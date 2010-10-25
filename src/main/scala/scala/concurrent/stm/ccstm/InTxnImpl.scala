/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ccstm

import annotation.tailrec

private[ccstm] object InTxnImpl extends ThreadLocal[InTxnImpl] {

  override def initialValue = new InTxnImpl

  def apply()(implicit mt: MaybeTxn): InTxnImpl = mt match {
    case x: InTxnImpl => x
    case _ => get // this will create one
  }

  private def active(txn: InTxnImpl): InTxnImpl = if (txn.currentLevel != null) txn else null

  def dynCurrentOrNull: InTxnImpl = active(get)

  def currentOrNull(implicit mt: MaybeTxn) = active(apply())
}

private[ccstm] class InTxnImpl extends AccessHistory[TxnLevelImpl] with skel.AbstractInTxn {
  import CCSTM._
  import Txn._
  import skel.RollbackError

  //////////////// pre-transaction state

  private var _alternatives: List[InTxn => Any] = Nil

  def pushAlternative(block: InTxn => Any) { _alternatives ::= block }
  def takeAlternatives(): List[InTxn => Any] = { val z = _alternatives ; _alternatives = Nil ; z }

  //////////////// per-transaction state

  private var _executor: TxnExecutor = null
  private var _barging: Boolean = false
  private var _slot: Slot = 0
  private var _rootLevel: TxnLevelImpl = null
  private var _currentLevel: TxnLevelImpl = null

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
  private[impl] var _readVersion: Version = freshReadVersion

  override def toString = {
    ("InTxnImpl@" + hashCode.toHexString + "(" + status +
            ", slot=" + _slot +
            ", priority=" + priority +
            ", readSetSize=" + (if (null == _readSet) "discarded" else _readSet.size.toString) +
            ", writeBuffer.size=" + (if (null == _writeBuffer) "discarded" else _writeBuffer.size.toString) +
            ", retrySet.size=" + (if (null == _retrySet) "N/A" else _retrySet.size.toString) +
            ", readVersion=0x" + _readVersion.toHexString +
            (if (barging) ", barging" else "") + ")")
  }

  //////////////// High-level behaviors

  def status: Status = _currentLevel.status
  def executor: TxnExecutor = _executor
  def undoLog: TxnLevelImpl = _currentLevel
  override def currentLevel: TxnLevelImpl = _currentLevel

  /** There are N ways that a single attempt can complete:
   *  - block returns normally, level commits;
   *  - block returns normally, level rolls back due to handler exception;
   *  - block throws a control flow exception, level commits;
   *  - block throws a control flow  */

  /** Throws `RollbackError` on failure that should be retried. */
  def attempt[Z](exec: TxnExecutor, barge: Boolean, level: TxnLevelImpl, block: InTxn => Z): Z = {
    begin(exec, barge, level)
    try {
      try {
        block(txn)
      } catch {
        case x if x != RollbackError && !executor.isControlFlow(x) => {
          level.forceRollback(Txn.UncaughtExceptionCause(x))
          throw RollbackError
        }
      }
    } finally {
      if (complete() != Txn.Committed)
        throw RollbackError
    }
  }

  def atomic[Z](exec: TxnExecutor, block: InTxn => Z): Z = {
    (while (true) {
      val level = new TxnLevelImpl(this, _currentLevel)
      try {
        return attempt(exec, barge, level, block)
      } catch {
        case RollbackError =>
      }
      level.status.asInstanceOf[RolledBack].cause match {
        case UncaughtExceptionCause(x) => throw x
        case ExplicitRetryCause => {
          if (_currentLevel != null) {
            // nested retry with no alternatives propagates to the parent txn
            _currentLevel.forceRollback(ExplicitRetryCause)
            throw RollbackError
          } else {
            // top-level retry
          }
        }
      }
    }).asInstanceOf[Nothing]
  }

//  {{
//      // success, return value is either the result or a control transfer
//      if (nonLocalReturn != null)
//        throw nonLocalReturn
//      result
//    } else {
//      // rollback, throw an exception if there is no retry
//      s.asInstanceOf[Txn.RolledBack].cause match {
//        case Txn.UncaughtExceptionCause(x) => throw x
//        case Txn.ExplicitRetryCause => {
//          // if a nested transaction does a retry with no alternatives, then
//          // it is equivalent to retrying the outer txn
//          if (currentLevel != null)
//            currentLevel.requestRollback(Txn.ExplicitRetryCause)
//          _currentLevel.checkAccess()
//          takeRetrySet().awaitRetry()
//          atomic(exec, block, 0)
//        }
//        case Txn.OptimisticFailureCause(_) => {
//          _currentLevel.checkAccess()
//          atomic(exec, block, 1 + consecutiveFailures)
//        }
//      }
//    }
//
//  }

  @tailrec final def atomic[Z](exec: TxnExecutor, block: InTxn => Z, consecutiveFailures: Int): Either[Z,ReadSetBuilder] = {
    begin(exec, consecutiveFailures > 2)
    var nonLocalReturn: Throwable = null
    var result: Z = null.asInstanceOf[Z]
    try {
      result = block(txn)
    } catch {
      case RollbackError =>
      case x if executor.isControlFlow(x) => nonLocalReturn = x
      case x => currentLevel.requestRollback(Txn.UncaughtExceptionCause(x))
    }
    val s = complete()
    if (s == Txn.Committed) {
      // success, return value is either the result or a control transfer
      if (nonLocalReturn != null)
        throw nonLocalReturn
      result
    } else {
      // rollback, throw an exception if there is no retry
      s.asInstanceOf[Txn.RolledBack].cause match {
        case Txn.UncaughtExceptionCause(x) => throw x
        case Txn.ExplicitRetryCause => {
          // if a nested transaction does a retry with no alternatives, then
          // it is equivalent to retrying the outer txn
          if (currentLevel != null)
            currentLevel.requestRollback(Txn.ExplicitRetryCause)
          _currentLevel.checkAccess()
          takeRetrySet().awaitRetry()
          atomic(exec, block, 0)
        }
        case Txn.OptimisticFailureCause(_) => {
          _currentLevel.checkAccess()
          atomic(exec, block, 1 + consecutiveFailures)
        }
      }
    }
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
          readLocate(i).requestRollback(Txn.OptimisticFailureCause(problem, Some(h)))
          return false
        }
      }
      i += 1
    }
    fireWhileValidating()
    
    _currentLevel.localStatus eq Txn.Active
  }

  /** Returns the name of the problem on failure, null on success. */ 
  private def checkRead(handle: Handle[_], ver: CCSTM.Version, index: Int): Symbol = {
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
            if (s.mightCommit)
              return 'pending_commit

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
  private[impl] def resolveWriteWriteConflict(owningRoot: TxnLevelImpl, contended: AnyRef) {
    // if write is not allowed, throw an exception of some sort
    checkAccess()

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
      _currentLevel.requestRollback(Txn.OptimisticFailureCause(msg, Some(contended)))
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

  def begin(exec: TxnExecutor, barge: Boolean) {
    if (_currentLevel == null)
      topLevelBegin(exec, barge)
    else
      nestedBegin()
  }

  private def nestedBegin() {
    val child = new TxnLevelImpl(this, _currentLevel)

    // link to child races with remote rollback
    if (!_currentLevel.pushIfActive(child))
      throw RollbackError

    // success
    _currentLevel = child
    checkpointCallbacks()
    checkpointAccessHistory()
  }

  private def topLevelBegin(exec: TxnExecutor, barge: Boolean) {
    _executor = exec 
    _barging = barge
    _currentLevel = new TxnLevelImpl(this, null)
    _priority = CCSTM.hash(_currentLevel, 0)
    // TODO: advance to a new slot in a fixed-cycle way to reduce steals from non-owners
    _slot = slotManager.assign(_currentLevel, _slot)
  }

  def complete(): Txn.Status = {
    if (_currentLevel.par == null)
      topLevelComplete()
    else
      nestedComplete()
  }

  /** This gives the current level's status during a commit. */
  private def commitStatus: Status = _currentLevel.localStatus.asInstanceOf[Status]

  private def nestedComplete(): Txn.Status = {
    val child = _currentLevel
    val result = (if (child.attemptMerge()) {
      // child was successfully merged
      mergeAccessHistory()

      Txn.Active
    } else {
      val s = commitStatus

      // we must accumulate the retry set before rolling back the access history
      if (s.asInstanceOf[Txn.RolledBack].cause == ExplicitRetryCause)
        accumulateRetrySet()

      // release the locks before the callbacks
      rollbackAccessHistory(_slot)
      val handlers = rollbackCallbacks()
      fireAfterRollback(handlers, s)
      s
    })

    // unlinking the child must be performed regardless of success or failure
    _currentLevel = child.par

    result
  }

  private def fireAfterRollback(handlers: Seq[Status => Unit], s: Status) {
    var i = handlers.size - 1
    while (i >= 0) {
      try {
        handlers(i)(s)
      } catch {
        case x => {
          try {
            executor.postDecisionFailureHandler(s, x)
          } catch {
            case xx => xx.printStackTrace()
          }
        }
      }
      i -= 1
    }
  }

  private[ccstm] def topLevelComplete(): Status = {
    try {
      val s = commitStatus
      if (s.isInstanceOf[RolledBack] || !callBefore())
        return completeRollback()

      if (writeCount == 0 && !writeResourcesPresent) {
        // read-only transactions are easy to commit, because all of the reads
        // are already guaranteed to be consistent
        if (!_currentLevel.statusCAS(Active, Committed))
          return completeRollback()
        return Committed
      }

      if (!_currentLevel.statusCAS(Active, Preparing) || !acquireLocks())
        return completeRollback()

      // this is our linearization point
      val cv = freshCommitVersion(_readVersion, globalVersion.get)

      // if the reads are still valid, then they were valid at the linearization
      // point
      if (!revalidateImpl())
        return completeRollback()

      if (!whilePreparingList.fire(_currentLevel, this))
        return completeRollback()

      if (externalDecider != null) {
        // external decider doesn't have to content with cancel by other threads
        if (!_currentLevel.statusCAS(Preparing, Prepared) || !consultExternalDecider())
          return completeRollback()

        assert(commitStatus == Preparing)
        _currentLevel.localStatus = Committing
      } else {
        // attempt to decide commit
        if (!_currentLevel.statusCAS(Preparing, Committing))
          return completeRollback()
      }

      commitWrites(cv)
      whileCommittingList.fire(_currentLevel, this)
      _currentLevel.localStatus = Committed

      return Committed
      
    } finally {
      val s = commitStatus
      val cur = _currentLevel

      // detach the level before the after-commit callbacks, so that they
      // can run a new transaction
      slotManager.release(_slot)
      _currentLevel = null
      _executor = null
      resetAccessHistory()

      if (s eq Committed)
        afterCommitList.fire(this, s)
      s
    }
  }

  private def completeRollback(): Status = {
    assert(_currentLevel.par == null)

    val s = commitStatus

    // we must accumulate the retry set before rolling back the access history
    if (s.asInstanceOf[Txn.RolledBack].cause == ExplicitRetryCause)
      accumulateRetrySet()

    rollbackAccessHistory(_slot)
    val handlers = rollbackCallbacks()
    fireAfterRollback(handlers, s)
    s
  }

  private def acquireLocks(): Boolean = {
    var wakeups = 0L
    var i = writeCount - 1
    while (i >= 0) {
      if (!acquireLock(getWriteHandle(i)))
        return false
      i -= 1
    }
    return commitStatus == Preparing
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

      // note that we accumulate wakeup entries for each ref and offset, even
      // if they share metadata
      val m = handle.meta
      if (pendingWakeups(m))
        wakeups |= wakeupManager.prepareToTrigger(handle.ref, handle.offset)

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

  private def checkAccess() { requireActive() }

  override protected def requireActive() {
    // TODO: optimize to remove a layer of indirection?
    val cur = _currentLevel
    if (cur == null)
      throw new IllegalStateException("no active transaction")
    cur.requireActive()
  }

  //////////////// lock management - similar to NonTxn but we also check for remote rollback

  private def weakAwaitUnowned(handle: Handle[_], m0: Meta) {
    CCSTM.weakAwaitUnowned(handle, m0, this)
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
    if (barging) return readForWrite(handle)

    checkAccess()

    var m1 = handle.meta
    if (owner(m1) == _slot) {
      // Self-owned.  This particular ref+offset might not be in the write
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
    if (barging)
      return f(readForWrite(handle))

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

          var m1 = handle.meta
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

  def unrecordedRead[T](handle: Handle[T]): UnrecordedRead[T] = {
    checkUnrecordedRead()

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
          if (_status eq InTxnImpl.Preparing) {
            // can't wait
            forceRollbackLocal(InTxnImpl.InvalidReadCause(handle, "contended unrecordedRead while validating"))
            throw RollbackError
          }
          weakAwaitUnowned(handle, m0)
          m0 = handle.meta
        }
        if (version(m0) > _readVersion) {
          if (_status eq InTxnImpl.Preparing) {
            // can't wait
            forceRollbackLocal(InTxnImpl.InvalidReadCause(handle, "unrecordedRead of future value while validating"))
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
    checkAccess()
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
    // If not f, then this was already self-owned.  This particular ref+offset
    // might not be in the write buffer, but it's definitely not in anybody
    // else's.
    if (f)
      revalidateIfRequired(version(mPrev))
  }
  
  def swap[T](handle: Handle[T], v: T): T = {
    checkAccess()
    val mPrev = acquireOwnership(handle)
    val f = freshOwner(mPrev)
    val v0 = swap(handle, f, v)
    if (f)
      revalidateIfRequired(version(mPrev))
    v0
  }

  def trySet[T](handle: Handle[T], v: T): Boolean = {
    checkAccess()

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
    checkAccess()
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
    checkAccess()
    val mPrev = acquireOwnership(handle)
    val f = freshOwner(mPrev)
    val v0 = getAndTransform(handle, f, func)
    if (f)
      revalidateIfRequired(version(mPrev))
    v0
  }

  def tryTransform[T](handle: Handle[T], f: T => T): Boolean = {
    checkAccess()

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
