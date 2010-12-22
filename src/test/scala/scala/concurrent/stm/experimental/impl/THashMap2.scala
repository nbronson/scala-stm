/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// THashMap2

package scala.concurrent.stm
package experimental
package impl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import annotation.tailrec
import scala.collection.Iterator
import scala.concurrent.stm.impl.{StripedIntRef, NonTxn, TAnyRef}
import collection.CleanupManager


object THashMap2 {
  private type NullEncoded = AnyRef

  private trait Predicate[C] extends Ref[C] {
    def nonTxnGet[A]: NullEncoded
    def nonTxnPut[A](map: THashMap2[A,_], key: A, ev: NullEncoded): NullEncoded
    def nonTxnRemove[A](map: THashMap2[A,_], key: A): NullEncoded

    def txnGet[A](map: THashMap2[A,_], key: A, entered: Boolean)(implicit txn: Txn): NullEncoded
    def txnPut[A](map: THashMap2[A,_], key: A, ev: NullEncoded, entered: Boolean)(implicit txn: Txn): NullEncoded
    def txnRemove[A](map: THashMap2[A,_], key: A, entered: Boolean)(implicit txn: Txn): NullEncoded
  }

  private val rcUpdater = (new RCPredicate(null)).newUpdater

  private class RCPredicate(ev0: NullEncoded) extends TAnyRef[NullEncoded](ev0) with Predicate[NullEncoded] {
    def newUpdater = AtomicIntegerFieldUpdater.newUpdater(classOf[RCPredicate], "refCount")

    @volatile var refCount = 1
    def refCountCAS(before: Int, after: Int) = rcUpdater.compareAndSet(this, before, after)

    @tailrec final def tryEnter: Boolean = {
      val rc0 = refCount
      rc0 > 0 && (refCountCAS(rc0, rc0 + 1) || tryEnter)
    }

    def exit[A](map: THashMap2[A,_], key: A) { exit(map, key, 1) }

    @tailrec final def exit[A](map: THashMap2[A,_], key: A, count: Int) {
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

    def nonTxnPut[A](map: THashMap2[A,_], key: A, ev: NullEncoded): NullEncoded = {
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

      val z = NonTxn.transform2(this, map.insertCount.aStripe, (v: AnyRef, s: Int) => {
        (ev, (if (v == null) s + 1 else s), v)
      })
      if (z != null)
        exitFull
      return z
    }

    def nonTxnRemove[A](map: THashMap2[A,_], key: A): NullEncoded = {
      val d0 = data
      if (null == d0 && null == NonTxn.get(this)) {
        // no change required
        return null
      }

      // either there is already a positive refCount or this is a no-op
      val z = NonTxn.transform2(this, map.insertCount.aStripe, (v: AnyRef, s: Int) => {
        (null, (if (v == null) s else s - 1), v)
      })
      if (z != null)
        exit(map, key)
      return z
    }
    

    def txnGet[A](map: THashMap2[A,_], key: A, entered: Boolean)(implicit txn: Txn): NullEncoded = {
      val z = try {
        txn.get(this)
      } catch {
        case x => {
          if (entered)
            exit(map, key)
          throw x
        }
      }

      if (z == null) {
        // retry if this predicate is stale
        if (!entered && !tryEnter) {
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

        txn.afterCompletion { _ => exit(map, key) }
      }
      else if (entered) {
        // The predicate is pre-entered.  We must exit, but it's cheaper to
        // exit now instead of creating the callback.
        exit(map, key)
      }

      return z
    }

    def txnPut[A](map: THashMap2[A,_], key: A, ev: NullEncoded, entered: Boolean)(implicit txn: Txn): NullEncoded = {
      // present | entered || tryEnter | immediate exit | commit exit | rollback exit
      // --------+-------++----------+----------------+-------------+---------------
      //   yes   |  yes  ||   no     |       1        |      0      |       0
      //    no   |  yes  ||   no     |       0        |      0      |       1
      //   yes   |   no  ||   no     |       0        |      0      |       0
      //    no   |   no  ||   yes    |       0        |      0      |       1

      val z = try {
        txn.swap(this, ev)
      } catch {
        case x => {
          if (entered)
            exit(map, key)
          throw x
        }
      }

      if (z == null) {
        // we're inserting

        if (!entered && !tryEnter) {
          map.predicates.remove(key, this)
          // put back null before anybody else gets to see it
          txn.set(this, null)
          return map.txnPut(key, ev)
        }

        map.insertCount += 1

        // retain entry as bonus, unless we roll back
        txn.afterRollback { _ => exit(map, key) }
      }
      else if (entered) {
        // present -> present transition can rely on the bonus, exit
        // the entered entry immediately
        exit(map, key)
      }

      return z
    }

    def txnRemove[A](map: THashMap2[A,_], key: A, entered: Boolean)(implicit txn: Txn): NullEncoded = {
      // present | entered || tryEnter | immediate exit | commit exit | rollback exit
      // --------+-------++----------+----------------+-------------+---------------
      //   yes   |  yes  ||   no     |       0        |      2      |       1
      //    no   |  yes  ||   no     |       0        |      1      |       1
      //   yes   |   no  ||   no     |       0        |      1      |       0
      //    no   |   no  ||   yes    |       0        |      1      |       1

      val z = try {
        txn.get(this)
      } catch {
        case x => {
          if (entered)
            exit(map, key)
          throw x
        }
      }

      if (z == null) {
        // retry if this predicate is stale
        if (!entered && !tryEnter) {
          map.predicates.remove(key, this)
          return map.txnRemove(key)
        }

        txn.afterCompletion { _ => exit(map, key) }
      }
      else {
        if (entered) {
          // exit 2 on commit, 1 on rollback
          txn.afterCompletion { t => exit(map, key, if (t.status eq Txn.Committed) 2 else 1) }
        } else {
          // we must remove the bonus, but we don't need to enter, so do nothing on rollback
          txn.afterCommit { _ => exit(map, key) }
        }

        map.removeCount += 1

        txn.set(this, null)
      }

      return z
    }
  }

  private class Token

  private class TokenRef[A,B](map: THashMap2[A,B], key: A, token: Token) extends CleanableRef[Token](token) {
    var pred: SoftPredicate = null
    def cleanup() {
      map.pendingRemoves.enqueue(key, pred)
    }
  }

  private class SoftPredicate(val softRef: TokenRef[_,_], tok: Token
          ) extends TIdentityPairRef[Token,AnyRef](tok, null) with Predicate[IdentityPair[Token,AnyRef]] {

    softRef.pred = this

    def nonTxnGet[A]: NullEncoded = {
      // if this predicate is stale, then we can just linearize at the time at
      // which it was read from the predicate map, instead of right now
      val z = NonTxn.get(this)
      if (z == null) null else z._2
    }

    def nonTxnPut[A](map: THashMap2[A,_], key: A, ev: NullEncoded): NullEncoded = {
      // this read is not atomic, but we use the result only as a heuristic
      val d0 = data
      if (d0 != null) {
        // guess that we are not changing the size
        if (NonTxn.compareAndSet(this, d0, IdentityPair(d0._1, ev))) {
          // success
          return d0._2
        }
        // failure uses transform2, which also handles insert
      }

      val token = softRef.get
      if (token == null) {
        // this predicate is stale, retry
        return map.nonTxnPut(key, ev)
      }

      val z = NonTxn.transform2(this, map.insertCount.aStripe, (vp: IdentityPair[Token,AnyRef], s: Int) => {
        (IdentityPair(token, ev), (if (null == vp) s + 1 else s), vp)
      })
      if (z == null) null else z._2
    }
    
    def nonTxnRemove[A](map: THashMap2[A,_], key: A): NullEncoded = {
      if (data == null && NonTxn.get(this) == null) {
        // No change required.  If the predicate is stale we just linearize at
        // the time that the predicate was returned.
        return null
      }

      // no need to check softRef, because if the predicate is stale then this
      // is a no-op and we can linearize at the previous map.predicates.get(key)
      val z = NonTxn.transform2(this, map.removeCount.aStripe, (vp: IdentityPair[Token,AnyRef], s: Int) => {
        (null, (if (null == vp) s else s + 1), vp)
      })
      if (z == null) null else z._2
    }

    def txnGet[A](map: THashMap2[A,_], key: A, entered: Boolean)(implicit txn: Txn): NullEncoded = {
      val z = txn.get(this)
      if (z == null) {
        // either active+absent or stale
        val token = softRef.get
        if (token == null) {
          // stale
          map.txnGet(key)
        } else {
          // active+absent, pin the predicate
          txn.addReference(token)
          null
        }
      } else {
        // active+present, predicate can't be removed unless there is a write
        // that will conflict with us, so no need to pin
        z._2
      }
    }

    def txnPut[A](map: THashMap2[A,_], key: A, ev: NullEncoded, entered: Boolean)(implicit txn: Txn): NullEncoded = {
      val token = softRef.get
      if (token == null)
        return map.txnPut(key, ev)

      val z = txn.swap(this, IdentityPair(token, ev))
      if (z == null)
        map.insertCount += 1

      if (z == null) null else z._2
    }
    
    def txnRemove[A](map: THashMap2[A,_], key: A, entered: Boolean)(implicit txn: Txn): NullEncoded = {
      val z = txn.swap(this, null)
      if (z == null) {
        // make sure it isn't stale
        val token = softRef.get
        if (token == null)
          return map.txnRemove(key)

        // make sure it doesn't become stale
        txn.addReference(token)

        null
      } else {
        // predicate holds old value until commit
        map.removeCount += 1

        z._2
      }
    }
  }
}

class THashMap2[A,B] extends TMap2[A,B] {
  import THashMap2._

  def bind(implicit txn: Txn): TMap2.View[A,B] = new TMap2.AbstractTxnView[A,B,THashMap2[A,B]](txn, this) {
    def iterator: Iterator[(A,B)] = new Iterator[(A,B)] {

      insertCount()(txn) // add to read set
      private var avail: (A,B) = null
      private val underlying = predicates.entrySet.iterator
      advance()

      @tailrec private def advance() {
        if (!underlying.hasNext) {
          // EOI
          avail = null
        } else {
          val e = underlying.next()
          e.getValue.get(txn) match {
            case null => advance() // empty predicate, keep looking
            case ev => avail = (e.getKey, NullValue.decode[B](ev.asInstanceOf[AnyRef]))
          }
        }
      }

      def hasNext: Boolean = (avail != null)

      def next(): (A,B) = {
        val z = avail
        advance()
        z
      }
    }
  }

  val escaped: TMap2.View[A,B] = new TMap2.AbstractEscapedView[A,B,THashMap2[A,B]](this) {
    def get(key: A) = NullValue.decodeOption[B](nonTxnGet(key))

    override def put(key: A, value: B) = NullValue.decodeOption[B](nonTxnPut(key, NullValue.encode(value)))

    override def remove(key: A) = NullValue.decodeOption[B](nonTxnRemove(key))

    def iterator: Iterator[(A,B)] = new Iterator[(A,B)] {

      private var avail: (A,B) = null
      private val underlying = predicates.entrySet.iterator
      advance()

      @tailrec private def advance() {
        if (!underlying.hasNext) {
          // EOI
          avail = null
        } else {
          val e = underlying.next()
          e.getValue.nonTxnGet match {
            case null => advance() // empty predicate, keep looking
            case ev => avail = (e.getKey, NullValue.decode[B](ev))
          }
        }
      }

      def hasNext: Boolean = (avail != null)

      def next(): (A,B) = {
        val z = avail
        advance()
        z
      }
    }
  }

  
  override val single: TMap2.View[A,B] = new TMap2.SingleView[A,B,THashMap2[A,B]](this) {
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

  def size(implicit txn: Txn) = insertCount() - removeCount()
  
  def get(key: A)(implicit txn: Txn) = NullValue.decodeOption[B](txnGet(key))

  def put(key: A, value: B)(implicit txn: Txn) = NullValue.decodeOption[B](txnPut(key, NullValue.encode(value)))

  def remove(key: A)(implicit txn: Txn) = NullValue.decodeOption[B](txnRemove(key))


  /////////////////////

  private[impl] val insertCount = new StripedIntRef(0)
  private[impl] val removeCount = new StripedIntRef(0)

  private val predicates = new ConcurrentHashMap[A,Predicate[_]] {
    override def putIfAbsent(key: A, value: Predicate[_]): Predicate[_] = {
      STM.resurrect(key.hashCode, value)
      super.putIfAbsent(key, value)
    }

    override def remove(key: Any, value: Any): Boolean = {
      STM.embalm(key.hashCode, value.asInstanceOf[Predicate[_]])
      super.remove(key, value)
    }
  }

  private[impl] val pendingRemoves = new CleanupManager[A,SoftPredicate] {
    def cleanup(key: A, pred: SoftPredicate) {
      predicates.remove(key, pred)
    }
  }

  private def nonTxnGet(key: A): NullEncoded = predicates.get(key) match {
    case null => null
    case p => p.nonTxnGet
  }

  private def nonTxnPut(key: A, ev: NullEncoded): NullEncoded = {
    var p = predicates.get(key)
    if (null == p) {
      val fresh = new RCPredicate(ev)
      p = predicates.putIfAbsent(key, fresh)
      if (null == p) return null
    }
    return p.nonTxnPut(this, key, ev)
  }

  private def nonTxnRemove(key: A): NullEncoded = predicates.get(key) match {
    case null => null
    case p => p.nonTxnRemove(this, key)
  }

  private def txnGet(key: A)(implicit txn: Txn) = txnGetWithSoft(key)

  private def txnGetWithRC(key: A)(implicit txn: Txn): NullEncoded = {
    var entered = false
    var p = predicates.get(key)
    if (null == p) {
      val n = new RCPredicate(null)
      p = predicates.putIfAbsent(key, n)
      if (null == p) {
        entered = true
        p = n
      }
    }
    p.txnGet(this, key, entered)
  }

  private def txnGetWithSoft(key: A)(implicit txn: Txn): NullEncoded = {
    var p = predicates.get(key)
    if (null == p) {
      val token = new Token
      val n = new SoftPredicate(new TokenRef(this, key, token), token)
      p = predicates.putIfAbsent(key, n)
      if (null == p)
        p = n // success
    }
    p.txnGet(this, key, false)
    // reachabilityFence(token)
  }

  private def txnPut(key: A, ev: NullEncoded)(implicit txn: Txn): NullEncoded = {
    var entered = false
    var p = predicates.get(key)
    if (null == p) {
      val n = new RCPredicate(null)
      p = predicates.putIfAbsent(key, n)
      if (null == p) {
        entered = true
        p = n
      }
    }
    p.txnPut(this, key, ev, entered)
  }

  private def txnRemove(key: A)(implicit txn: Txn) = txnRemoveWithSoft(key)

  private def txnRemoveWithRC(key: A)(implicit txn: Txn): NullEncoded = {
    var entered = false
    var p = predicates.get(key)
    if (null == p) {
      val n = new RCPredicate(null)
      p = predicates.putIfAbsent(key, n)
      if (null == p) {
        entered = true
        p = n
      }
    }
    p.txnRemove(this, key, entered)
  }

  private def txnRemoveWithSoft(key: A)(implicit txn: Txn): NullEncoded = {
    var p = predicates.get(key)
    if (null == p) {
      val token = new Token
      val n = new SoftPredicate(new TokenRef(this, key, token), token)
      p = predicates.putIfAbsent(key, n)
      if (null == p)
        p = n
    }
    p.txnRemove(this, key, false)
    // reachabilityFence(token)
  }
}
