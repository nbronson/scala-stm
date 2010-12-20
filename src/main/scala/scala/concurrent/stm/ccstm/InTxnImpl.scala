/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm
package ccstm


private[ccstm] object InTxnImpl extends ThreadLocal[InTxnImpl] {

  override def initialValue = new InTxnImpl

  def apply()(implicit mt: MaybeTxn): InTxnImpl = mt match {
    case x: InTxnImpl => x
    case _ => get // this will create one
  }

  private def active(txn: InTxnImpl): InTxnImpl = if (txn.internalCurrentLevel != null) txn else null

  def dynCurrentOrNull: InTxnImpl = active(get)

  def currentOrNull(implicit mt: MaybeTxn) = active(apply())
}

/** In CCSTM there is one `InTxnImpl` per thread, and it is reused across all
 *  transactions.
 *
 *  @author Nathan Bronson
 */
private[ccstm] class InTxnImpl extends AccessHistory with skel.AbstractInTxn {
  import CCSTM._
  import Txn._
  import skel.RollbackError

  //////////////// pre-transaction state

  private var _alternatives: List[InTxn => Any] = Nil

  def pushAlternative(block: InTxn => Any): Boolean = {
    val z = _alternatives.isEmpty
    _alternatives ::= block
    z
  }

  def takeAlternatives(): List[InTxn => Any] = {
    val z = _alternatives
    _alternatives = Nil
    z
  }

  //////////////// per-transaction state

  private var _barging: Boolean = false
  private var _bargeVersion: Version = 0

  /** Non-negative values are assigned slots, negative values are the bitwise
   *  complement of the last used slot value.
   */
  private var _slot: Slot = ~0

  private var _currentLevel: TxnLevelImpl = null
  protected def undoLog: TxnLevelImpl = _currentLevel
  protected def internalCurrentLevel: TxnLevelImpl = _currentLevel

  /** Subsumption dramatically reduces the overhead of nesting, but it doesn't
   *  allow partial rollback.  If we find out that partial rollback was needed
   *  then we disable subsumption and rerun the parent.
   */
  private var _subsumptionAllowed = true

  private var _pendingFailure: Throwable = null

  /** This is the inner-most level that contains all subsumption, or null if
   *  there is no subsumption taking place.
   */
  private var _subsumptionParent: TxnLevelImpl = null

  /** Higher wins.  Currently priority doesn't change throughout the lifetime
   *  of a rootLevel.  It would be okay for it to monotonically increase, so
   *  long as there is no change of the current txn's priority between the
   *  priority check on conflict and any subsequent waiting that occurs.
   */
  private var _priority: Int = 0

  /** The read version of this transaction.  It is guaranteed that all values
   *  read by this transaction have a version number less than or equal to this
   *  value, and that any transaction whose writes conflict with this
   *  transaction will label those writes with a version number greater than
   *  this value.  The read version must never be greater than
   *  `globalVersion.get`, must never decrease, and each time it is
   *  changed the read set must be revalidated.  Lazily assigned.
   */
  private var _readVersion: Version = 0

  override def toString = {
    ("InTxnImpl@" + hashCode.toHexString + "(" +
            (if (_currentLevel == null) "Detached" else status.toString) +
            ", slot=" + _slot +
            ", subsumptionAllowed=" + _subsumptionAllowed +
            ", priority=" + _priority +
            ", readCount=" + readCount  +
            ", bargeCount=" + bargeCount +
            ", writeCount=" + writeCount +
            ", readVersion=0x" + _readVersion.toHexString +
            (if (_barging) ", bargingVersion=0x" + _bargeVersion.toHexString else "") + ")")
  }

  //////////////// High-level behaviors

  def status: Status = _currentLevel.statusAsCurrent

  def currentLevel: NestingLevel = {
    if (_subsumptionParent != null) {
      _subsumptionAllowed = false
      _subsumptionParent.forceRollback(Txn.OptimisticFailureCause('restart_to_materialize_current_level, None))
      throw RollbackError
    }
    _currentLevel
  }

  def atomic[Z](exec: TxnExecutor, block: InTxn => Z): Z = {
    if (!_alternatives.isEmpty)
      atomicWithAlternatives(exec, block)
    else if (_currentLevel == null)
      topLevelAtomicImpl(exec, block)
    else
      nestedAtomicImpl(exec, block)
  }

  private def atomicWithAlternatives(exec: TxnExecutor, block: InTxn => Any): Nothing = {
    val z = atomicImpl(exec, block, takeAlternatives())
    throw new impl.AlternativeResult(z)
  }

  def atomicOneOf[Z](exec: TxnExecutor, blocks: Seq[InTxn => Z]): Z = {
    if (!_alternatives.isEmpty)
      throw new IllegalStateException("atomic.oneOf can't be mixed with orAtomic")
    atomicImpl(exec, blocks(0), blocks.toList.tail)
  }

  /** On commit, either returns a Z or throws the control-flow exception from
   *  the committed attempted; on rollback, throws an exception on a permanent
   *  rollback or `RollbackError` on a transient rollback.
   */
  private def nestedAttempt[Z](exec: TxnExecutor, prevFailures: Int, level: TxnLevelImpl, block: InTxn => Z, reusedReadThreshold: Int): Z = {
    checkBarging(prevFailures)
    nestedBegin(level, reusedReadThreshold)
    try {
      runBlock(exec, block)
    } finally {
      rethrowFromStatus(nestedComplete(exec))
    }
  }

  private def topLevelAttempt[Z](exec: TxnExecutor, prevFailures: Int, level: TxnLevelImpl, block: InTxn => Z): Z = {
    checkBarging(prevFailures)
    topLevelBegin(level)
    try {
      runBlock(exec, block)
    } finally {
      rethrowFromStatus(topLevelComplete(exec))
    }
  }

  private def runBlock[Z](exec: TxnExecutor, block: InTxn => Z): Z = {
    try {
      block(this)
    } catch {
      case x if x != RollbackError && !exec.isControlFlow(x) => {
        _currentLevel.forceRollback(Txn.UncaughtExceptionCause(x))
        null.asInstanceOf[Z]
      }
    }
  }

  private def rethrowFromStatus(status: Status) {
    status match {
      case rb: Txn.RolledBack => {
        rb.cause match {
          case Txn.UncaughtExceptionCause(x) => throw x
          case _ => throw RollbackError
        }
      }
      case _ =>
    }
  }

  private def topLevelAtomicImpl[Z](exec: TxnExecutor, block: InTxn => Z): Z = {
    var prevFailures = 0
    (while (true) {
      var level = new TxnLevelImpl(this, null, false)
      try {
        // successful attempt or permanent rollback either returns a Z or
        // throws an exception != RollbackError
        return topLevelAttempt(exec, prevFailures, level, block)
      } catch {
        case RollbackError =>
      }
      // we are only here if it is a transient rollback or an explicit retry
      if (level.status.asInstanceOf[RolledBack].cause == ExplicitRetryCause) {
        level = null // help the GC while waiting
        awaitRetry()
        prevFailures = 0
      } else {
        prevFailures += 1 // transient rollback, retry
      }
    }).asInstanceOf[Nothing]
  }

  private def nestedAtomicImpl[Z](exec: TxnExecutor, block: InTxn => Z): Z = {
    if (_subsumptionAllowed)
      subsumedNestedAtomicImpl(exec, block)
    else
      trueNestedAtomicImpl(exec, block)
  }

  private def subsumedNestedAtomicImpl[Z](exec: TxnExecutor, block: InTxn => Z): Z = {
    val outermost = _subsumptionParent == null
    if (outermost)
      _subsumptionParent = _currentLevel
    try {
      try {
        block(this)
      } catch {
        case x if x != RollbackError && !exec.isControlFlow(x) => {
          // partial rollback is required, but we can't do it here
          _subsumptionAllowed = false
          _currentLevel.forceRollback(Txn.OptimisticFailureCause('restart_to_enable_partial_rollback, Some(x)))
          throw RollbackError
        }
      }
    } finally {
      if (outermost)
        _subsumptionParent = null
    }
  }

  private def trueNestedAtomicImpl[Z](exec: TxnExecutor, block: InTxn => Z): Z = {
    var prevFailures = 0
    (while (true) {
      // fail back to parent if it has been rolled back
      _currentLevel.requireActive()

      val level = new TxnLevelImpl(this, _currentLevel, false)
      try {
        return nestedAttempt(exec, prevFailures, level, block, -1)
      } catch {
        case RollbackError =>
      }
      // we are only here if it is a transient rollback or an explicit retry
      if (level.status.asInstanceOf[RolledBack].cause == ExplicitRetryCause) {
        // Reads are still in access history.  Retry the parent, which will
        // treat the reads as its own
        _currentLevel.forceRollback(ExplicitRetryCause)
        throw RollbackError
      }
      prevFailures += 1 // transient rollback, retry
    }).asInstanceOf[Nothing]
  }

  /** On commit, returns a Z or throws an exception other than `RollbackError`.
   *  On permanent rollback, throws an exception other than `RollbackError`.
   *  On nested explicit retry, throws `RollbackError` and sets the parent
   *  level's status to `RolledBack(ExplicitRetryCause)`.  All other cases
   *  result in a retry within the method.
   */
  private def atomicImpl[Z](exec: TxnExecutor, block: InTxn => Z, alternatives: List[InTxn => Z]): Z = {
    if (Stats.top != null)
      recordAlternatives(alternatives)

    var reusedReadThreshold = -1
    (while (true) {
      var level: TxnLevelImpl = null
      var prevFailures = 0
      while (level == null) {
        // fail back to parent if it has been rolled back
        if (_currentLevel != null)
          _currentLevel.requireActive()

        // phantom attempts reuse reads from the previous one
        val phantom = reusedReadThreshold >= 0
        level = new TxnLevelImpl(this, _currentLevel, phantom)
        try {
          // successful attempt or permanent rollback either returns a Z or
          // throws an exception != RollbackError
          val b = if (phantom) { (_: InTxn) => atomicImpl(exec, alternatives.head, alternatives.tail) } else block
          if (_currentLevel != null)
            return nestedAttempt(exec, prevFailures, level, b, reusedReadThreshold)
          else
            return topLevelAttempt(exec, prevFailures, level, b)
        } catch {
          case RollbackError =>
        }
        // we are only here if it is a transient rollback or an explicit retry
        if (level.status.asInstanceOf[RolledBack].cause != ExplicitRetryCause) {
          // transient rollback, retry (not as a phantom)
          prevFailures += 1
          reusedReadThreshold = -1
          level = null
        }
        // else explicit retry, exit the loop
      }

      // The attempt ended in an explicit retry.  If there are alternatives
      // then we run a phantom attempt that reuses the read from the last real
      // attempt on the block.  Phantom attempts can also end in explicit retry
      // (if all of the alternatives triggered retry), in which case we treat
      // them the same as a regular block with no alternatives.
      val phantom = reusedReadThreshold >= 0
      if (!phantom && !alternatives.isEmpty) {
        // rerun a phantom
        reusedReadThreshold = level.prevReadCount
      } else {
        level = null

        // no more alternatives, reads are still in access history
        if (_currentLevel != null) {
          // retry the parent, which will treat the reads as its own
          _currentLevel.forceRollback(ExplicitRetryCause)
          throw RollbackError
        }

        // top-level retry after waiting for something to change
        awaitRetry()

        // rerun the real block, now that the await has completed
        reusedReadThreshold = -1
      }
    }).asInstanceOf[Nothing]
  }

  private def recordAlternatives(alternatives: List[_]) {
    (if (_currentLevel == null) Stats.top else Stats.nested).alternatives += alternatives.size
  }

  def rollback(cause: RollbackCause): Nothing = {
    // We need to grab the version numbers from writes and pessimistic reads
    // before the status is set to rollback, because as soon as the top-level
    // txn is marked rollback other threads can steal ownership.  This is
    // harmless if some other type of rollback occurs.
    if (cause == ExplicitRetryCause)
      addLatestWritesAsReads(_barging)

    _currentLevel.forceRollback(cause)
    throw RollbackError
  }


  //////////////// Implementation

  /** On return, the read version will have been the global version at some
   *  point during the call, the read version will be >= minReadVersion, and
   *  all reads will have been validated against the new read version.  Throws
   *  `RollbackError` if invalid.
   */
  private def revalidate(minReadVersion: Version) {
    _readVersion = freshReadVersion(minReadVersion)
    if (!revalidateImpl())
      throw RollbackError
  }

  /** Calls revalidate(version) if version > _readVersion. */
  private def revalidateIfRequired(version: Version) {
    if (version > _readVersion)
      revalidate(version)
  }

  /** Returns true if valid. */
  private def revalidateImpl(): Boolean = {
    // we check the oldest reads first, so that we roll back all intervening
    // invalid nesting levels
    var i = 0
    while (i < readCount) {
      val h = readHandle(i)
      val problem = checkRead(h, readVersion(i))
      if (problem != null) {
        readLocate(i).asInstanceOf[NestingLevel].requestRollback(Txn.OptimisticFailureCause(problem, Some(h)))
        return false
      }
      i += 1
    }
    fireWhileValidating()
    
    !this.status.isInstanceOf[RolledBack]
  }

  /** Returns the name of the problem on failure, null on success. */ 
  private def checkRead(handle: Handle[_], ver: CCSTM.Version): Symbol = {
    (while (true) {
      val m1 = handle.meta
      if (!changing(m1) || owner(m1) == _slot) {
        if (version(m1) != ver)
          return 'stale_read
        // okay
        return null
      } else if (owner(m1) == nonTxnSlot) {
        // non-txn updates don't set changing unless they will install a new
        // value, so we are the only party that can yield
        return 'read_vs_nontxn_write
      } else {
        // Either this txn or the owning txn must roll back.  We choose to
        // give precedence to the owning txn, as it is the writer and is
        // trying to commit.  There's a bit of trickiness since o may not be
        // the owning transaction, it may be a new txn that reused the same
        // slot.  If the actual owning txn committed then the version
        // number will have changed, which we will detect on the next pass
        // (because we aren't incrementing i, so we will revisit this
        // entry).  If it rolled back then we don't have to roll back, so
        // looking at o to make the decision doesn't affect correctness
        // (although it might result in an unnecessary rollback).  If the
        // owner slot is the same but the changing bit is not set (or if
        // the owner txn is null) then we are definitely observing a reused
        // slot and we can avoid the spurious rollback.
        val o = slotManager.lookup(owner(m1))
        if (null != o) {
          val s = o.status
          val m2 = handle.meta
          if (changing(m2) && owner(m2) == owner(m1)) {
            if (!s.isInstanceOf[Txn.RolledBack])
              return 'read_vs_pending_commit

            stealHandle(handle, m2, o)
          }
        }
      }
      // try again
    }).asInstanceOf[Nothing]
  }


  /** After this method returns, either the current transaction will have been
   *  rolled back, or it is safe to wait for `currentOwner` to be `Committed`
   *  or doomed.  
   */
  def resolveWriteWriteConflict(owningRoot: TxnLevelImpl, contended: AnyRef) {
    // if write is not allowed, throw an exception of some sort
    requireActive()

    // TODO: boost our priority if we have written?

    // This test is _almost_ symmetric.  Tie goes to neither.
    if (this._priority <= owningRoot.txn._priority) {
      resolveAsWWLoser(owningRoot, contended, false, 'owner_has_priority)
    } else {
      // This will resolve the conflict regardless of whether it succeeds or fails.
      val s = owningRoot.requestRollback(Txn.OptimisticFailureCause('steal_by_higher_priority, Some(contended)))
      if (s == Preparing || s == Committing) {
        // owner can't be remotely canceled
        val msg = if (s == Preparing) 'owner_is_preparing else 'owner_is_committing
        resolveAsWWLoser(owningRoot, contended, true, msg)
      }
    }
  }

  private def resolveAsWWLoser(owningRoot: TxnLevelImpl, contended: AnyRef, ownerIsCommitting: Boolean, msg: Symbol) {
    if (!shouldWaitAsWWLoser(owningRoot, ownerIsCommitting)) {
      // The failed write is in the current nesting level, so we only need to
      // invalidate one nested atomic block.  Nothing will get better for us
      // until the current owner completes or this txn has a higher priority,
      // however.
      _currentLevel.forceRollback(Txn.OptimisticFailureCause(msg, Some(contended)))
      throw RollbackError
    }
  }

  private def shouldWaitAsWWLoser(owningRoot: TxnLevelImpl, ownerIsCommitting: Boolean): Boolean = {
    // If we haven't performed any writes, there is no point in not waiting.
    if (writeCount == 0 && !writeResourcesPresent)
      return true

    // If the current owner is in the process of committing then we should
    // wait, because we can't succeed until their commit is done.  This means
    // that regardless of priority all of this txn's retries are just useless
    // spins.  This is especially important in the case of external resources
    // that perform I/O during commit.
    if (ownerIsCommitting)
      return true

    // We know that priority <= currentOwner.priority, because we're the loser.
    // If the priorities match exactly (unlikely but possible) then we can't
    // have both losers wait or we will get a deadlock.
    if (_priority == owningRoot.txn._priority)
      return false

    // Now we're in heuristic territory, waiting or rolling back are both
    // reasonable choices.  Waiting might reduce rollbacks, but it increases
    // the number of thread sleep/wakeup transitions, each of which is
    // expensive.  Our heuristic is to wait only if we are barging, which
    // indicates that we are having trouble making forward progress using
    // just blind optimism.  This also guarantees that doomed transactions
    // never block anybody, because barging txns have visible readers.
    return _barging
  }

  //////////////// begin + commit

  private def checkBarging(prevFailures: Int) {
    if (prevFailures >= BargeThreshold && !_barging) {
      _barging = true
      _bargeVersion = freshReadVersion
    }
  }

  private def nestedBegin(child: TxnLevelImpl, reusedReadThreshold: Int) {
    // link to child races with remote rollback. pushIfActive detects the race
    // and returns false
    if (!_currentLevel.pushIfActive(child)) {
      child.forceRollback(this.status.asInstanceOf[RolledBack].cause)
      throw RollbackError
    }

    // successfully begun
    _currentLevel = child
    checkpointCallbacks()
    checkpointAccessHistory(reusedReadThreshold)
  }

  private def topLevelBegin(child: TxnLevelImpl) {
    if (_slot < 0) {
      _priority = skel.FastSimpleRandom.nextInt()
      _slot = slotManager.assign(child, ~_slot)
      _readVersion = freshReadVersion
    }
    // else we must be a top-level alternative
    _currentLevel = child
  }

  private def nestedComplete(exec: TxnExecutor): Txn.Status = {
    val child = _currentLevel
    if (child.attemptMerge()) {
      // child was successfully merged
      mergeAccessHistory()
      _currentLevel = child.parUndo
      Committed
    } else {
      val s = this.status

      // we handle explicit retry by retaining (merging) the read set while
      // rolling back the writes and firing after-rollback handlers
      if (s.asInstanceOf[Txn.RolledBack].cause == ExplicitRetryCause)
        retainRetrySet()

      // callbacks must be last, because they might throw an exception
      rollbackAccessHistory(_slot, s)
      val handlers = rollbackCallbacks()
      _currentLevel = child.parUndo
      if (handlers != null)
        fireAfterCompletionAndThrow(handlers, exec, s, null)
      s
    }
  }

  private def awaitRetry() {
    assert(_slot >= 0)
    val rs = takeRetrySet(_slot)
    detach()
    val f = _pendingFailure
    if (f != null) {
      _pendingFailure = null
      throw f
    }
    rs.awaitRetry()
  }

  private def retainRetrySet() {
    // this will turn rollbackAccessHistory into a no-op for the reads
    _currentLevel.retainReadsAndBarges = true
  }

  private def topLevelComplete(exec: TxnExecutor): Status = {
    if (attemptTopLevelComplete(exec)) {
      finishTopLevelCommit(exec)
      Txn.Committed
    } else {
      val s = this.status
      if (s.asInstanceOf[Txn.RolledBack].cause == ExplicitRetryCause)
        finishTopLevelRetry(exec, s)
      else
        finishTopLevelRollback(exec, s)
      s
    }
  }

  private def finishTopLevelCommit(exec: TxnExecutor) {
    // we can start subsuming again
    _subsumptionAllowed = true

    resetAccessHistory()
    val handlers = resetCallbacks()
    detach()

    val f = _pendingFailure
    _pendingFailure = null
    fireAfterCompletionAndThrow(handlers, exec, Txn.Committed, f)
  }

  private def finishTopLevelRollback(exec: TxnExecutor, s: Txn.Status) {
    rollbackAccessHistory(_slot, s)
    val handlers = rollbackCallbacks()
    detach()

    val f = _pendingFailure
    _pendingFailure = null
    fireAfterCompletionAndThrow(handlers, exec, s, f)
  }

  private def finishTopLevelRetry(exec: TxnExecutor, s: Txn.Status) {
    retainRetrySet()
    rollbackAccessHistory(_slot, s)
    val handlers = rollbackCallbacks()

    // don't detach, but we do need to give up the current level
    _currentLevel = null
    assert(writeCount == 0)

    if (handlers != null)
      _pendingFailure = fireAfterCompletion(handlers, exec, s, _pendingFailure)

    if (_pendingFailure != null) {
      // scuttle the retry
      takeRetrySet(_slot)
      val f = _pendingFailure
      _pendingFailure = null
      throw f
    }
  }

  private def attemptTopLevelComplete(exec: TxnExecutor): Boolean = {
    val root = _currentLevel

    fireBeforeCommitCallbacks()

    // Read-only transactions are easy to commit, because all of the reads
    // are already guaranteed to be consistent.  We still have to release the
    // barging locks, though.
    if (writeCount == 0 && !writeResourcesPresent) {
      if (bargeCount > 0)
        readOnlyCommitBarges()
      return root.setCommittedIfActive()
    }

    if (!root.statusCAS(Active, Preparing) || !acquireLocks())
      return false

    // this is our linearization point
    val cv = freshCommitVersion(_readVersion, globalVersion.get)

    // if the reads are still valid, then they were valid at the linearization
    // point
    if (!revalidateImpl())
      return false

    fireWhilePreparingCallbacks()

    if (externalDecider != null) {
      // external decider doesn't have to content with cancel by other threads
      if (!root.statusCAS(Preparing, Prepared) || !consultExternalDecider())
        return false

      root.setCommitting()
    } else {
      // attempt to decide commit
      if (!root.statusCAS(Preparing, Committing))
        return false
    }

    _pendingFailure = fireWhileCommittingCallbacks(exec)
    commitWrites(cv)
    root.setCommitted()

    return true
  }

  private def consultExternalDecider(): Boolean = {
    try {
      if (!externalDecider.shouldCommit(this))
        _currentLevel.forceRollback(OptimisticFailureCause('external_decision, None))
    } catch {
      case x => _currentLevel.forceRollback(UncaughtExceptionCause(x))
    }
    this.status eq Prepared
  }

  private def detach() {
    assert(_slot >= 0 && readCount == 0 && bargeCount == 0 && writeCount == 0)
    slotManager.release(_slot)
    _slot = ~_slot
    _currentLevel = null
    _barging = false
  }

  private def acquireLocks(): Boolean = {
    var i = writeCount - 1
    while (i >= 0) {
      // acquireLock is inlined to reduce the call depth from TxnExecutor.apply
      val handle = getWriteHandle(i)
      var m = 0L
      do {
        m = handle.meta
        if (owner(m) != _slot && m != txnLocalMeta)
          return false
        // we have to use CAS to guard against remote steal
      } while (!changing(m) && !handle.metaCAS(m, withChanging(m)))
      i -= 1
    }
    return true
  }

  private def commitWrites(cv: Long) {
    var wakeups = 0L
    var i = writeCount - 1
    while (i >= 0) {
      val handle = getWriteHandle(i).asInstanceOf[Handle[Any]]

      val m = handle.meta
      if (pendingWakeups(m))
        wakeups |= wakeupManager.prepareToTrigger(handle)

      //assert(owner(m) == _slot)

      val v = getWriteSpecValue[Any](i)

      // release the lock, clear the PW bit, and update the version, but only
      // if this was the entry that actually acquired ownership
      if (wasWriteFreshOwner(i)) {
        // putting the data store in both sides allows the volatile writes to
        // be coalesced
        handle.data = v
        handle.meta = withCommit(m, cv)
      } else {
        handle.data = v
      }

      // because we release when we find the original owner, it is important
      // that we traverse in reverse order.  There are no duplicates
      i -= 1
    }

    // We still have ownership (but not exclusive) for pessimistic reads, and
    // we still have exclusive ownership of pessimistic reads that turned into
    // writes (or that share metadata with writes).  We rollback the vlock for
    // the former and commit the vlock for the later.
    i = bargeCount - 1
    while (i >= 0) {
      val handle = bargeHandle(i)
      val m = handle.meta

      if (changing(m)) {
        // a handle sharing this base and metaOffset must also be present in
        // the write buffer, so we should bump the version number
        handle.meta = withCommit(m, cv)
      } else {
        // this was only a pessimistic read, no need to bump the version
        rollbackHandle(handle, _slot, m)
      }

      i -= 1
    }

    // unblock anybody waiting on a value change
    if (wakeups != 0L)
      wakeupManager.trigger(wakeups)
  }

  private def readOnlyCommitBarges() {
    var i = bargeCount - 1
    while (i >= 0) {
      rollbackHandle(bargeHandle(i), _slot)
      i -= 1
    }
  }

  //////////////// status checks

  override def requireActive() {
    val cur = _currentLevel
    if (cur == null)
      throw new IllegalStateException("no active transaction")
    cur.requireActive()
  }

  //////////////// lock management - similar to NonTxn but we also check for remote rollback

  private def weakAwaitUnowned(handle: Handle[_], m0: Meta) {
    CCSTM.weakAwaitUnowned(handle, m0, _currentLevel)
  }

  /** Returns the pre-acquisition metadata. */
  private def acquireOwnership(handle: Handle[_]): Meta = {
    var m = handle.meta
    if (owner(m) == _slot)
      return m
    (while (true) {
      if (owner(m) != unownedSlot)
        weakAwaitUnowned(handle, m)
      else if (handle.metaCAS(m, withOwner(m, _slot)))
        return m
      m = handle.meta
    }).asInstanceOf[Nothing]
  }

  private def tryAcquireOwnership(handle: Handle[_], m0: Meta): Boolean = {
    owner(m0) == unownedSlot && handle.metaCAS(m0, withOwner(m0, _slot))
  }

  //////////////// barrier implementations
  
  def get[T](handle: Handle[T]): T = {
    requireActive()

    var m1 = handle.meta
    if (owner(m1) == _slot) {
      // Self-owned.  This particular base+offset might not be in the write
      // buffer, but it's definitely not in anybody else's.
      return stableGet(handle)
    }

    if (readShouldBarge(m1))
      return bargingRead(handle)

    var m0 = 0L
    var value: T = null.asInstanceOf[T]
    do {
      m0 = m1
      while (changing(m0)) {
        weakAwaitUnowned(handle, m0)
        m0 = handle.meta
      }
      revalidateIfRequired(version(m0))
      value = handle.data
      m1 = handle.meta
    } while (changingAndVersion(m0) != changingAndVersion(m1))

    // Stable read.  The second read of handle.meta is required for
    // opacity, and it also enables the read-only commit optimization.
    recordRead(handle, version(m1))
    return value
  }

  private def readShouldBarge(meta: Meta): Boolean = {
    _barging && version(meta) >= _bargeVersion
  }

  private def bargingRead[T](handle: Handle[T]): T = {
    val mPrev = acquireOwnership(handle)
    recordBarge(handle)
    revalidateIfRequired(version(mPrev))
    return handle.data
  }

  def getWith[T,Z](handle: Handle[T], f: T => Z): Z = {
    if (_barging && version(handle.meta) >= _bargeVersion)
      return f(get(handle))

    val u = unrecordedRead(handle)
    val result = f(u.value)
    if (!u.recorded) {
      val callback = new Function[NestingLevel, Unit] {
        var _latestRead = u

        def apply(level: NestingLevel) {
          if (!isValid)
            level.requestRollback(OptimisticFailureCause('invalid_getWith, Some(handle)))
        }

        private def isValid: Boolean = {
          if (_latestRead == null || _latestRead.stillValid)
            return true

          val m1 = handle.meta
          if (owner(m1) == _slot) {
            // We know that our original read did not come from the write
            // buffer, because !u.recorded.  That means that to redo this
            // read we should go to handle.data, which has the most recent
            // value from which we should read.
            _latestRead = null
            return (result == f(handle.data))
          }

          // reread, and see if that changes the result
          _latestRead = unrecordedRead(handle)

          return (result == f(_latestRead.value))
        }
      }

      // It is safe to skip calling callback.valid() here, because we
      // have made no calls into the txn that might have resulted in it
      // moving its virtual snapshot forward.  This means that the
      // unrecorded read that initialized u is consistent with all of the
      // reads performed so far.
      whileValidating(callback)
    }

    return result
  }

  def relaxedGet[T](handle: Handle[T], equiv: (T, T) => Boolean): T = {
    if (_barging && version(handle.meta) >= _bargeVersion)
      return get(handle)

    val u = unrecordedRead(handle)
    val snapshot = u.value
    if (!u.recorded) {
      val callback = new Function[NestingLevel, Unit] {
        var _latestRead = u

        def apply(level: NestingLevel) {
          if (!isValid)
            level.requestRollback(OptimisticFailureCause('invalid_getWith, Some(handle)))
        }

        private def isValid: Boolean = {
          if (_latestRead == null || _latestRead.stillValid)
            return true

          val m1 = handle.meta
          if (owner(m1) == _slot) {
            // We know that our original read did not come from the write
            // buffer, because !u.recorded.  That means that to redo this
            // read we should go to handle.data, which has the most recent
            // value from which we should read.
            _latestRead = null
            return equiv(snapshot, handle.data)
          }

          // reread, and see if that changes the result
          _latestRead = unrecordedRead(handle)

          return equiv(snapshot, _latestRead.value)
        }
      }

      // It is safe to skip calling callback.valid() here, because we
      // have made no calls into the txn that might have resulted in it
      // moving its virtual snapshot forward.  This means that the
      // unrecorded read that initialized u is consistent with all of the
      // reads performed so far.
      whileValidating(callback)
    }

    return snapshot
  }

  def unrecordedRead[T](handle: Handle[T]): UnrecordedRead[T] = {
    requireNotDecided()

    var m1 = handle.meta
    var v: T = null.asInstanceOf[T]
    val rec = (if (owner(m1) == _slot) {
      v = stableGet(handle)
      true
    } else {
      var m0 = 0L
      do {
        m0 = m1
        while (changing(m0)) {
          if (status != Active) {
            // can't wait
            _currentLevel.forceRollback(OptimisticFailureCause('late_invalid_unrecordedRead, Some(handle)))
            throw RollbackError
          }
          weakAwaitUnowned(handle, m0)
          m0 = handle.meta
        }
        if (version(m0) > _readVersion) {
          if (status != Active) {
            // can't wait
            _currentLevel.forceRollback(OptimisticFailureCause('late_invalid_unrecordedRead, Some(handle)))
            throw RollbackError
          }
          revalidate(version(m0))
        }
        v = handle.data
        m1 = handle.meta
      } while (changingAndVersion(m0) != changingAndVersion(m1))
      false
    })

    new UnrecordedRead[T] {
      def context: Option[InTxnImpl] = Some(InTxnImpl.this.asInstanceOf[InTxnImpl])
      def value: T = v
      def stillValid = {
        val m = handle.meta
        version(m) == version(m1) && (!changing(m) || owner(m) == _slot)
      }
      def recorded = rec
    }
  }

  private def freshOwner(mPrev: Meta) = owner(mPrev) == unownedSlot

  def set[T](handle: Handle[T], v: T) {
    requireActive()
    val mPrev = acquireOwnership(handle)
    val f = freshOwner(mPrev)
    put(handle, f, v)

    // This might not be a blind write, because meta might be shared with other
    // values that are subsequently read by the transaction.  We don't need to
    // record a read set entry, however, because nobody can modify it after we
    // grab ownership.  This means it suffices to check against _readVersion.
    // We must put something in the buffer before calling revalidate in case we
    // roll back, so that the ownership gets released.
    //
    // If not f, then this was already self-owned.  This particular base+offset
    // might not be in the write buffer, but it's definitely not in anybody
    // else's.
    if (f)
      revalidateIfRequired(version(mPrev))
  }
  
  def swap[T](handle: Handle[T], v: T): T = {
    requireActive()
    val mPrev = acquireOwnership(handle)
    val f = freshOwner(mPrev)
    val v0 = swap(handle, f, v)
    if (f)
      revalidateIfRequired(version(mPrev))
    v0
  }

  def trySet[T](handle: Handle[T], v: T): Boolean = {
    requireActive()

    val m0 = handle.meta
    if (owner(m0) == _slot) {
      put(handle, false, v)
      return true
    }

    if (!tryAcquireOwnership(handle, m0))
      return false
    put(handle, true, v)
    revalidateIfRequired(version(m0))
    return true
  }

  def compareAndSet[T](handle: Handle[T], before: T, after: T): Boolean = {
    transformIfDefined(handle, new PartialFunction[T,T] {
      def isDefinedAt(v: T): Boolean = before == v
      def apply(v: T): T = after
    })
  }

  def compareAndSetIdentity[T, R <: T with AnyRef](handle: Handle[T], before: R, after: T): Boolean = {
    transformIfDefined(handle, new PartialFunction[T,T] {
      def isDefinedAt(v: T): Boolean = (before eq v.asInstanceOf[AnyRef])
      def apply(v: T): T = after
    })
  }

  def getAndTransform[T](handle: Handle[T], func: T => T): T = {
    requireActive()
    val mPrev = acquireOwnership(handle)
    val f = freshOwner(mPrev)
    val v0 = getAndTransform(handle, f, func)
    if (f)
      revalidateIfRequired(version(mPrev))
    v0
  }

  def transformAndGet[T](handle: Handle[T], func: T => T): T = {
    requireActive()
    val mPrev = acquireOwnership(handle)
    val f = freshOwner(mPrev)
    val v1 = transformAndGet(handle, f, func)
    if (f)
      revalidateIfRequired(version(mPrev))
    v1
  }

  def transformIfDefined[T](handle: Handle[T], pf: PartialFunction[T,T]): Boolean = {
    val u = unrecordedRead(handle)
    if (!pf.isDefinedAt(u.value)) {
      // make sure it stays undefined
      if (!u.recorded) {        
        val callback = new Function[NestingLevel, Unit] {
          var _latestRead = u

          def apply(level: NestingLevel) {
            if (!isValid)
              level.requestRollback(OptimisticFailureCause('invalid_getWith, Some(handle)))
          }

          private def isValid: Boolean = {
            if (!_latestRead.stillValid) {
              // if defined after reread then return false==invalid
              _latestRead = unrecordedRead(handle)
              !pf.isDefinedAt(_latestRead.value)
            } else {
              true
            }
          }
        }
        whileValidating(callback)
      }
      false
    } else {
      val v = get(handle)
      if (!u.stillValid && !pf.isDefinedAt(v)) {
        // value changed after unrecordedRead
        false
      } else {
        // still defined, do the actual getAndTransform
        set(handle, pf(v))
        true
      }
    }
  }

  //////////// TxnLocal stuff

  // We store transactional local values in the write buffer by pretending
  // that they are proper handles, but their data and metadata aren't actually
  // backed by anything.

  def txnLocalFind(local: TxnLocalImpl[_]): Int = findWrite(local)
  def txnLocalGet[T](index: Int): T = getWriteSpecValue[T](index)
  def txnLocalInsert[T](local: TxnLocalImpl[T], v: T) { writeAppend(local, false, v) }
  def txnLocalUpdate[T](index: Int, v: T) { writeUpdate(index, v) }
}
