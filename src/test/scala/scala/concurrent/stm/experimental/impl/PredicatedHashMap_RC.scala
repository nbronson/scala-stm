/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// PredicatedHashMap_RC

package scala.concurrent.stm.experimental.impl

import scala.concurrent.stm.experimental.TMap
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.stm.experimental.TMap.Bound
import scala.concurrent.stm.{STM, Txn}
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import scala.concurrent.stm.impl.TAnyRef


object PredicatedHashMap_RC {
  private val refCountUpdater = (new Pred[Int](null)).newUpdater

  class Pred[B](init0: AnyRef) extends TAnyRef[AnyRef](init0) {
    def newUpdater = AtomicIntegerFieldUpdater.newUpdater(classOf[Pred[_]], "_refCount")
    @volatile private var _refCount = 1

    def refCount = _refCount
    def refCountCAS(before: Int, after: Int) = refCountUpdater.compareAndSet(this, before, after)
  }
}

class PredicatedHashMap_RC[A,B] extends TMap[A,B] {
  import PredicatedHashMap_RC._

  private val predicates = new ConcurrentHashMap[A,Pred[B]] {
    override def putIfAbsent(key: A, value: Pred[B]): Pred[B] = {
      STM.resurrect(key.hashCode, value)
      super.putIfAbsent(key, value)
    }

    override def replace(key: A, oldValue: Pred[B], newValue: Pred[B]): Boolean = {
      val h = key.hashCode
      STM.embalm(h, oldValue)
      STM.resurrect(h, newValue)
      super.replace(key, oldValue, newValue)
    }

    override def remove(key: Any, value: Any): Boolean = {
      STM.embalm(key.hashCode, value.asInstanceOf[Pred[B]])
      super.remove(key, value)
    }
  }


  def escaped: Bound[A,B] = new TMap.AbstractNonTxnBound[A,B,PredicatedHashMap_RC[A,B]](this) {

    def get(key: A): Option[B] = {
      // if no predicate exists, or the one we get is stale, we can still read None
      val p = existingPred(key)
      if (null == p) None else NullValue.decodeOption(p.escaped.get)
    }

    override def put(key: A, value: B): Option[B] = {
      // this is like enter()
      var p = predicates.get(key)
      var fresh: Pred[B] = null
      do {
        if (null != p) {
          var rc = p.refCount
          while (rc > 0) {
            if (p.refCountCAS(rc, rc + 1)) {
              // successfully entered a predicate that was not
              return putExisting(key, value, p)
            }
            rc = p.refCount
          }
          predicates.remove(key, p)
        }
        fresh = new Pred[B](NullValue.encode(value))
        p = predicates.putIfAbsent(key, fresh)
      } while (null != p)
      // our fresh predicate won
      None
    }

    private def putExisting(key: A, value: B, pred: Pred[B]): Option[B] = {
      val prev = pred.escaped.swap(NullValue.encode(value))
      if (null != prev) {
        exit(key, pred, 1)
        Some(NullValue.decode(prev))
      } else {
        // bonus cancels out exit
        None
      }
    }

    override def remove(key: A): Option[B] = {
      // if no predicate exists, then we don't need to create one
      val p = existingPred(key)
      if (null == p) {
        None
      } else {
        val prev = p.escaped.swap(null)
        if (null != prev) {
          exit(key, p, 1)
          Some(NullValue.decode(prev))
        } else {
          None
        }
      }
    }

//    override def transform(key: A, f: (Option[B]) => Option[B]) {
//      // TODO: implement directly
//      STM.atomic(unbind.transform(key, f)(_))
//    }
//
//    override def transformIfDefined(key: A, pf: PartialFunction[Option[B],Option[B]]): Boolean = {
//      // TODO: implement directly
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

  def bind(implicit txn0: Txn): Bound[A, B] = new TMap.AbstractTxnBound[A,B,PredicatedHashMap_RC[A,B]](txn0, this) {
    def iterator: Iterator[(A,B)] = throw new UnsupportedOperationException
  }

  def isEmpty(implicit txn: Txn): Boolean = throw new UnsupportedOperationException

  def size(implicit txn: Txn): Int = throw new UnsupportedOperationException

  def get(key: A)(implicit txn: Txn) = {
    val pred = enter(key)
    txn.afterCompletion(t => exit(key, pred, 1))
    NullValue.decodeOption(pred.get)
  }

  def put(k: A, v: B)(implicit txn: Txn): Option[B] = {
    val p = enter(k)
    try {
      val prev = p.swap(NullValue.encode(v))
      if (null == prev) {
        // None -> Some.  On commit, we leave +1 on the reference count
        txn.afterRollback(t => exit(k, p, 1))
        None
      } else {
        // Some -> Some
        txn.afterCompletion(t => exit(k, p, 1))
        Some(NullValue.decode(prev))
      }
    } catch {
      case x => {
        exit(k, p, 1)
        throw x
      }
    }
  }

  def remove(k: A)(implicit txn: Txn): Option[B] = {
    val p = enter(k)
    try {
      val prev = p.swap(null)
      if (null != prev) {
        // Some -> None.  On commit, we erase the +1 that was left by the
        // None -> Some transition
        txn.afterCompletion(t => exit(k, p, (if (txn.status == Txn.Committed) 2 else 1)))
        Some(NullValue.decode(prev))
      } else {
        // None -> None
        txn.afterCompletion(t => exit(k, p, 1))
        None
      }
    } catch {
      case x => {
        exit(k, p, 1)
        throw x
      }
    }
  }


//  override def transform(key: A, f: (Option[B]) => Option[B])(implicit txn: Txn) {
//    val p = enter(key)
//    var sizeDelta = 0
//    try {
//      val before = p.get
//      val after = f(before)
//      p.set(after)
//      sizeDelta = (if (after.isEmpty) 0 else 1) - (if (before.isEmpty) 0 else 1)
//    } finally {
//      txn.afterCompletion(t => exit(key, p, (if (txn.status == Txn.Committed) 1 - sizeDelta else 1)))
//    }
//  }
//
//  override def transformIfDefined(key: A, pf: PartialFunction[Option[B],Option[B]])(implicit txn: Txn): Boolean = {
//    val p = enter(key)
//    var sizeDelta = 0
//    try {
//      val before = p.get
//      if (pf.isDefinedAt(before)) {
//        val after = pf(before)
//        p.set(after)
//        sizeDelta = (if (after.isEmpty) 0 else 1) - (if (before.isEmpty) 0 else 1)
//        true
//      } else {
//        false
//      }
//    } finally {
//      txn.afterCompletion(t => exit(key, p, (if (txn.status == Txn.Committed) 1 - sizeDelta else 1)))
//    }
//  }
//
//  protected def transformIfDefined(key: A,
//                                   pfOrNull: PartialFunction[Option[B],Option[B]],
//                                   f: Option[B] => Option[B])(implicit txn: Txn): Boolean = {
//    throw new Error
//  }

  private def existingPred(key: A): Pred[B] = predicates.get(key)

  private def enter(k: A): Pred[B] = {
    var p = predicates.get(k)
    var fresh: Pred[B] = null
    do {
      if (null != p) {
        var rc = p.refCount
        while (rc > 0) {
          if (p.refCountCAS(rc, rc + 1))
            return p
          rc = p.refCount
        }
        predicates.remove(k, p)
      }
      fresh = new Pred[B](null)
      p = predicates.putIfAbsent(k, fresh)
    } while (null != p)
    fresh
  }

  private def exit(k: A, p: Pred[B], d: Int) {
    val rc = p.refCount
    if (p.refCountCAS(rc, rc - d)) {
      if (rc - d == 0) predicates.remove(k, p)
    } else {
      exit(k, p, d)
    }
  }
}
