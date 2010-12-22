/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// THashMap3

package scala.concurrent.stm
package experimental
package impl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import annotation.tailrec
import scala.collection.Iterator
import scala.concurrent.stm.impl.{StripedIntRef, NonTxn, TAnyRef}
import java.lang.ref.WeakReference


object THashMap3 {
  private type NullEncoded = AnyRef

  private val rcUpdater = (new Predicate(null)).newUpdater

  private class Predicate(ev0: NullEncoded) extends TAnyRef[NullEncoded](ev0) {

    private class PendingExit[A](mapRef: WeakReference[THashMap3[A,_]], key: A) extends (Txn => Unit) {
      def apply(txn: Txn) {
        val map = mapRef.get
        if (map != null)
          exit(map, key, count(txn))
      }

      def count(txn: Txn) = 1
    }

    private class PendingExit21[A](mapRef0: WeakReference[THashMap3[A,_]], key0: A) extends PendingExit(mapRef0, key0) {
      override def count(txn: Txn) = if (txn.status eq Txn.Committed) 2 else 1
    }

    def newUpdater = AtomicIntegerFieldUpdater.newUpdater(classOf[Predicate], "refCount")

    @volatile var refCount = 1
    def refCountCAS(before: Int, after: Int) = rcUpdater.compareAndSet(this, before, after)

    @tailrec final def tryEnter: Boolean = {
      val rc0 = refCount
      rc0 > 0 && (refCountCAS(rc0, rc0 + 1) || tryEnter)
    }

    def exit[A](map: THashMap3[A,_], key: A) { exit(map, key, 1) }

    @tailrec final def exit[A](map: THashMap3[A,_], key: A, count: Int) {
      val rc0 = refCount
      if (refCountCAS(rc0, rc0 - count)) {
        if (rc0 - count == 0)
          map.predicates.remove(key, this)
      } else {
        exit(map, key, count)
      }
    }

    @tailrec final def exitFull {
      val rc0 = refCount
      if (!refCountCAS(rc0, rc0 - 1))
        exitFull
    }

    def nonTxnGet[A]: NullEncoded = {
      // No reference count needed.  If the predicate is stale (rc==0) then we
      // linearize at either the predicate map read (if it was stale then) or
      // at the time at which it became stale.
      NonTxn.get(this)
    }

    def nonTxnPut[A](map: THashMap3[A,_], key: A, ev: NullEncoded): NullEncoded = {
      val d0 = data
      if (d0 eq ev) {
        // If d0 is stable, then this put is both a no-op and no reference
        // count adjustment is necessary.
        val d = NonTxn.get(this)
        if (NullValue.equal(ev, d))
          return d
      }

      // We can also perform a write from non-empty to non-empty without
      // adjusting the reference count or the map size.  We know that the
      // underlying Handle holds references, so even if B is a boxed primitive
      // the CASI is sufficient for success.
      if (null != d0 && NonTxn.compareAndSetIdentity(this, d0, ev)) {
        // success
        return d0
      }

      if (!tryEnter) {
        // predicate is stale, remove and retry
        map.predicates.remove(key, this)
        return map.nonTxnPut(key, ev)
      }

      val z = NonTxn.transform2(this, map._size.aStripe, (v: AnyRef, s: Int) => {
        (ev, (if (v == null) s + 1 else s), v)
      })
      if (z != null)
        exitFull
      return z
    }

    def nonTxnRemove[A](map: THashMap3[A,_], key: A): NullEncoded = {
      if (data == null && NonTxn.get(this) == null) {
        // no change required
        return null
      }

      // either there is already a positive refCount or this is a no-op
      val z = NonTxn.transform2(this, map._size.aStripe, (v: AnyRef, s: Int) => {
        (null, (if (v == null) s else s - 1), v)
      })
      if (z != null)
        exit(map, key)
      return z
    }
    

    def txnGet[A](map: THashMap3[A,_], key: A, fresh: Boolean)(implicit txn: Txn): NullEncoded = {
      val z = try {
        txn.get(this)
      } catch {
        case x => {
          if (fresh)
            exit(map, key)
          throw x
        }
      }

      if (z == null) {
        // retry if this predicate is stale
        if (!fresh && !tryEnter) {
          map.predicates.remove(key, this)
          return map.txnGet(key)
        }

        // If we delay cleanup then there is a chance we will reuse the
        // predicate.  If we perform the cleanup on another thread then there
        // may be a latency benefit for this thread, so long as the cache
        // misses are not problematic.  There is also likely to be an advantage
        // to performing multiple cleanups at once, since we can acquire the
        // predicate map's segment lock more quickly if it is already in our
        // cache in exclusive mode.
        //
        // There are several ways to arrange for cleanup to occur after the
        // transaction has completed.  All of them involve enqueuing the map
        // and key.  One possibility is to also record the txn, and have the
        // cleanup operation just skip entries whose txn is still active.  One
        // possibility is to use an after-completion handler to enqueue the
        // map and key after completion.  A third way is to use a WeakReference
        // to the transaction to enqueue the keys.  This seems problematic,
        // since a pinned txn can pin predicates indefinitely.
        //
        // There is also the question of whether to perform cleanup work during
        // map invocations (either any map, or the specific map in use), or if
        // a daemon thread should be used.

        txn.afterCompletion(new PendingExit[A](new WeakReference(map), key))
      }
      else if (fresh) {
        // The predicate is pre-entered.  We must exit, but it's cheaper to
        // exit now instead of creating the callback.
        exit(map, key)
      }

      return z
    }

    def txnPut[A](map: THashMap3[A,_], key: A, ev: NullEncoded, fresh: Boolean)(implicit txn: Txn): NullEncoded = {
      // present | fresh || tryEnter | immediate exit | commit exit | rollback exit
      // --------+-------++----------+----------------+-------------+---------------
      //   yes   |  yes  ||   no     |       1        |      0      |       0
      //    no   |  yes  ||   no     |       0        |      0      |       1
      //   yes   |   no  ||   no     |       0        |      0      |       0
      //    no   |   no  ||   yes    |       0        |      0      |       1

      val z = try {
        txn.get(this)
      } catch {
        case x => {
          if (fresh)
            exit(map, key)
          throw x
        }
      }

      if (z == null) {
        // we're inserting

        if (!fresh && !tryEnter) {
          map.predicates.remove(key, this)
          return map.txnPut(key, ev)
        }

        // retain entry as bonus, unless we roll back
        txn.afterRollback(new PendingExit[A](new WeakReference(map), key))

        map._size += 1
      }
      else if (fresh) {
        // present -> present transition can rely on the bonus, exit
        // the fresh entry immediately
        exit(map, key)
      }

      txn.set(this, ev)

      return z
    }

    def txnRemove[A](map: THashMap3[A,_], key: A, fresh: Boolean)(implicit txn: Txn): NullEncoded = {
      // present | fresh || tryEnter | immediate exit | commit exit | rollback exit
      // --------+-------++----------+----------------+-------------+---------------
      //   yes   |  yes  ||   no     |       0        |      2      |       1
      //    no   |  yes  ||   no     |       0        |      1      |       1
      //   yes   |   no  ||   no     |       0        |      1      |       0
      //    no   |   no  ||   yes    |       0        |      1      |       1

      val z = try {
        txn.get(this)
      } catch {
        case x => {
          if (fresh)
            exit(map, key)
          throw x
        }
      }

      if (z == null) {
        // retry if this predicate is stale
        if (!fresh && !tryEnter) {
          map.predicates.remove(key, this)
          return map.txnRemove(key)
        }

        txn.afterCompletion(new PendingExit[A](new WeakReference(map), key))
      }
      else {
        if (fresh) {
          // exit 2 on commit, 1 on rollback
          txn.afterCompletion(new PendingExit21[A](new WeakReference(map), key))
        } else {
          // we must remove the bonus, but we don't need to enter, so do nothing on rollback
          txn.afterCommit(new PendingExit[A](new WeakReference(map), key))
        }

        map._size += 1
        
        txn.set(this, null)
      }

      return z
    }
  }
}

class THashMap3[A,B] extends TMap2[A,B] {
  import THashMap3._

  def bind(implicit txn: Txn): TMap2.View[A,B] = new TMap2.AbstractTxnView[A,B,THashMap3[A,B]](txn, this) {
    private abstract class Iter[Z] extends Iterator[Z] {

      _size()(txn) // add to read set
      private var done = false
      private var avail: Z = null.asInstanceOf[Z]
      private val underlying = predicates.entrySet.iterator
      advance()

      @tailrec private def advance() {
        if (!underlying.hasNext) {
          // EOI
          done = true
          avail = null.asInstanceOf[Z]
        } else {
          val e = underlying.next()
          txn.get(e.getValue) match {
            case null => advance() // empty predicate, keep looking
            case ev => avail = compute(e.getKey, ev)
          }
        }
      }

      def hasNext: Boolean = !done

      def next(): Z = {
        if (done) throw new NoSuchElementException
        val z = avail
        advance()
        z
      }

      protected def compute(k: A, ev: NullEncoded): Z
    }

    def iterator: Iterator[(A,B)] = new Iter[(A,B)] {
      protected def compute(k: A, ev: AnyRef): (A,B) = (k, NullValue.decode[B](ev))
    }

    override def keysIterator: Iterator[A] = new Iter[A] {
      protected def compute(k: A, ev: AnyRef): A = k
    }

    override def valuesIterator: Iterator[B] = new Iter[B] {
      protected def compute(k: A, ev: AnyRef): B = NullValue.decode[B](ev)
    }
  }

  val escaped: TMap2.View[A,B] = new TMap2.AbstractEscapedView[A,B,THashMap3[A,B]](this) {
    private abstract class Iter[Z] extends Iterator[Z] {
      private var done = false
      private var avail: Z = null.asInstanceOf[Z]
      private val underlying = predicates.entrySet.iterator
      advance()

      @tailrec private def advance() {
        if (!underlying.hasNext) {
          // EOI
          done = true
          avail = null.asInstanceOf[Z]
        } else {
          val e = underlying.next()
          e.getValue.nonTxnGet match {
            case null => advance() // empty predicate, keep looking
            case ev => avail = compute(e.getKey, ev)
          }
        }
      }

      def hasNext: Boolean = (avail != null)

      def next(): Z = {
        if (done) throw new NoSuchElementException
        val z = avail
        advance()
        z
      }

      protected def compute(k: A, ev: NullEncoded): Z
    }

    def get(key: A) = NullValue.decodeOption[B](nonTxnGet(key))

    override def put(key: A, value: B) = NullValue.decodeOption[B](nonTxnPut(key, NullValue.encode(value)))

    override def remove(key: A) = NullValue.decodeOption[B](nonTxnRemove(key))

    def iterator: Iterator[(A,B)] = new Iter[(A,B)] {
      def compute(k: A, ev: NullEncoded): (A,B) = (k, NullValue.decode[B](ev))
    }
    
    override def keysIterator: Iterator[A] = new Iter[A] {
      protected def compute(k: A, ev: AnyRef): A = k
    }

    override def valuesIterator: Iterator[B] = new Iter[B] {
      protected def compute(k: A, ev: AnyRef): B = NullValue.decode[B](ev)
    }
  }

  
  override val single: TMap2.View[A,B] = new TMap2.SingleView[A,B,THashMap3[A,B]](this) {
    override def get(key: A) = NullValue.decodeOption[B]({
      Txn.dynCurrentOrNull match {
        case null => nonTxnGet(key)
        case t => txnGet(key)(t)
      }
    })

    override def put(key: A, value: B) = NullValue.decodeOption[B]({
      val ev = NullValue.encode(value)
      Txn.dynCurrentOrNull match {
        case null => nonTxnPut(key, ev)
        case t => txnPut(key, ev)(t)
      }
    })

    override def remove(key: A) = NullValue.decodeOption[B]({
      Txn.dynCurrentOrNull match {
        case null => nonTxnRemove(key)
        case t => txnRemove(key)(t)
      }
    })
  }


  def isEmpty(implicit txn: Txn): Boolean = size == 0

  def size(implicit txn: Txn) = _size()
  
  def get(key: A)(implicit txn: Txn) = NullValue.decodeOption[B](txnGet(key))

  def put(key: A, value: B)(implicit txn: Txn) = NullValue.decodeOption[B](txnPut(key, NullValue.encode(value)))

  def remove(key: A)(implicit txn: Txn) = NullValue.decodeOption[B](txnRemove(key))


  /////////////////////

  private[impl] val _size = new StripedIntRef(0)

  private val predicates = new ConcurrentHashMap[A,Predicate](16, 0.75f, 4) {
    override def putIfAbsent(key: A, value: Predicate): Predicate = {
      STM.resurrect(key.hashCode, value)
      super.putIfAbsent(key, value)
    }

    override def remove(key: Any, value: Any): Boolean = {
      STM.embalm(key.hashCode, value.asInstanceOf[Predicate])
      super.remove(key, value)
    }
  }

  private def nonTxnGet(key: A): NullEncoded = predicates.get(key) match {
    case null => null
    case p => p.nonTxnGet
  }

  private def nonTxnPut(key: A, ev: NullEncoded): NullEncoded = {
    var p = predicates.get(key)
    if (null == p) {
      val fresh = new Predicate(ev)
      p = predicates.putIfAbsent(key, fresh)
      if (null == p) return null
    }
    return p.nonTxnPut(this, key, ev)
  }

  private def nonTxnRemove(key: A): NullEncoded = predicates.get(key) match {
    case null => null
    case p => p.nonTxnRemove(this, key)
  }

  private def txnGet(key: A)(implicit txn: Txn): NullEncoded = {
    var fresh = false
    var p = predicates.get(key)
    if (null == p) {
      val n = new Predicate(null)
      p = predicates.putIfAbsent(key, n)
      if (null == p) {
        fresh = true
        p = n
      }
    }
    p.txnGet(this, key, fresh)
  }

  private def txnPut(key: A, ev: NullEncoded)(implicit txn: Txn): NullEncoded = {
    var fresh = false
    var p = predicates.get(key)
    if (null == p) {
      val n = new Predicate(null)
      p = predicates.putIfAbsent(key, n)
      if (null == p) {
        fresh = true
        p = n
      }
    }
    p.txnPut(this, key, ev, fresh)
  }

  private def txnRemove(key: A)(implicit txn: Txn): NullEncoded = {
    var fresh = false
    var p = predicates.get(key)
    if (null == p) {
      val n = new Predicate(null)
      p = predicates.putIfAbsent(key, n)
      if (null == p) {
        fresh = true
        p = n
      }
    }
    p.txnRemove(this, key, fresh)
  }
}
