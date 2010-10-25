/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm.ccstm

import annotation.tailrec

/** A read set representation. */
private[ccstm] class ReadSet(size: Int,
                             handles: Array[Handle[_]],
                             versions: Array[CCSTM.Version]) {
  import CCSTM._

  def awaitRetry() {
    // Spin a few times, counting one spin per read set element
    var spins = 0
    while (spins < SpinCount + YieldCount) {
      if (!stillValid)
        return
      spins += size
      if (spins == 0)
        throw new IllegalStateException("explicit retries cannot succeed because cumulative read set is empty")
      if (spins > SpinCount)
        Thread.`yield`
    }

    while (true) {
      val event = wakeupManager.subscribe
      var i = size - 1
      while (i >= 0) {
        val h = handles(i)
        if (!event.addSource(h.ref, h.offset))
          return // event was already triggered  TODO: recheck stillValid?
        if (!addPendingWakeup(h, versions(i)))
          return // handle has already changed
        i -= 1
      }
      event.await
    }
  }

  @tailrec private def addPendingWakeup(handle: Handle[_], ver: CCSTM.Version): Boolean = {
    val m = handle.meta
    if (changing(m) || version(m) != ver)
      false // handle has already changed
    else if (pendingWakeups(m) || handle.metaCAS(m, withPendingWakeups(m)))
      true // already has pending wakeup, or CAS to add it was successful
    else
      addPendingWakeup(handle, ver) // try again
  }

  def stillValid: Boolean = {
    var i = size - 1
    while (i >= 0) {
      val m = handles(i).meta
      if (changing(m) || version(m) != versions(i))
        return false
      i -= 1
    }
    return true
  }

}
