/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// SkipListMap

package scala.concurrent.stm.experimental.impl


import scala.concurrent.stm._
import collection.TArray
import experimental.TMap
import experimental.TMap.Bound
import impl.{TAnyRef, FastSimpleRandom}
import reflect.Manifest


class SkipListMap[A,B](implicit aMan: Manifest[A], bMan: Manifest[B]) extends TMap[A,B] {

  //////////////// TMap stuff

  def isEmpty(implicit txn: Txn): Boolean = (null != head.get.links(0))

  def size(implicit txn: Txn): Int = {
    var s = 0
    val iter = bind.iterator
    while (iter.hasNext) { s += 1; iter.next() }
    s
  }

  protected def transformIfDefined(key: A,
                                   pfOrNull: PartialFunction[Option[B],Option[B]],
                                   f: Option[B] => Option[B])(implicit txn: Txn): Boolean = {
    val v0 = get(key)
    if (null != pfOrNull && !pfOrNull.isDefinedAt(v0)) {
      false
    } else {
      f(v0) match {
        case Some(v) => put(key, v)
        case None => remove(key)
      }
      false
    }
  }


  def bind(implicit txn0: Txn): Bound[A, B] = new TMap.AbstractTxnBound[A,B,SkipListMap[A,B]](txn0, this) {
    def iterator: Iterator[(A,B)] = new Iterator[(A,B)] {
      var avail = unbind.head.get.links(0)

      def hasNext = null != avail
      def next() = {
        val z = (avail.key, avail.value)
        avail = avail.links(0)
        z
      }
    }
  }

  def escaped: Bound[A,B] = new TMap.AbstractNonTxnBound[A,B,SkipListMap[A,B]](this) {

    override def isEmpty = null != unbind.head.escaped.get.links.escaped(0)
    override def size = STM.atomic(unbind.size(_))

    def get(key: A): Option[B] = STM.atomic(unbind.get(key)(_))
    override def put(key: A, value: B): Option[B] = STM.atomic(unbind.put(key, value)(_))
    override def remove(key: A): Option[B] = STM.atomic(unbind.remove(key)(_))

    protected def transformIfDefined(key: A,
                                     pfOrNull: PartialFunction[Option[B],Option[B]],
                                     f: Option[B] => Option[B]): Boolean = {
      STM.atomic(unbind.transformIfDefined(key, pfOrNull, f)(_))
    }

    def iterator: Iterator[Tuple2[A,B]] = {
      STM.atomic(unbind.bind(_).toArray).iterator
    }
  }



  //////////////// internal state

  private val head = Ref(newHead)

  private def newHead = new SLNode(null.asInstanceOf[A], null.asInstanceOf[B], 1)

  def clear()(implicit txn: Txn) {
    head() = newHead
  }

  def containsKey(key: A)(implicit txn: Txn) = null != getNode(key)

  private def getNode(key: A)(implicit txn: Txn): SLNode[A,B] = {
    val n = head.get
    n.findInTail(key, n.links.length - 1)
  }

  def get(key: A)(implicit txn: Txn): Option[B] = {
    getNode(key) match {
      case null => None
      case n => Some(n.value)
    }
  }

  def put(key: A, value: B)(implicit txn: Txn): Option[B] = {
    if (null == key) throw new NullPointerException

    val n = head.get
    val preds = new Array[SLNode[A,B]](n.links.length)
    val hit = n.findInTailForPut(key, n.links.length - 1, preds)
    if (null != hit) {
      return Some(hit.swapValue(value))
    }
    else {
      // Miss.  Height has 50% chance of 1, 25% of 2, ...
      val newH = 1 + java.lang.Integer.lowestOneBit(FastSimpleRandom.nextInt() >>> 1)
      val newNode = new SLNode[A,B](key, value, newH)

      // Link new node after predecessor at each level
      var i = math.min(newH, preds.length) - 1
      while (i >= 0) {
        newNode.links(i) = preds(i).links(i)
        preds(i).links(i) = newNode
        i -= 1
      }

      // Expand the root to match the height, if necessary
      if (n.height < newH) {
        val repl = new SLNode(n.key, n.value, newH)
        var i = newH - 1
        while (i >= n.height) {
          repl.links(i) = newNode
          i -= 1
        }
        while (i >= 0) {
          repl.links(i) = n.links(i)
          i -= 1
        }
        head.set(repl)
      }

      return None
    }
  }

  def remove(key: A)(implicit txn: Txn): Option[B] = {
    val n = head.get
    val preds = new Array[SLNode[A,B]](n.links.length)
    val hit = n.findInTailForRemove(key, n.links.length - 1, preds)
    if (null == hit) {
      return None
    } else {
      var i = hit.height - 1
      while (i >= 0) {
        preds(i).links(i) = hit.links(i)
        i -= 1
      }
      return Some(hit.value)
    }
  }
}


private class SLNode[A,B](val key: A, value0: B, height0: Int
        )(implicit aMan: Manifest[A], bMan: Manifest[B]) extends TAnyRef[B](value0) {
  val links = new TArray[SLNode[A,B]](height0)
  def height = links.length

  def value(implicit txn: Txn): B = this.get
  def swapValue(v: B)(implicit txn: Txn): B = this.swap(v)

  def findInTail(key: A, h: Int)(implicit txn: Txn): SLNode[A,B] = {
    var i = h
    while (i >= 0) {
      val next = links(i)
      if (null != next) {
        val c = key.asInstanceOf[Comparable[A]].compareTo(next.key)
        if (c == 0) {
          // direct hit
          return next
        } else if (c > 0) {
          // key is in tail of next
          return next.findInTail(key, i)
        }
      }
      i -= 1
    }
    return null
  }

  def findInTailForPut(key: A, h: Int, preds: Array[SLNode[A,B]])(implicit txn: Txn): SLNode[A,B] = {
    var i = h
    while (i >= 0) {
      val next = links(i)
      if (null != next) {
        val c = key.asInstanceOf[Comparable[A]].compareTo(next.key)
        if (c == 0) {
          // direct hit
          return next
        } else if (c > 0) {
          // key is in tail of next
          return next.findInTailForPut(key, i, preds)
        }
      }
      // this node is the last one at height i
      preds(i) = this
      i -= 1
    }
    return null
  }

  def findInTailForRemove(key: A, h: Int, preds: Array[SLNode[A,B]])(implicit txn: Txn): SLNode[A,B] = {
    var i = h
    while (i >= 0) {
      val next = links(i)
      if (null != next) {
        val c = key.asInstanceOf[Comparable[A]].compareTo(next.key)
        if (i == 0 && c == 0) {
          // preds must be filled in completely
          preds(0) = this
          return next
        } else if (c > 0) {
          // key is in tail of next
          return next.findInTailForRemove(key, i, preds)
        }
      }
      // this node is the last one at height i
      preds(i) = this
      i -= 1
    }
    return null
  }
}
