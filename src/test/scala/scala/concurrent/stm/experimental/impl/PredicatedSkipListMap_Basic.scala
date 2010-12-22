/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// PredicatedSkipListMap_Basic

package scala.concurrent.stm.experimental.impl

import scala.concurrent.stm.experimental.TMap
import scala.concurrent.stm.experimental.TMap.Bound
import scala.concurrent.stm.{STM, Txn}
import edu.stanford.ppl.util.PeekableCSLMap
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.stm.impl.TIntRef
// TODO: use Ordered or Ordering

object PredicatedSkipListMap_Basic {
  class Predicate[B] extends TOptionRef[B](None) {
    @volatile var leftNotified = false
//    @volatile var rightNotified = false
//    val predInsCount = new TIntRef(0)
    val succInsCount = new TIntRef(0)
  }

//  val iterNextValueField = {
//    val f = Class.forName("java.util.concurrent.ConcurrentSkipListMap$Iter").getDeclaredField("nextValue")
//    f.setAccessible(true)
//    f
//  }
//
//  val subMapIterNextValueField = {
//    val f = Class.forName("java.util.concurrent.ConcurrentSkipListMap$SubMap$SubMapIter").getDeclaredField("nextValue")
//    f.setAccessible(true)
//    f
//  }
//
//  def iterPeek[A,B](iter: java.util.Iterator[java.util.Map.Entry[A,B]]): B = {
//    // look for a field named "nextValue"
//    iterNextValueField.get(iter).asInstanceOf[B]
//  }
//
//  def subMapIterPeek[A,B](iter: java.util.Iterator[java.util.Map.Entry[A,B]]): B = {
//    // look for a field named "nextValue"
//    subMapIterNextValueField.get(iter).asInstanceOf[B]
//  }
}

class PredicatedSkipListMap_Basic[A,B] extends TMap[A,B] {
  import PredicatedSkipListMap_Basic._

  private val predicates = new PeekableCSLMap[A,Predicate[B]]
  private val firstInsCount = new TIntRef(0)
  //private val lastInsCount = new TIntRef(0)
  private val bargingCount = new AtomicInteger(0)

  def escaped = new TMap.AbstractNonTxnBound[A,B,PredicatedSkipListMap_Basic[A,B]](this) {

    def get(key: A): Option[B] = {
      // if no predicate exists, then we don't need to create one
      val p = existingPred(key)
      if (null == p) None else p.escaped.get
    }

    override def put(key: A, value: B): Option[B] = {
      predicateForInsert(key).escaped.swap(Some(value))
    }

    override def remove(key: A): Option[B] = {
      // if no predicate exists, then we don't need to create one
      val p = existingPred(key)
      if (null == p) None else p.escaped.swap(None)
    }

    override def higher(key: A): Option[(A,B)] = {
      // we use a txn, because the value read must be consistent with the
      // protecting insCount reads
      STM.atomic(unbind.higher(key)(_))
    }

//    override def transform(key: A, f: (Option[B]) => Option[B]) {
//      predicateForInsert(key).escaped.transform(f)
//    }
//
//    override def transformIfDefined(key: A, pf: PartialFunction[Option[B],Option[B]]): Boolean = {
//      predicateForInsert(key).escaped.transformIfDefined(pf)
//    }
//
//    protected def transformIfDefined(key: A,
//                                     pfOrNull: PartialFunction[Option[B],Option[B]],
//                                     f: Option[B] => Option[B]): Boolean = {
//      throw new Error
//    }

    def iterator: Iterator[(A,B)] = new Iterator[(A,B)] {
      val iter = predicates.keySet().iterator.asInstanceOf[PeekableCSLMap.PeekIterator[A,Predicate[B]]]
      var avail: (A,B) = null
      advance()

      private def advance() {
        while (iter.hasNext) {
          val p = iter.peekValue()
          val k = iter.next()
          val vOpt = p.escaped.get
          if (!vOpt.isEmpty) {
            avail = (k, vOpt.get)
            return
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

  def bind(implicit txn0: Txn): Bound[A, B] = new TMap.AbstractTxnBound[A,B,PredicatedSkipListMap_Basic[A,B]](txn0, this) {
    def iterator: Iterator[(A,B)] = {
      if (txn.barging) {
        bargingCount.getAndIncrement()
        txn.afterCompletion(_ => { bargingCount.getAndDecrement() })
      }

      // this will cause the txn to be invalidated if we missed a leading key
      firstInsCount.get

      // Iteration is tricky, because the underlying iterator will already
      // have advanced to the _next_ element when returning the existing one,
      // but we can't read the current element's succInsCount until after it
      // has been returned to us.  We have three options:
      //  1) don't use an iterator, but just repeatedly apply higherEntry
      //  2) use two iterators, with one running just ahead of the second
      //  3) hack in a peek() operator using reflection
      //  4) duplicate the entire code for ConcurrentSkipListMap
      // Option (1) will have bad performance.  Option (2) seems very
      // complicated, because the iterators may not see the same set of
      // entries.  Option (3) is brittle, because the library may change.
      // Therefore, we go with option (4).

      val iter = predicates.keySet().iterator.asInstanceOf[PeekableCSLMap.PeekIterator[A,Predicate[B]]]

      return new Iterator[(A,B)] {
        var avail: (A,B) = null
        advance()

        private def advance() {
          while (iter.hasNext) {
            // Before iter.next() returns X, it will first find X's successor.
            // We need to perform the read of X.succInsCount before it does
            // that.
            val p = iter.peekValue()
            p.succInsCount.get
            val k = iter.next
            val vOpt = p.get
            if (!vOpt.isEmpty) {
              avail = (k, vOpt.get)
              return
            }
          }
          avail = null
        }

        def hasNext = null != avail
        def next = {
          val z = avail
          advance()
          z
        }
      }
    }

    override def higher(key: A): Option[(A,B)] = unbind.higher(key)(txn)
  }

  def isEmpty(implicit txn: Txn): Boolean = bind.iterator.hasNext

  def size(implicit txn: Txn): Int = {
    var n = 0    
    var iter = bind.iterator
    while (iter.hasNext) { n += 1; iter.next }
    n
  }

  def get(key: A)(implicit txn: Txn): Option[B] = {
    predicateForNonInsert(key).get
  }

  def put(key: A, value: B)(implicit txn: Txn): Option[B] = {
    predicateForInsert(key).swap(Some(value))
  }

  def remove(key: A)(implicit txn: Txn): Option[B] = {
    predicateForNonInsert(key).swap(None)
  }

  def higher(key: A)(implicit txn: Txn): Option[(A,B)] = {
    // Since we can only protect traversals to the successor, we need to find
    // a usable predicate to the left of key to protect against insertions.
    val below = predicates.floorEntry(key)
    if (below == null) higherFromFirst(key) else higherFromLower(key, below)
  }

  private def higherFromFirst(key: A)(implicit txn: Txn): Option[(A,B)] = {
    // this read protects the traversal to the first element
    firstInsCount.get
    val iter = predicates.keySet().iterator.asInstanceOf[PeekableCSLMap.PeekIterator[A,Predicate[B]]]
    var avail: (A,B) = null
    while (iter.hasNext) {
      val p = iter.peekValue()
      // this read protects the traversal to e's successor that will occur when
      // iter.next() is called
      p.succInsCount.get
      val k = iter.next()
      if (k.asInstanceOf[Comparable[A]].compareTo(key) > 0) {
        val vOpt = p.get
        if (!vOpt.isEmpty) {
          return Some((k, vOpt.get))
        }
      }
    }
    return None
  }

  private def higherFromLower(key: A, lowerEntry: java.util.Map.Entry[A,Predicate[B]])(implicit txn: Txn): Option[(A,B)] = {
    // In a system that GC-ed predicates we would have to verify that key was
    // still present, but we're safe in this particular implementation.

    // this read protects the traversal to lowerEntry's successor
    lowerEntry.getValue.succInsCount.get
    val tail = predicates.tailMap(lowerEntry.getKey, false)
    val iter = tail.keySet().iterator.asInstanceOf[PeekableCSLMap.PeekIterator[A,Predicate[B]]]
    var avail: (A,B) = null
    while (iter.hasNext) {
      val p = iter.peekValue()
      // this read protects the traversal to e's successor that will occur when
      // iter.next() is called
      p.succInsCount.get
      val k = iter.next()
      val vOpt = p.get
      if (!vOpt.isEmpty) {
        return Some((k, vOpt.get))
      }
    }
    return None
  }

  // TODO: replace with a version that goes left, then searches right using succInsCount only
//  def higher(key: A)(implicit txn: Txn): Option[(A,B)] = {
//    // There's a bit of a catch-22, because we can't find the insCount that
//    // protects our range access until we've performed the access.  Also, we
//    // may have to skip one or more predicates until we find one that actually
//    // contains a value.
//    val tail = predicates.tailMap(key, false)
//
//    val first = tail.firstEntry
//    if (null == first) {
//      // changes to the tail must adjust lastInsCount
//      lastInsCount.get
//    } else {
//      // changes to the head of the tail must change the predInsCount of first
//      first.getValue.predInsCount.get
//    }
//
//    // for the second (protected) read we will use an iterator, so that we can
//    // keep going if we see absent entries
//    val iter = tail.entrySet.iterator
//    var availValue = subMapIterPeek(iter)
//    if (availValue ne (if (null == first) null else first.getValue)) {
//      // the insCount we read was not the right one, try again
//      return higher(key)
//    }
//
//    // we can now iterate as in bind.iterator
//    while (null != availValue) {
//      availValue.get match {
//        case Some(v) => {
//          // no protection for iter.next needed, because we just want the key
//          return Some((iter.next.getKey, v))
//        }
//        case None => {} // keep searching
//      }
//      availValue.succInsCount.get
//      iter.next
//    }
//    return None
//  }

//  override def transform(key: A, f: (Option[B]) => Option[B])(implicit txn: Txn) {
//    predicateForInsert(key).transform(f)
//  }
//
//  override def transformIfDefined(key: A, pf: PartialFunction[Option[B],Option[B]])(implicit txn: Txn): Boolean = {
//    predicateForInsert(key).transformIfDefined(pf)
//  }
//
//  protected def transformIfDefined(key: A,
//                                   pfOrNull: PartialFunction[Option[B],Option[B]],
//                                   f: Option[B] => Option[B])(implicit txn: Txn): Boolean = {
//    throw new Error
//  }

  private def existingPred(key: A): Predicate[B] = predicates.get(key)

  private def predicateForNonInsert(key: A): Predicate[B] = {
    val p = predicates.get(key)
    if (null != p) {
      p
    } else {
      val fresh = new Predicate[B]
      val race = predicates.putIfAbsent(key, fresh)
      if (null != race) race else fresh
    }
  }

  private def predicateForInsert(key: A): Predicate[B] = {
    // We can't store to a predicate until its left has been notified of a
    // new successor and its right has been notified of a new predecessor
    val p = predicateForNonInsert(key)

    leftNotify(key, p)
    //rightNotify(key, p)
    return p
  }

  private def leftNotify(key: A, p: Predicate[B]) {
    if (p.leftNotified) return

    val before = predicates.lowerEntry(key)
    if (p.leftNotified) return

    if (null == before) {
      firstInsCount.escaped += 1
    } else {
      leftNotify(before.getKey, before.getValue)
      if (p.leftNotified) return

      if (bargingCount.get > 0) {
        firstInsCount.escaped.get
      }

      before.getValue.succInsCount.escaped += 1
    }
    p.leftNotified = true
  }
//
//  private def rightNotify(key: A, p: Predicate[B]) {
//    if (p.rightNotified) return
//
//    val after = predicates.higherEntry(key)
//    if (p.rightNotified) return
//
//    if (null == after) {
//      lastInsCount.escaped += 1
//    } else {
//      rightNotify(after.getKey, after.getValue)
//      if (p.rightNotified) return
//
//      after.getValue.predInsCount.escaped += 1
//    }
//    p.rightNotified = true
//  }
}
