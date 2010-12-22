/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// PredicatedHashMap_Enum

package scala.concurrent.stm.experimental.impl

import scala.concurrent.stm.experimental.TMap
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.stm.experimental.TMap.Bound
import scala.concurrent.stm.{STM, Txn}
import scala.concurrent.stm.impl.{TAnyRef, StripedIntRef}

class PredicatedHashMap_Enum[A,B] extends TMap[A,B] {
  private val sizeRef = new StripedIntRef(0)
  private val predicates = new ConcurrentHashMap[A,TAnyRef[AnyRef]]

  def escaped: Bound[A,B] = new TMap.AbstractNonTxnBound[A,B,PredicatedHashMap_Enum[A,B]](this) {

    override def size(): Int = {
      sizeRef.escaped.get
    }

    def get(key: A): Option[B] = {
      NullValue.decodeOption(pred(key).escaped.get)
    }

    override def put(key: A, value: B): Option[B] = {
      val p = pred(key)
      val pn = p.escaped
      val before = pn.get
      if (null != before) {
        // try to update (no size change)
        if (pn.compareAndSet(before, NullValue.encode(value))) {
          // success
          return Some(NullValue.decode(before))
        }
        // failure goes to the transform2 implementation
      }
      return NullValue.decodeOption(STM.transform2(p, sizeRef.aStripe, (v: AnyRef, s: Int) => {
        (NullValue.encode(value), (if (null == v) s + 1 else s), v)
      }))
    }

    override def remove(key: A): Option[B] = {
      val p = predicates.get(key)
      if (null == p || null == p.escaped.get) {
        // no need to create a predicate, let's linearize right here
        return None
      }
      return NullValue.decodeOption(STM.transform2(p, sizeRef.aStripe, (v: AnyRef, s: Int) => {
        (null, (if (null == v) s else s - 1), v)
      }))
    }

//    protected def transformIfDefined(key: A,
//                                     pfOrNull: PartialFunction[Option[B],Option[B]],
//                                     f: Option[B] => Option[B]): Boolean = {
//      val p = pred(key)
//      val pn = p.escaped
//      val before = pn.get
//      if (null != pfOrNull && !pfOrNull.isDefinedAt(before)) {
//        // no change
//        return false
//      }
//      // make a CAS attempt
//      val after = f(before)
//      if (!before.isEmpty) {
//        if (!after.isEmpty && pn.compareAndSet(before, after)) {
//          // CAS success, and no size change
//          return true
//        }
//        // failure goes to the transform2 implementation
//      }
//      return STM.transform2(p, sizeRef.aStripe, (vo: Option[B], s: Int) => {
//        if (null != pfOrNull && (vo ne before) && !pfOrNull.isDefinedAt(vo)) {
//          // do nothing and return false
//          (vo, s, false)
//        } else {
//          // can we use the precomputed after?
//          val a = if (vo eq before) after else f(vo)
//          (a, s + (if (a.isEmpty) 0 else 1) - (if (vo.isEmpty) 0 else 1), true)
//        }
//      })
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

  def bind(implicit txn0: Txn): Bound[A, B] = new TMap.AbstractTxnBound[A,B,PredicatedHashMap_Enum[A,B]](txn0, this) {
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
          val v = e.getValue.get
          if (null != v) {
            apparentSize += 1
            avail = (e.getKey, NullValue.decode(v))
            return
          }
        }

        // end of iteration
        if (apparentSize != size) {
          txn.forceRollback(Txn.InvalidReadCause(unbind, "PredicatedHashMap_Enum.Iterator missed elements"))
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
    NullValue.decodeOption(pred(key).get)
  }

  def put(key: A, value: B)(implicit txn: Txn): Option[B] = {
    val prev = pred(key).swap(NullValue.encode(value))
    if (null == prev) {
      sizeRef += 1
      None
    } else {
      Some(NullValue.decode(prev))
    }
  }

  def remove(key: A)(implicit txn: Txn): Option[B] = {
    val prev = pred(key).swap(None)
    if (null != prev) {
      sizeRef -= 1
      Some(NullValue.decode(prev))
    } else {
      None
    }
  }

//  protected def transformIfDefined(key: A,
//                                   pfOrNull: PartialFunction[Option[B],Option[B]],
//                                   f: Option[B] => Option[B])(implicit txn: Txn): Boolean = {
//    val p = pred(key)
//    val prev = p.get
//    if (null != pfOrNull && !pfOrNull.isDefinedAt(prev)) {
//      false
//    } else {
//      val after = f(prev)
//      p.set(after)
//      sizeRef += (if (after.isEmpty) 0 else 1) - (if (prev.isEmpty) 0 else 1)
//      true
//    }
//  }

  private def pred(key: A): TAnyRef[AnyRef] = {
    val pred = predicates.get(key)
    if (null != pred) pred else createPred(key)
  }

  private def createPred(key: A): TAnyRef[AnyRef] = {
    val fresh = new TAnyRef[AnyRef](null)
    val race = predicates.putIfAbsent(key, fresh)
    if (null != race) race else fresh
  }
}
