/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ccstm

import annotation.tailrec


/** The object that contains the code for non-transactional read and write
 *  barriers.
 */
private[ccstm] object NonTxn {
  import CCSTM._

  //////////////// lock waiting

  private def weakAwaitUnowned(handle: Handle[_], m0: Meta) {
    CCSTM.weakAwaitUnowned(handle, m0, null)
  }

  //////////////// value waiting

  private def weakAwaitNewVersion(handle: Handle[_], m0: Meta) {
    // spin a bit
    var m = 0L
    var spins = 0
    do {
      val m = handle.meta
      if (version(m) != version(m0)) return

      spins += 1
      if (spins > SpinCount) Thread.`yield`
    } while (spins < SpinCount + YieldCount)

    if (changing(m)) {
      weakAwaitUnowned(handle, m)
    } else {
      weakNoSpinAwaitNewVersion(handle, m)
    }
  }

  private def weakNoSpinAwaitNewVersion(handle: Handle[_], m0: Meta) {
    val event = wakeupManager.subscribe
    event.addSource(handle.base, handle.metaOffset)
    do {
      val m = handle.meta
      if (version(m) != version(m0) || changing(m)) {
        // observed new version, or likely new version (after unlock)
        return
      }

      // not changing, so okay to set PW bit
      if (pendingWakeups(m) || handle.metaCAS(m, withPendingWakeups(m))) {
        // after the block, things will have changed with reasonably high
        // likelihood (spurious wakeups are okay)
        event.await
        return
      }
    } while (!event.triggered)
  }

  //////////////// lock acquisition

  private def acquireLock(handle: Handle[_], exclusive: Boolean): Meta = {
    var m0 = 0L
    var m1 = 0L
    do {
      m0 = handle.meta
      while (owner(m0) != UnownedSlot) {
        weakAwaitUnowned(handle, m0)
        m0 = handle.meta
      }
      val mOwned = withOwner(m0, NonTxnSlot)
      m1 = if (exclusive) withChanging(mOwned) else mOwned
    } while (!handle.metaCAS(m0, m1))
    m1
  }

  /** Returns 0L on failure. */
  private def tryAcquireLock(handle: Handle[_], exclusive: Boolean): Meta = {
    val m0 = handle.meta
    if (owner(m0) != UnownedSlot) return 0L

    val mOwned = withOwner(m0, NonTxnSlot)
    val m1 = if (exclusive) withChanging(mOwned) else mOwned

    if (!handle.metaCAS(m0, m1)) return 0L

    return m1
  }

  private def upgradeLock(handle: Handle[_], m0: Meta): Meta = {
    var before = m0
    if (!handle.metaCAS(before, withChanging(before))) {
      // must have been a concurrent set of pendingWakeups
      before = withPendingWakeups(before)
      handle.meta = withChanging(before)
    }
    withChanging(before)
  }

  private def commitLock(handle: Handle[_], m0: Meta) {
    releaseLock(handle, m0, CCSTM.nonTxnWriteVersion(version(m0)))
  }

  private def discardLock(handle: Handle[_], m0: Meta) {
    releaseLock(handle, m0, version(m0))
  }

  private def releaseLock(handle: Handle[_], m0: Meta, newVersion: Version) {
    // If pendingWakeups is set, then we are not racing with any other updates.
    // If the CAS fails, then we lost a race with pendingWakeups <- true, so we
    // can just assume that it's true.
    if (pendingWakeups(m0) || !handle.metaCAS(m0, withCommit(m0, newVersion))) {
      handle.meta = withCommit(withPendingWakeups(m0), newVersion)
      val r = handle.base

      // we notify on offset for threads that are waiting for handle to change
      val o1 = handle.offset

      // we notify on metaOffset for threads that are trying to acquire a lock
      // on a handle that shares metaData with this handle
      val o2 = handle.metaOffset

      var wakeups = wakeupManager.prepareToTrigger(r, o1)
      if (o1 != o2) wakeups |= wakeupManager.prepareToTrigger(r, o2)
      wakeupManager.trigger(wakeups)
    }
  }

  //////////////// public interface

  def get[T](handle: Handle[T]): T = {
    var tries = 0
    var m0 = 0L
    while (tries < 100) {
      m0 = handle.meta
      if (changing(m0)) {
        weakAwaitUnowned(handle, m0)
      } else {
        val v = handle.data
        val m1 = handle.meta
        if (changingAndVersion(m0) == changingAndVersion(m1)) {
          return v
        }
      }
      tries += 1
    }
    return lockedGet(handle)
  }

  private def lockedGet[T](handle: Handle[T]): T = {
    val m0 = acquireLock(handle, false)
    val z = handle.data
    discardLock(handle, m0)
    z
  }

  def await[T](handle: Handle[T], pred: T => Boolean) {
    while (true) {
      val m0 = handle.meta
      if (changing(m0)) {
        weakAwaitUnowned(handle, m0)
      } else {
        val v = handle.data
        val m1 = handle.meta
        if (changingAndVersion(m0) == changingAndVersion(m1)) {
          // stable read of v
          if (pred(v)) {
            // success!
            return
          }

          // wait for a new version
          weakAwaitNewVersion(handle, m1)
        }
      }
    }
  }

  @tailrec
  def unrecordedRead[T](handle: Handle[T]): UnrecordedRead[T] = {
    val m0 = handle.meta
    if (changing(m0)) {
      weakAwaitUnowned(handle, m0)
    } else {
      val v = handle.data
      val m1 = handle.meta
      if (changingAndVersion(m0) == changingAndVersion(m1)) {
        // stable read of v
        return new UnrecordedRead[T] {
          def context = None
          val value = v
          def stillValid = changingAndVersion(handle.meta) == changingAndVersion(m1)
          def recorded = false
        }
      }
    }
    return unrecordedRead(handle)
  }

  def set[T](handle: Handle[T], v: T) {
    val m0 = acquireLock(handle, true)
    handle.data = v
    commitLock(handle, m0)
  }

  def swap[T](handle: Handle[T], v: T): T = {
    val m0 = acquireLock(handle, true)
    val z = handle.data
    handle.data = v
    commitLock(handle, m0)
    z
  }

  def trySet[T](handle: Handle[T], v: T): Boolean = {
    val m0 = tryAcquireLock(handle, true)
    if (m0 == 0L) {
      false
    } else {
      handle.data = v
      commitLock(handle, m0)
      true
    }
  }

  def compareAndSet[T](handle: Handle[T], before: T, after: T): Boolean = {
    // Try to acquire ownership.  If we can get it easily then we hold the lock
    // while evaluating before == handle.data, otherwise we try to perform an
    // invisible read to determine if the CAS will succeed, only waiting for
    // the lock if the CAS might go ahead.
    val m0 = handle.meta
    if (owner(m0) != UnownedSlot) {
      return invisibleCAS(handle, before, after)
    }
    val m1 = withOwner(m0, NonTxnSlot)
    if (!handle.metaCAS(m0, m1)) {
      return invisibleCAS(handle, before, after)
    }

    if (before == handle.data) {
      val m2 = upgradeLock(handle, m1)
      handle.data = after
      commitLock(handle, m2)
      true
    } else {
      discardLock(handle, m1)
      false
    }
  }

  private def invisibleCAS[T](handle: Handle[T], before: T, after: T): Boolean = {
    // this is the code from get, inlined so that we have access to the version
    // number as well with no boxing
    var m0 = 0L
    var m1 = 0L
    var v: T = null.asInstanceOf[T]
    do {
      m0 = handle.meta
      while (changing(m0)) {
        weakAwaitUnowned(handle, m0)
        m0 = handle.meta
      }
      v = handle.data
      m1 = handle.meta
    } while (changingAndVersion(m0) != changingAndVersion(m1))

    // invisible failure?
    if (!(before == v)) return false

    // don't go directly to changing, because we can't run user code
    // (before.equals) there
    val m2 = acquireLock(handle, false)
    if (version(m2) == version(m1) || before == handle.data) {
      val m3 = upgradeLock(handle, m2)
      handle.data = after
      commitLock(handle, m3)
      true
    } else {
      discardLock(handle, m2)
      false
    }
  }

  def compareAndSetIdentity[T, R <: AnyRef with T](handle: Handle[T], before: R, after: T): Boolean = {
    // try to acquire exclusive ownership
    val m0 = handle.meta
    if (owner(m0) != UnownedSlot) {
      return invisibleCASI(handle, before, after)
    }
    val m1 = withChanging(withOwner(m0, NonTxnSlot))
    if (!handle.metaCAS(m0, m1)) {
      return invisibleCASI(handle, before, after)
    }

    if (before eq handle.data.asInstanceOf[AnyRef]) {
      handle.data = after
      commitLock(handle, m1)
      true
    } else {
      discardLock(handle, m1)
      false
    }
  }

  private def invisibleCASI[T, R <: T with AnyRef](handle: Handle[T], before: R, after: T): Boolean = {
    if (before eq get(handle).asInstanceOf[AnyRef]) {
      // CASI is different than CAS, because we don't have to invoke user code to
      // perform the comparison
      val m0 = acquireLock(handle, true)
      if (before eq handle.data.asInstanceOf[AnyRef]) {
        handle.data = after
        commitLock(handle, m0)
        true
      } else {
        discardLock(handle, m0)
        false
      }
    } else {
      // invisible failure
      false
    }
  }

  def getAndTransform[T](handle: Handle[T], f: T => T): T = {
    getAndTransformImpl(handle, f, acquireLock(handle, false))
  }

  def tryTransform[T](handle: Handle[T], f: T => T): Boolean = {
    val m0 = tryAcquireLock(handle, false)
    if (m0 == 0L) {
      false
    } else {
      getAndTransformImpl(handle, f, m0)
      true
    }
  }

  private def getAndTransformImpl[T](handle: Handle[T], f: T => T, m0: Meta): T = {
    val v0 = handle.data
    val repl = try { f(v0) } catch { case x => discardLock(handle, m0) ; throw x }
    val m1 = upgradeLock(handle, m0)
    handle.data = repl
    commitLock(handle, m1)
    v0
  }

  def transformIfDefined[T](handle: Handle[T], pf: PartialFunction[T,T]): Boolean = {
    if (pf.isDefinedAt(get(handle))) {
      val m0 = acquireLock(handle, false)
      val v = handle.data
      if (try { pf.isDefinedAt(v) } catch { case x => discardLock(handle, m0) ; throw x }) {
        val repl = try { pf(v) } catch { case x => discardLock(handle, m0) ; throw x }
        val m1 = upgradeLock(handle, m0)
        handle.data = repl
        commitLock(handle, m1)
        true
      } else {
        discardLock(handle, m0)
        false
      }
    } else {
      // invisible failure
      false
    }
  }

  //////////////// multi-handle ops

//  def transform2[A,B,Z](handleA: Handle[A], handleB: Handle[B], f: (A,B) => (A,B,Z)): Z = {
//    var mA0: Long = 0L
//    var mB0: Long = 0L
//    var tries = 0
//    do {
//      mA0 = acquireLock(handleA, true)
//      mB0 = tryAcquireLock(handleB, true)
//      if (mB0 == 0) {
//        // tryAcquire failed
//        discardLock(handleA, mA0)
//        mA0 = 0
//
//        // did it fail because the handles are equal?
//        if (handleA == handleB) throw new IllegalArgumentException("transform2 targets must be distinct")
//
//        // try it in the opposite direction
//        mB0 = acquireLock(handleB, true)
//        mA0 = tryAcquireLock(handleA, true)
//
//        if (mA0 == 0) {
//          // tryAcquire failed
//          discardLock(handleB, mB0)
//          mB0 = 0
//
//          tries += 1
//          if (tries > 10) {
//            // fall back to a txn, which is guaranteed to eventually succeed
//            return STM.atomic((t: InTxnImpl) => {
//              val refA = new TxnView(null, handleA, t)
//              val refB = new TxnView(null, handleB, t)
//              val (a,b,z) = f(refA.readForWrite, refB.readForWrite)
//              refA() = a
//              refB() = b
//              z
//            })
//          }
//        }
//      }
//    } while (mB0 == 0)
//
//    val (a,b,z) = try {
//      f(handleA.data, handleB.data)
//    } catch {
//      case x => {
//        discardLock(handleA, mA0)
//        discardLock(handleB, mB0)
//        throw x
//      }
//    }
//
//    handleA.data = a
//    handleB.data = b
//
//    val wv = CCSTM.nonTxnWriteVersion(math.max(version(mA0), version(mB0)))
//    releaseLock(handleA, mA0, wv)
//    releaseLock(handleB, mB0, wv)
//    return z
//  }

  def getAndAdd(handle: Handle[Int], delta: Int): Int = {
    val m0 = acquireLock(handle, true)
    val v0 = handle.data
    handle.data = v0 + delta
    commitLock(handle, m0)
    v0
  }
}
