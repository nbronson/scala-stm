/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm.ccstm


import java.util.concurrent.atomic.{AtomicReferenceArray, AtomicLongArray}

private[impl] final class WakeupManager(numChannels: Int, numSources: Int) {
  import CCSTM.hash

  def this() = this(64, 512)

  assert(numChannels > 0 && numChannels <= 64 && (numChannels & (numChannels - 1)) == 0)
  assert(numSources > 0 && (numSources & (numSources - 1)) == 0)

  // To reduce false sharing.  Assume 64 byte cache lines and 4 byte pointers.
  private def ChannelSpacing = 16

  private val pending = new AtomicLongArray(numSources)
  private val events = new AtomicReferenceArray[Event](numChannels * ChannelSpacing)
  
  /** The returned value must later be passed to `trigger`.
   *  Multiple return values may be passed to a single invocation of
   *  `trigger` by merging them with bitwise-OR.
   */
  def prepareToTrigger(ref: AnyRef, offset: Int): Long = {
    val i = hash(ref, offset) & (numSources - 1)
    var z = 0L
    do {
      z = pending.get(i)
    } while (z != 0L && !pending.compareAndSet(i, z, 0L))
    z
  }

  /** Completes the wakeups started by `prepareToTrigger`.  If a
   *  thread completes `e = subscribe; e.addSource(r,o)` prior to
   *  a call to `prepareToTrigger(r,o)` call whose return value is
   *  included in `wakeups`, then any pending call to
   *  `e.await` will return now and any future calls will return
   *  immediately.
   */
  def trigger(wakeups: Long) {
    var channel = 0
    var w = wakeups
    while (w != 0) {
      val s = java.lang.Long.numberOfTrailingZeros(w) + 1
      w >>>= s
      channel += s
      trigger(channel - 1)
    }
  }

  private def trigger(channel: Int) {
    val i = channel * ChannelSpacing
    val e = events.get(i)
    if (null != e && events.compareAndSet(i, e, null)) e.trigger
  }

  /** See `trigger`. */
  def subscribe: Event = {
    // Picking the waiter's identity using the thread hash means that there is
    // a possibility that we will get repeated interference with another thread
    // in a per-VM way, but it minimizes saturation of the pending wakeups,
    // which is quite important.
    subscribe(hash(Thread.currentThread, 0) & (numChannels - 1))
  }

  private def subscribe(channel: Int): Event = {
    val i = channel * ChannelSpacing
    (while (true) {
      val existing = events.get(i)
      if (null != existing) return existing
      val fresh = new Event(channel)
      if (events.compareAndSet(i, null, fresh)) return fresh
    }).asInstanceOf[Nothing]
  }

  class Event(channel: Int) {
    private val mask = 1L << channel
    @volatile private var _triggered = false

    def triggered = _triggered

    /** Returns false if triggered. */
    def addSource(ref: AnyRef, offset: Int): Boolean = {
      if (_triggered) {
        return false
      } else {
        val i = hash(ref, offset) & (numSources - 1)
        var p = pending.get(i)
        while((p & mask) == 0 && !pending.compareAndSet(i, p, p | mask)) {
          if (_triggered) return false
          p = pending.get(i)
        }
        return true
      }
    }

    def await() { await(null) }

    def await(currentTxn: TxnLevel) {
      if (!_triggered) {
        if (null != currentTxn)
          currentTxn.checkAccess()
        synchronized {
          while (!_triggered) {
            if (null != currentTxn)
              currentTxn.checkAccess()
            wait
          }
        }
      }
    }

    private[WakeupManager] def trigger() {
      if (!_triggered) {
        synchronized {
          if (!_triggered) {
            _triggered = true
            notifyAll
          }
        }
      }
    }
  }
}
