/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ccstm


private[ccstm] object InTxnImpl extends ThreadLocal[InTxnImpl] {

  override def initialValue = new InTxnImpl

  def apply()(implicit mt: MaybeTxn): InTxnImpl = mt match {
    case x: InTxnImpl => x
    case _ => get // this will create one
  }

  private def active(txn: InTxnImpl): InTxnImpl = if (txn._currentLevel != null) txn else null

  def dynCurrentOrNull: InTxnImpl = active(get)

  def currentOrNull(implicit mt: MaybeTxn) = active(apply())
}

/** In CCSTM there is one `InTxnImpl` per thread, and it is reused across all
 *  transactions.
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
  private var _slot: Slot = 0
  private var _currentLevel: TxnLevelImpl = null

  /** This is all of the remembered levels, even ones with depth greater than
   *  `_currentLevel`.  `levels(_currentLevel.depth) == _currentLevel`
   */
  val levels: scala.collection.mutable.ArrayBuffer[TxnLevelImpl] =
      (new scala.collection.mutable.ArrayBuffer[TxnLevelImpl]) += (new TxnLevelImpl(this, null, 0))

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

  private var _retrySet: ReadSetBuilder = null

  override def toString = {
    ("InTxnImpl@" + hashCode.toHexString + "(" + status +
            ", slot=" + _slot +
            ", priority=" + _priority +
            ", readCount=" + readCount  +
            ", writeCount=" + writeCount +
            ", retrySet.size=" + (if (_retrySet == null) "N/A" else _retrySet.size.toString) +
            ", readVersion=0x" + _readVersion.toHexString +
            (if (_barging) ", barging" else "") + ")")
  }

  //////////////// High-level behaviors

  def status: Status = _currentLevel.statusAsCurrent
  def undoLog: TxnLevelImpl = _currentLevel
  def currentLevel = _currentLevel.nestingLevel
  def rootLevel = levels(0).nestingLevel

  /** There are N ways that a single attempt can complete:
   *  - block returns normally, commit;
   *  - block returns normally,
   *  - block throws a control flow exception, level commits;
   *  - block throws a control flow  */

  /** On commit, either returns a Z or throws the control-flow exception from
   *  the committed attempted; on rollback, throws an exception on a permanent
   *  rollback or `RollbackError` on a transient rollback.
   */
  def attempt[Z](exec: TxnExecutor, prevFailures: Int, level: TxnLevelImpl, block: InTxn => Z): Z = {
    var bug3965check = true
    begin(prevFailures, level)
    try {
      try {
        block(this)
      } catch {
        case x if x != RollbackError && !exec.isControlFlow(x) => {
          level.forceRollback(Txn.UncaughtExceptionCause(x))
          throw RollbackError
        }
      }
    } finally {
      assert(bug3965check)
      bug3965check = false
      complete(exec) match {
        case Txn.RolledBack(Txn.UncaughtExceptionCause(x)) => throw x
        case Txn.RolledBack(_) => throw RollbackError
        case _ =>
      }
    }
  }

  def atomic[Z](exec: TxnExecutor, block: InTxn => Z): Z = {
    if (!_alternatives.isEmpty) {
      val z = atomicOneOf(exec, block :: takeAlternatives())
      throw new impl.AlternativeResult(z)
    } else {
      atomicImpl(exec, block)
    }
  }

  def atomicImpl[Z](exec: TxnExecutor, block: InTxn => Z): Z = {
    (while (true) {
      var prevFailures = 0
      val level = childLevel()
      do {
        // fail back to parent if it has been rolled back
        if (_currentLevel != null)
          _currentLevel.requireActive()

        try {
          // successful attempt or permanent rollback either returns a Z or
          // throws an exception != RollbackError
          return attempt(exec, prevFailures, level, block)
        } catch {
          case RollbackError =>
        }
        prevFailures += 1
      } while (level.status.asInstanceOf[RolledBack].cause != ExplicitRetryCause)

      // we're out of alternatives

      if (_currentLevel != null) {
        // retry the parent
        _currentLevel.forceRollback(ExplicitRetryCause)
        throw RollbackError
      }

      // top-level retry after waiting for something to change
      takeRetrySet().result().awaitRetry()
    }).asInstanceOf[Nothing]
  }

  private def childLevel(): TxnLevelImpl = {
    val d = if (_currentLevel == null) 0 else (1 + _currentLevel.depth)
    if (d == levels.size)
      levels += new TxnLevelImpl(this, _currentLevel, d)
    levels(d)
  }

  def atomicOneOf[Z](exec: TxnExecutor, blocks: Seq[InTxn => Z]): Z = {
    if (!_alternatives.isEmpty)
      throw new IllegalStateException("atomic.oneOf can't be mixed with orAtomic")

    (while (true) {
      var rsSum: ReadSetBuilder = null

      for (block <- blocks) {
        var prevFailures = 0
        val level = childLevel()
        do {
          // fail back to parent if it has been rolled back
          if (_currentLevel != null)
            _currentLevel.requireActive()

          try {
            // successful attempt or permanent rollback either returns a Z or
            // throws an exception != RollbackError
            return attempt(exec, prevFailures, level, block)
          } catch {
            case RollbackError =>
          }
          prevFailures += 1
        } while (level.status.asInstanceOf[RolledBack].cause != ExplicitRetryCause)

        val rs = takeRetrySet()
        if (rsSum != null)
          rsSum ++= rs
        else
          rsSum = rs
      }

      // we're out of alternatives
      
      if (_currentLevel != null) {
        // retry the parent, including the sum of all of these retry sets
        _retrySet = rsSum
        _currentLevel.forceRollback(ExplicitRetryCause)
        throw RollbackError
      }

      // top-level retry after waiting for something to change
      rsSum.result().awaitRetry()
    }).asInstanceOf[Nothing]
  }

  def rollback(cause: RollbackCause): Nothing = {
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
      if (h != null) {
        val problem = checkRead(h, readVersion(i))
        if (problem != null) {
          readLocate(i).asInstanceOf[NestingLevel].requestRollback(Txn.OptimisticFailureCause(problem, Some(h)))
          return false
        }
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
          return 'version_changed
        // okay
        return null
      } else if (owner(m1) == NonTxnSlot) {
        // non-txn updates don't set changing unless they will install a new
        // value, so we are the only party that can yield
        return 'pending_nontxn_write
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
              return 'pending_commit

            stealHandle(handle, m2, o)
          }
        }
      }
      // try again
    }).asInstanceOf[Nothing]
  }

  private def fireWhileValidating() {
    var level = _currentLevel
    var i = whileValidatingList.size - 1
    while (i >= 0) {
      while (level.prevWhileValidatingSize > i)
        level = level.par
      if (level.status != Txn.Active) {
        // skip the remaining handlers for this level
        i = level.prevWhileValidatingSize
      } else {
        try {
          if (!whileValidatingList(i)())
            level.forceRollback(OptimisticFailureCause('validation_handler, None))
        } catch {
          case x => level.forceRollback(UncaughtExceptionCause(x))
        }
      }
      i -= 1
    }
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
      val s = owningRoot.requestRollback(Txn.OptimisticFailureCause('steal_from_lower_priority, Some(contended)))
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

  private def writeResourcesPresent: Boolean = {
    !whilePreparingList.isEmpty || !whileCommittingList.isEmpty || externalDecider != null
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
    // never block anybody, because barging txns effectively have visible
    // readers.
    return _barging
  }

  //////////////// begin + commit

  def begin(prevFailures: Int, child: TxnLevelImpl) {
    if (prevFailures >= 3)
      _barging = true
    if (_currentLevel == null)
      topLevelBegin(child)
    else
      nestedBegin(child)
  }

  private def nestedBegin(child: TxnLevelImpl) {
    // link to child races with remote rollback
    if (!_currentLevel.pushIfActive(child)) {
      assert(this.status.isInstanceOf[RolledBack])
      // null localStatus will forward to parent's status
      child.status = this.status
      throw RollbackError
    }

    // successfully begun
    child.init()
    _currentLevel = child
    checkpointCallbacks(child)
    checkpointAccessHistory()
  }

  private def topLevelBegin(child: TxnLevelImpl) {
    child.init()
    _currentLevel = child
    //_priority = CCSTM.hash(_currentLevel, 0)
    // TODO: compute priority lazily, only if needed
    _priority = FastSimpleRandom.nextInt() // CCSTM.hash(_currentLevel, 0)
    // TODO: advance to a new slot in a fixed-cycle way to reduce steals from non-owners
    _slot = slotManager.assign(_currentLevel, _slot)
    _readVersion = freshReadVersion
  }

  def complete(exec: TxnExecutor): Txn.Status = {
    if (_currentLevel.par == null)
      topLevelComplete(exec)
    else
      nestedComplete(exec)
  }

  private def nestedComplete(exec: TxnExecutor): Txn.Status = {
    val child = _currentLevel
    if (child.attemptMerge()) {
      // child was successfully merged
      mergeAccessHistory()
      _currentLevel = child.par
      Committed
    } else {
      val s = this.status

      // we must accumulate the retry set before rolling back the access history
      if (s.asInstanceOf[Txn.RolledBack].cause == ExplicitRetryCause)
        accumulateRetrySet()

      // callbacks must be last, because they might throw an exception
      rollbackAccessHistory(_slot)
      val handlers = rollbackCallbacks(child)
      _currentLevel = child.par
      fireAfterCompletion(handlers, exec, s)
      s
    }
  }

  private def topLevelComplete(exec: TxnExecutor): Status = {
    val committed = attemptTopLevelComplete()
    val s = if (committed) Committed else this.status

    if (!committed) {
      if (s.asInstanceOf[Txn.RolledBack].cause == ExplicitRetryCause)
        accumulateRetrySet()

      // this releases the locks
      rollbackAccessHistory(_slot)
    }

    val handlers = if (committed) resetCallbacks() else rollbackCallbacks(_currentLevel)
    detach()
    fireAfterCompletion(handlers, exec, s)
    s
  }

  private def attemptTopLevelComplete(): Boolean = {
    val root = _currentLevel

    if (!beforeCommitList.fire(root, this))
      return false

      // read-only transactions are easy to commit, because all of the reads
      // are already guaranteed to be consistent
    if (writeCount == 0 && !writeResourcesPresent)
      return root.statusCAS(Active, Committed)

    if (!root.statusCAS(Active, Preparing) || !acquireLocks())
      return false

    // this is our linearization point
    val cv = freshCommitVersion(_readVersion, globalVersion.get)

    // if the reads are still valid, then they were valid at the linearization
    // point
    if (!revalidateImpl())
      return false

    if (!whilePreparingList.fire(root, this))
      return false

    if (externalDecider != null) {
      // external decider doesn't have to content with cancel by other threads
      if (!root.statusCAS(Preparing, Prepared) || !consultExternalDecider())
        return false

      root.status = Committing
    } else {
      // attempt to decide commit
      if (!root.statusCAS(Preparing, Committing))
        return false
    }

    commitWrites(cv)
    // TODO: handle exceptions and don't recheck status for: whileCommittingList.fire(root, this)
    root.status = Committed

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
    slotManager.release(_slot)
    _currentLevel = null
    _barging = false
    resetAccessHistory()
  }

  private def fireAfterCompletion(handlers: Seq[Status => Unit], exec: TxnExecutor, s: Status) {
    if (!handlers.isEmpty) {
      val inOrder = if (s eq Committed) handlers else handlers.view.reverse
      var failure: Option[Throwable] = None
      for (h <- inOrder)
        failure = fireAfter(h, exec, s) orElse failure
      for (f <- failure)
        throw f
    }
  }

  private def fireAfter(handler: Status => Unit, exec: TxnExecutor, s: Status): Option[Throwable] = {
    try {
      handler(s)
      None
    } catch {
      case x => {
        try {
          exec.postDecisionFailureHandler(s, x)
          None
        } catch {
          case xx => Some(xx)
        }
      }
    }
  }

  private def accumulateRetrySet() {
    if (_retrySet == null)
      _retrySet = new ReadSetBuilder
    accumulateAccessHistoryRetrySet(_retrySet)
  }

  private def takeRetrySet(): ReadSetBuilder = {
    val z = _retrySet
    _retrySet = null
    z
  }

  private def acquireLocks(): Boolean = {
    var i = writeCount - 1
    while (i >= 0) {
      if (!acquireLock(getWriteHandle(i)))
        return false
      i -= 1
    }
    return this.status eq Preparing
  }

  private def acquireLock(handle: Handle[_]): Boolean = {
    var m = handle.meta
    if (!changing(m)) {
      // remote requestRollback might have doomed us, followed by a steal
      // of this handle, so we must verify ownership each try
      while (owner(m) == _slot) {
        if (handle.metaCAS(m, withChanging(m)))
          return true
        m = handle.meta
      }
      return false
    }
    return true
  }

  private def commitWrites(cv: Long) {
    var wakeups = 0L
    var i = writeCount - 1
    while (i >= 0) {
      val handle = getWriteHandle(i).asInstanceOf[Handle[Any]]

      // update the value
      handle.data = getWriteSpecValue[Any](i)

      // note that we accumulate wakeup entries for each base and offset, even
      // if they share metadata
      val m = handle.meta
      if (pendingWakeups(m))
        wakeups |= wakeupManager.prepareToTrigger(handle.base, handle.offset)

      assert(owner(m) == _slot)

      // release the lock, clear the PW bit, and update the version, but only
      // if this was the entry that actually acquired ownership
      if (wasWriteFreshOwner(i))
        handle.meta = withCommit(m, cv)

      // because we release when we find the original owner, it is important
      // that we traverse in reverse order.  There are no duplicates
      i -= 1
    }

    // unblock anybody waiting on a value change that has just occurred
    if (wakeups != 0L)
      wakeupManager.trigger(wakeups)
  }

  //////////////// status checks

  override protected def requireActive() {
    // TODO: optimize to remove a layer of indirection?
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
      if (owner(m) != UnownedSlot)
        weakAwaitUnowned(handle, m)
      else if (handle.metaCAS(m, withOwner(m, _slot)))
        return m
      m = handle.meta
    }).asInstanceOf[Nothing]
  }

  private def tryAcquireOwnership(handle: Handle[_], m0: Meta): Boolean = {
    owner(m0) == UnownedSlot && handle.metaCAS(m0, withOwner(m0, _slot))
  }

  //////////////// barrier implementations
  
  def get[T](handle: Handle[T]): T = {
    if (_barging)
      return readForWrite(handle)

    requireActive()

    var m1 = handle.meta
    if (owner(m1) == _slot) {
      // Self-owned.  This particular base+offset might not be in the write
      // buffer, but it's definitely not in anybody else's.
      return stableGet(handle)
    }

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

  def getWith[T,Z](handle: Handle[T], f: T => Z): Z = {
    if (_barging)
      return f(readForWrite(handle))

    val u = unrecordedRead(handle)
    val result = f(u.value)
    if (!u.recorded) {
      val callback = new Function0[Boolean] {
        var _latestRead = u

        def apply(): Boolean = {
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
    if (_barging)
      return readForWrite(handle)

    val u = unrecordedRead(handle)
    val snapshot = u.value
    if (!u.recorded) {
      val callback = new Function0[Boolean] {
        var _latestRead = u

        def apply(): Boolean = {
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

  private def freshOwner(mPrev: Meta) = owner(mPrev) == UnownedSlot

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

  def readForWrite[T](handle: Handle[T]): T = {
    requireActive()
    val mPrev = acquireOwnership(handle)
    val f = freshOwner(mPrev)
    val v = allocatingGet(handle, f)
    if (f)
      revalidateIfRequired(version(mPrev))
    v
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

  def tryTransform[T](handle: Handle[T], f: T => T): Boolean = {
    requireActive()

    val m0 = handle.meta
    if (owner(m0) == _slot) {
      getAndTransform(handle, false, f)
      return true
    }

    if (!tryAcquireOwnership(handle, m0))
      return false
    getAndTransform(handle, true, f)
    revalidateIfRequired(version(m0))
    return true
  }

  def transformIfDefined[T](handle: Handle[T], pf: PartialFunction[T,T]): Boolean = {
    val u = unrecordedRead(handle)
    if (!pf.isDefinedAt(u.value)) {
      // make sure it stays undefined
      if (!u.recorded) {        
        val callback = new Function0[Boolean] {
          var _latestRead = u

          def apply(): Boolean = {
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
      val v = readForWrite(handle)
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

}
