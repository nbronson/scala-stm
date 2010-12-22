/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// PredicatedHashMap_LazyGC_Enum

package scala.concurrent.stm.experimental.impl

import scala.concurrent.stm.experimental.TMap
import scala.concurrent.stm.experimental.TMap.Bound
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.stm.{STM, Txn}
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import scala.concurrent.stm.impl.StripedIntRef

private object PredicatedHashMap_LazyGC_Enum {

  private trait TokenRef[A,B] {
    def get: Token[A,B]
    def isWeak: Boolean
  }

  private class WeakTokenRef[A,B](map: PredicatedHashMap_LazyGC_Enum[A,B],
                                  key: A,
                                  token: Token[A,B]) extends CleanableRef[Token[A,B]](token) with TokenRef[A,B] {
    var pred: Predicate[A,B] = null

    def cleanup(): Unit = map.predicates.remove(key, pred)
    def isWeak = true
  }

  // We use the Token as its own strong reference to itself.  A more
  // straightforward embedding into the type system would be to have
  // predicate.tokenRef: Either[Token,SoftRef[Token]], but then we would have
  // an extra Left or Right instance for each ref.
  private class Token[A,B] extends TokenRef[A,B] {
    def get = this
    def isWeak = false
  }

  private class CreationInfo[A,B](val txn: Txn,
                                  map: PredicatedHashMap_LazyGC_Enum[A,B],
                                  key: A,
                                  var removeOnCommit: Boolean) extends (Txn => Unit) {
    var pred: Predicate[A,B] = null

    def apply(t: Txn): Unit = {
      pred.creationInfo = null
      // removeOnRollback is always true
      if (removeOnCommit || t.status != Txn.Committed) {
        map.immediateCleanup(key, pred)
      }
    }
  }

  private val predicateTokenRefUpdater = new Predicate[Int,Int](null, null).newUpdater()

  // we extend from TIdentityPairRef opportunistically
  private class Predicate[A,B](tokenRef0: TokenRef[A,B],
                               var creationInfo: CreationInfo[A,B]
          ) extends TIdentityPairRef[Token[A,B],B](null) {

    if (null != creationInfo) creationInfo.pred = this

    @volatile private var _tokenRef: TokenRef[A,B] = tokenRef0

    def newUpdater() = AtomicReferenceFieldUpdater.newUpdater(classOf[Predicate[_,_]], classOf[TokenRef[_,_]], "_tokenRef")

    def tokenRef = _tokenRef
    def tokenRefCAS(before: TokenRef[A,B], after: TokenRef[A,B]) = {
      predicateTokenRefUpdater.compareAndSet(this, before, after)
    }

    def createdBy(txn: Txn) = {
      val ci = creationInfo
      null != ci && (ci.txn eq txn) 
    }
  }
}

class PredicatedHashMap_LazyGC_Enum[A,B] extends TMap[A,B] {
  import PredicatedHashMap_LazyGC_Enum._

  private val sizeRef = new StripedIntRef(0)

  private val predicates = new ConcurrentHashMap[A,Predicate[A,B]] {
    override def putIfAbsent(key: A, value: Predicate[A,B]): Predicate[A,B] = {
      STM.resurrect(key.hashCode, value)
      super.putIfAbsent(key, value)
    }

    override def replace(key: A, oldValue: Predicate[A,B], newValue: Predicate[A,B]): Boolean = {
      val h = key.hashCode
      STM.embalm(h, oldValue)
      STM.resurrect(h, newValue)
      super.replace(key, oldValue, newValue)
    }

    override def remove(key: Any, value: Any): Boolean = {
      STM.embalm(key.hashCode, value.asInstanceOf[Predicate[A,B]])
      super.remove(key, value)
    }
  }


  def escaped: Bound[A,B] = new TMap.AbstractNonTxnBound[A,B,PredicatedHashMap_LazyGC_Enum[A,B]](this) {

    override def size(): Int = {
      sizeRef.escaped.get
    }

    def get(key: A): Option[B] = {
      val p = predicates.get(key)
      if (p == null) None else decodePair(p.escaped.get)
    }

    override def put(key: A, value: B): Option[B] = {
      putImpl(key, value, predicates.get(key))
    }

    private def putImpl(key: A, value: B, p: Predicate[A,B]): Option[B] = {

      // put is harder than remove because remove can blindly write None
      // to a stale entry without causing problems

      if (null != p) {
        val tokenRef = p.tokenRef
        if (null != tokenRef) {
          val token = tokenRef.get
          if (null != token) {
            // the predicate is still active (or was two lines ago)
            if (tokenRef.isWeak) {
              // this predicate is already in its most general state, and it is
              // active, so we will always succeed
              return putToActive(token, value, p)
            } else {
              // the predicate is strong, but we can still perform a Some -> Some
              // transition
              val prevPair = p.escaped.get
              if (null != prevPair && p.escaped.compareAndSet(prevPair, IdentityPair(token, value))) {
                // success
                return Some(prevPair._2)
              } else if (null != ensureWeak(key, p)) {
                return putToActive(token, value, p)
              }
              // else p is stale and must be replaced
            }
          }
          // else p is stale and must be replaced
        }
        // else p is stale and must be replaced
      }
      // else there is no p, so we must create one

      val freshToken = new Token[A,B]
      val freshPred = new Predicate(freshToken, null)

      if (null == p) {
        // no previous predicate
        val race = predicates.putIfAbsent(key, freshPred)
        if (null != race) {
          // CAS failed, try again using the predicate that beat us
          return putImpl(key, value, race)
        }
      } else {
        // existing stale predicate
        if (!predicates.replace(key, p, freshPred)) {
          // someone else already removed the predicate, can we still use the
          // fresh one we just created?
          var race = predicates.get(key)
          if (null == race) {
            // second try, afterward race will be null on success
            race = predicates.putIfAbsent(key, freshPred)
          }
          if (null != race) {
            return putImpl(key, value, race)
          }
          // else second-try success
        }
      }

      putToActive(freshToken, value, freshPred)
    }

    private def putToActive(token: Token[A,B], value: B, p: Predicate[A,B]) = {
      decodePair(STM.transform2(p, sizeRef.aStripe, (vp: IdentityPair[Token[A,B],B], s: Int) => {
        (IdentityPair(token, value), (if (null == vp) s + 1 else s), vp)
      }))
    }

    override def remove(key: A): Option[B] = {
      val p = predicates.get(key)
      if (null == p) {
        // no predicate means no entry, as for get()
        return None
      }

      // We don't need to weaken to observe absence or presence here.  If we
      // see a strong pred then every thread that has already observed the
      // predicate will conflict with the -> None transition, and threads that
      // have not yet observed the pred must serialize after us anyway.  The
      // only wrinkle is that we can't clean up the strong ref if we observe
      // absence, because another txn may have created the predicate but not
      // yet done the store to populate it.
      val prev = STM.transform2(p, sizeRef.aStripe, (vp: IdentityPair[Token[A,B],B], s: Int) => {
        (null, (if (null == vp) s else s - 1), vp)
      })
      if (null == prev) {
        // Not previously present, somebody else's cleanup problem.  The
        // predicate may have been stale, and already removed.
        return None
      } else {
        // the committed state was Some(v), so it falls to us to clean up if
        // the predicate was strong
        immediateCleanup(key, p)
        return Some(prev._2)
      }
    }

//    override def transform(key: A, f: (Option[B]) => Option[B]) {
//      STM.atomic(unbind.transform(key, f)(_))
//    }
//
//    override def transformIfDefined(key: A, pf: PartialFunction[Option[B],Option[B]]): Boolean = {
//      STM.atomic(unbind.transformIfDefined(key, pf)(_))
//    }
//
//    protected def transformIfDefined(key: A,
//                                     pfOrNull: PartialFunction[Option[B],Option[B]],
//                                     f: Option[B] => Option[B]): Boolean = {
//      throw new Error
//    }

    def iterator: Iterator[(A,B)] = new Iterator[(A,B)] {
      val iter = predicates.keySet().iterator
      var avail: (A,B) = null
      advance()

      private def advance() {
        while (iter.hasNext) {
          val k = iter.next()
          get(k) match {
            case Some(v) => {
              avail = (k,v)
              return
            }
            case None => // keep looking
          }
        }
        avail = null
      }

      def hasNext: Boolean = null != avail
      def next(): (A,B) = {
        val z = avail
        advance()
        z
      }
    }
  }

  def bind(implicit txn0: Txn): Bound[A, B] = new TMap.AbstractTxnBound[A,B,PredicatedHashMap_LazyGC_Enum[A,B]](txn0, this) {
    def iterator: Iterator[(A,B)] = new Iterator[(A,B)] {
      private var apparentSize = 0
      private val iter = predicates.entrySet().iterator
      private var avail: (A,B) = null

      advance()
      if (txn.barging) {
        // auto-readForWrite will prevent the size from changing in another txn
        size
      }

      private def advance() {
        while (iter.hasNext) {
          val e = iter.next()
          getImpl(e.getKey, e.getValue) match {
            case Some(v) => {
              apparentSize += 1
              avail = (e.getKey, v)
              return
            }
            case None => {}
          }
        }

        // end of iteration
        if (apparentSize != size) {
          txn.forceRollback(Txn.InvalidReadCause(unbind, "PredicatedHashMap_LazyGC_Enum.Iterator missed elements"))
        }
        avail = null
      }

      def hasNext: Boolean = null != avail

      def next(): (A,B) = {
        val z = avail
        advance()
        z
      }
    }
  }

  def isEmpty(implicit txn: Txn): Boolean = {
    sizeRef getWith { _ > 0 }
  }

  def size(implicit txn: Txn): Int = {
    sizeRef.get
  }

  def get(key: A)(implicit txn: Txn): Option[B] = {
    getImpl(key, predicates.get(key))
  }

  private def getImpl(key: A, pred: Predicate[A,B])(implicit txn: Txn): Option[B] = {
    if (null != pred) {
      val txnState = pred.get
      if (null != txnState) {
        // we are observing presence, predicate is definitely active and either
        // strong or weak is okay  
        //assert (pred.tokenRef.get eq txnState._1)
        return Some(txnState._2)
      }

      // we are observing absence, so we must both make sure that the token is
      // weak, and record a strong reference to it, unless this is the txn that
      // created the predicate
      if (pred.createdBy(txn)) {
        return None
      }
      
      val token = ensureWeak(key, pred)
      if (null != token) {
        txn.addReference(token)
        return None
      }
      // else predicate is stale and must be replaced
    }
    // else there is no predicate and we must make one

    val freshToken = new Token[A,B]
    val freshPred = new Predicate(freshToken, new CreationInfo(txn, this, key, true))

    if (null == pred || !predicates.replace(key, pred, freshPred)) {
      // No previous predicate, or predicate we think is previous is no longer
      // there (and most likely removed by the thread that made it stale).
      val race = predicates.putIfAbsent(key, freshPred)
      if (null != race) {
        // a new predicate is available, retry using it
        return getImpl(key, race)
      }
    }

    // Any transaction that uses this predicate must first weaken it, so if it
    // is still strong at the end of the transaction then we can remove it.
    txn.afterCompletion(freshPred.creationInfo)

    // We have to perform a txn read from our new predicate, because another
    // txn may have already updated it.  We know, however, that it is active.
    return decodePair(freshPred.get)
  }

  def put(key: A, value: B)(implicit txn: Txn): Option[B] = {
    putImpl(key, value, predicates.get(key))
  }

  private def putImpl(key: A, value: B, pred: Predicate[A,B])(implicit txn: Txn): Option[B] = {
    if (null != pred) {
      if (pred.createdBy(txn)) {
        // this txn created the predicate, so we now have knowledge that if the
        // transaction commits, cleanup is _not_ necessary
        pred.creationInfo.removeOnCommit = false
      }

      // we will definitely conflict with any txn that performs an immediate
      // cleanup, so as long as we can find the token we can use this predicate
      val ref = pred.tokenRef
      val token = if (null == ref) null else ref.get
      if (null != token) {
        val prev = pred.swap(IdentityPair(token, value))
        if (null == prev) sizeRef += 1
        return decodePair(prev)
      }
    }

    // There is either no predicate or the predicate is stale.  Make a new one.
    val freshToken = new Token[A,B]
    val freshPred = new Predicate(freshToken, new CreationInfo(txn, this, key, false))

    if (null == pred || !predicates.replace(key, pred, freshPred)) {
      // No previous predicate, or predicate we think is previous is no longer
      // there (and most likely removed by the thread that made it stale).
      val existing = predicates.putIfAbsent(key, freshPred)
      if (null != existing) {
        // CAS failed, access the predicate that beat us
        return putImpl(key, value, existing)
      }
    }

    // Any transaction that has knowledge that after its completion the
    // predicate is in the strong + active + absent state, must take
    // responsibility for removing it.  That will happen if this transaction
    // rolls back.  We install using afterCompletion so that a subsequent
    // remove in this txn can enable CreationInfo.removeOncommit.
    txn.afterCompletion(freshPred.creationInfo)

    // We have to perform a txn read from the new predicate, because another
    // txn may have already updated it.  We don't, however, have to do all of
    // the normal work, because there is no way that the predicate could have
    // become stale.
    val prev = freshPred.swap(IdentityPair(freshToken, value))
    if (null == prev) sizeRef += 1
    return decodePair(prev)
  }

  def remove(key: A)(implicit txn: Txn): Option[B] = {
    val pred = predicates.get(key)
    if (null != pred) {
      if (pred.createdBy(txn)) {
        // We now have knowledge that if this txn commits, the predicate should
        // be cleaned up.  Also, we don't need to weaken it.
        pred.creationInfo.removeOnCommit = true
        val prev = pred.swap(null)
        if (null != prev) sizeRef -= 1
        return decodePair(prev)
      }

      val txnState = pred.bind.readForWrite
      if (null != txnState) {
        // We are observing presence, no weak ref necessary, but if we commit
        // then we are responsible for cleanup.  Since the predicate was not
        // created by this transaction, a subsequent put won't have the chance
        // to avoid weakening, but that's okay.
        //assert (pred.tokenRef.get eq txnState._1)
        pred.set(null)
        if (!pred.tokenRef.isWeak) {
          // we are responsible for cleanup 
          txn.afterCommit(deferredCleanup(key, pred))
        }
        sizeRef -= 1
        return Some(txnState._2)
      }

      // we are observing absence, so we must both make sure that the token is
      // weak, and record a strong reference to it
      val token = ensureWeak(key, pred)
      if (null != token) {
        txn.addReference(token)
        return None
      }
    }

    // There is no predicate present, which means (at least right now) there is
    // nothing to remove.  This is equivalent to a get.
    val result = getImpl(key, null)
    if (!result.isEmpty) {
      // guess we have to remove after all
      return remove(key)
    } else {
      // expected
      return None
    }
  }

  private def deferredCleanup(key: A, pred: Predicate[A,B]) = (t: Txn) => immediateCleanup(key, pred)
  
  private def immediateCleanup(key: A, pred: Predicate[A,B]) {
    val r = pred.tokenRef
    if (!r.isWeak && pred.tokenRefCAS(r, null)) {
      // successfully made it stale
      predicates.remove(key, pred)
      // no need to retry on remove failure, somebody else did it for us
    }
  }

//  protected def transformIfDefined(key: A,
//                                   pfOrNull: PartialFunction[Option[B],Option[B]],
//                                   f: Option[B] => Option[B])(implicit txn: Txn): Boolean = {
//    val v0 = get(key)
//    if (null != pfOrNull && !pfOrNull.isDefinedAt(v0)) {
//      false
//    } else {
//      f(v0) match {
//        case Some(v) => put(key, v)
//        case None => remove(key)
//      }
//      true
//    }
//  }

  //////////////// encoding and decoding into the pair

  private def decodePair(pair: IdentityPair[Token[A,B],B]): Option[B] = {
    if (null == pair) None else Some(pair._2)
  }

  //////////////// predicate management

  // returns null if unsuccessful
  private def ensureWeak(key: A, pred: Predicate[A,B]): Token[A,B] = {
    val tokenRef = pred.tokenRef
    if (null == tokenRef) {
      // this predicate is stale and must be replaced
      null
    } else if (tokenRef.isWeak) {
      // possible success, but not of our doing
      tokenRef.get
    } else {
      // try the strong->weak transition
      val token = tokenRef.get
      val weakRef = new WeakTokenRef(this, key, token)
      weakRef.pred = pred
      if (pred.tokenRefCAS(tokenRef, weakRef)) {
        // success!
        token
      } else {
        // another thread either did our work for us (strong -> weak) or
        // prevented us from succeeding (strong -> stale)
        if (null != pred.tokenRef) token else null
      }
    }
  }
}
