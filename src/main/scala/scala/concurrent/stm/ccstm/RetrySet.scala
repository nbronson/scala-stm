/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm.ccstm

import annotation.tailrec

/** A retry set representation. */
private[ccstm] class RetrySet(val size: Int,
                              handles: Array[Handle[_]],
                              versions: Array[CCSTM.Version]) {
  import CCSTM._

  @throws(classOf[InterruptedException])
  def awaitRetry(prevCumulativeWait: Long, timeoutMillis: Option[Long]): Long = {
    // this should be enforced by the logic in Txn
    assert(timeoutMillis.isEmpty || timeoutMillis.get > prevCumulativeWait)

    if (size == 0 && timeoutMillis.isEmpty)
      throw new IllegalStateException("explicit retries cannot succeed because cumulative read set is empty")

    val begin = System.currentTimeMillis
    val deadline = if (timeoutMillis.isEmpty) Long.MaxValue else begin + timeoutMillis.get - prevCumulativeWait

    val timeoutExceeded = !attemptAwait(deadline)

    val actualElapsed = System.currentTimeMillis - begin

    if (Stats.top != null) {
      Stats.top.retrySet += size
      Stats.top.retryWaitElapsed += math.min(actualElapsed, Int.MaxValue).asInstanceOf[Int]
    }

    // to cause the proper retryFor to wake up we need to present an illusion
    // that we didn't block for too long
    //
    //  reportedElapsed = min(now, deadline) - begin
    //  reportedElapsed = min(now - begin, deadline - begin)
    //  newCumulativeWait = prevCumulativeWait + reportedElapsed
    prevCumulativeWait + math.min(actualElapsed, deadline - begin)
  }

  /** Returns true if something changed, false if the deadline was reached. */
  @throws(classOf[InterruptedException])
  private def attemptAwait(deadline: Long): Boolean = {
    // Spin a few times, counting one spin per read set element
    var spins = 0
    while (size > 0 && spins < SpinCount + YieldCount) {
      if (changed)
        return true
      spins += size
      if (spins > SpinCount) {
        Thread.`yield`
        if (deadline != Long.MaxValue && System.currentTimeMillis > deadline)
          return false
      }
    }

    return blockingAttemptAwait(deadline)
  }

  @throws(classOf[InterruptedException])
  @tailrec
  private def blockingAttemptAwait(deadline: Long,
                                   event: WakeupManager.Event = wakeupManager.subscribe,
                                   i: Int = size - 1): Boolean = {
    if (i < 0) {
      // event has been completed, time to block
      if (!event.tryAwait(deadline))
        false // timed out
      else
        changed || blockingAttemptAwait(deadline) // event fired
    } else {
      // still building the event
      val h = handles(i)
      if (!event.addSource(h))
        changed || blockingAttemptAwait(deadline) // event fired
      else if (!addPendingWakeup(h, versions(i)))
        true // direct evidence of change
      else
        blockingAttemptAwait(deadline, event, i - 1) // keep building
    }
  }

  @tailrec
  private def addPendingWakeup(handle: Handle[_], ver: CCSTM.Version): Boolean = {
    val m = handle.meta
    if (changing(m) || version(m) != ver)
      false // handle has already changed
    else if (pendingWakeups(m) || handle.metaCAS(m, withPendingWakeups(m)))
      true // already has pending wakeup, or CAS to add it was successful
    else
      addPendingWakeup(handle, ver) // try again
  }

  private def changed: Boolean = {
    var i = size - 1
    while (i >= 0) {
      val m = handles(i).meta
      if (changing(m) || version(m) != versions(i))
        return true
      i -= 1
    }
    return false
  }

}
