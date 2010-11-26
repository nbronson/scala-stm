/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm
package ccstm


import java.util.concurrent.atomic.AtomicReferenceArray


/** This class manages a mapping from active transaction to a bounded integral
 *  range, so that transaction identities to be packed into some of the bits of
 *  an integral value.
 *
 *  @author Nathan Bronson
 */
private[ccstm] final class TxnSlotManager[T <: AnyRef](range: Int, reservedSlots: Int) {

  assert(range >= 16 & (range & (range - 1)) == 0)
  assert(range >= reservedSlots + 16)

  private case class SlotLock(txn: T, refCount: Int)

  
  private def nextSlot(tries: Int) = {
    var s = 0
    do {
      s = ((skel.FastSimpleRandom.nextInt << 4) | ((-tries >> 1) & 0xf)) & (range - 1)
    } while (s < reservedSlots)
    s
  }

  /** CAS on the entries manages the actual acquisition.  Entries are either
   *  transactions, or SlotLock objects.
   */
  private val slots = new AtomicReferenceArray[AnyRef](range)

  def assign(txn: T, preferredSlot: Int): Int = {
    var s = preferredSlot & (range - 1)
    if (s < reservedSlots) s = nextSlot(0)
    
    var tries = 0
    while ((slots.get(s) ne null) || !slots.compareAndSet(s, null, txn)) {
      s = nextSlot(tries)
      tries += 1
      if (tries > 100) Thread.`yield`
    }
    s
  }

  /** Returns the slot associated with `slot` at some instant.  The
   *  returned value may be obsolete before this method returns.
   */
  def lookup(slot:Int): T = unwrap(slots.get(slot))

  private def unwrap(e: AnyRef): T = {
    e match {
      case SlotLock(txn, _) => txn
      case txn => txn.asInstanceOf[T]
    }
  }

  /** A non-racy version of `lookup`, that must be paired with
   *  `endLookup`.
   */
  def beginLookup(slot: Int): T = {
    var e: AnyRef = null
    do {
      e = slots.get(slot)
    } while (null != e && !slots.compareAndSet(slot, e, locked(e)))
    unwrap(e)
  }

  private def locked(e: AnyRef): AnyRef = {
    e match {
      case SlotLock(txn, rc) => SlotLock(txn, rc + 1)
      case txn => SlotLock(txn.asInstanceOf[T], 1)
    }
  }

  def endLookup(slot: Int, observed: T) {
    if (null != observed) release(slot)
  }

  def release(slot: Int) {
    var e: AnyRef = null
    do {
      e = slots.get(slot)
    } while (!slots.compareAndSet(slot, e, unlocked(e)))
  }

  private def unlocked(e: AnyRef): AnyRef = {
    e match {
      case SlotLock(txn, 1) => txn
      case SlotLock(txn, rc) => SlotLock(txn, rc - 1)
      case txn => null
    }
  }

  def assertAllReleased() {
    for (i <- 0 until range) {
      val e = slots.get(i)
      if (null != e) {
        assert(false, i + " -> " + e)
      }
    }
  }
}
