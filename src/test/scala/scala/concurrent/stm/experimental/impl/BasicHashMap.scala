/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// BasicHashMap

package scala.concurrent.stm.experimental.impl

import reflect.Manifest
import scala.concurrent.stm.experimental.TMap
import scala.concurrent.stm.experimental.TMap.Bound
import scala.concurrent.stm._
import collection.TArray
import impl.{LazyConflictRef}
import scala.concurrent.stm.TxnFieldUpdater.Base

class BasicHashMap[K,V](implicit km: Manifest[K], vm: Manifest[V]) extends TMap[K,V] {
  private val bucketsRef = Ref(new TArray[BHMBucket[K,V]](16, TArray.MaximizeParallelism))
  private val sizeRef = new LazyConflictRef(0)

  private def hash(key: K) = {
    // this is the bit mixing code from java.util.HashMap
    var h = key.hashCode
    h ^= (h >>> 20) ^ (h >>> 12)
    h ^= (h >>> 7) ^ (h >>> 4)
    h
  }


  def escaped: Bound[K,V] = new TMap.AbstractNonTxnBound[K,V,BasicHashMap[K,V]](BasicHashMap.this) {

    override def size: Int = sizeRef.escaped.get

    def get(key: K): Option[V] = {
      STM.atomic(unbind.get(key)(_))
    }

    override def put(key: K, value: V): Option[V] = {
      STM.atomic(unbind.put(key, value)(_))
    }

    override def remove(key: K): Option[V] = {
      STM.atomic(unbind.remove(key)(_))
    }

    protected def transformIfDefined(key: K,
                                     pfOrNull: PartialFunction[Option[V],Option[V]],
                                     f: Option[V] => Option[V]): Boolean = {
      STM.atomic(unbind.transformIfDefined(key, pfOrNull, f)(_))
    }

    def iterator: Iterator[Tuple2[K,V]] = {
      throw new UnsupportedOperationException
    }
  }


  def bind(implicit txn0: Txn): Bound[K,V] = new TMap.AbstractTxnBound[K,V,BasicHashMap[K,V]](txn0, BasicHashMap.this) {

    def iterator: Iterator[Tuple2[K,V]] = {
      throw new UnsupportedOperationException
    }
  }

  def isEmpty(implicit txn: Txn): Boolean = sizeRef getWith { _ > 0 }

  def size(implicit txn: Txn): Int = sizeRef.get

  def get(key: K)(implicit txn: Txn): Option[V] = {
    val h = hash(key)
    val buckets = bucketsRef.get
    return get(key, buckets(h & (buckets.length - 1)))
  }

  private def get(key: K, bucket: BHMBucket[K,V])(implicit txn: Txn): Option[V] = {
    if (null == bucket) {
      None
    } else if (key == bucket.key) {
      Some(bucket.value)
    } else {
      get(key, bucket.next)
    }
  }

  def put(key: K, value: V)(implicit txn: Txn): Option[V] = {
    val h = hash(key)
    val buckets = bucketsRef.get
    val i = h & (buckets.length - 1)
    val head = buckets(i)
    val z = putExisting(key, value, head)
    if (!z.isEmpty) {
      // update
      z
    } else {
      // insert change
      buckets(i) = new BHMBucket(key, value, head)
      sizeRef += 1
      val n = buckets.length
      if (sizeRef getWith { _ > n - n/4 }) {
        grow()
      }
      None
    }
  }

  private def putExisting(key: K, value: V, bucket: BHMBucket[K,V])(implicit txn: Txn): Option[V] = {
    if (null == bucket) {
      None
    } else if (key == bucket.key) {
      Some(bucket.valueRef.swap(value))
    } else {
      putExisting(key, value, bucket.next)
    }
  }


  def remove(key: K)(implicit txn: Txn): Option[V] = {
    val h = hash(key)
    val buckets = bucketsRef.get
    val i = h & (buckets.length - 1)
    var prev: BHMBucket[K,V] = null
    var node = buckets(i)
    while (null != node) {
      val next = node.next
      if (node.key == key) {
        // hit
        if (null == prev) {
          buckets(i) = next
        } else {
          prev.next = next
        }
        sizeRef -= 1
        return Some(node.value)
      }
      prev = node
      node = next
    }
    return None
  }

  protected def transformIfDefined(key: K,
                                   pfOrNull: PartialFunction[Option[V],Option[V]],
                                   f: Option[V] => Option[V])(implicit txn: Txn): Boolean = {
    throw new UnsupportedOperationException
  }

  private def grow()(implicit txn: Txn) {
    val before = bucketsRef.bind.readForWrite

    val after = new TArray[BHMBucket[K,V]](before.length * 2, TArray.MaximizeParallelism)
    val aMask = after.length - 1
    for (bi <- 0 until before.length) {
      var bucket = before(bi)
      while (null != bucket) {
        val next = bucket.next
        val ai = hash(bucket.key) & aMask
        bucket.next = after(ai)
        after(ai) = bucket
        bucket = next
      }
    }

    bucketsRef() = after
  }
}

private class BHMBucket[A,B](val key: A, value0: B, next0: BHMBucket[A,B]) extends Base {
  import BHMBucket._

  @volatile private var _value: B = value0
  @volatile private var _next: BHMBucket[A,B] = next0

  def value(implicit txn: Txn): B = valueRef.get
  def value_=(v: B)(implicit txn: Txn) { valueRef.set(v) }
  def valueRef = Value(this)

  def next(implicit txn: Txn): BHMBucket[A,B] = nextRef.get
  def next_=(v: BHMBucket[A,B])(implicit txn: Txn) { nextRef.set(v) }
  def nextRef = Next(this)
}

private object BHMBucket {
  val Value = new TxnFieldUpdater.Generic[BHMBucket[_,_]]("value") {
    type Instance[X] = BHMBucket[_,X]
    type Value[X] = X
    protected def getField[B](instance: BHMBucket[_,B]) = instance._value
    protected def setField[B](instance: BHMBucket[_,B], v: B) { instance._value = v }
  }

  val Next = new TxnFieldUpdater.Generic2[BHMBucket[_,_]]("next") {
    type Instance[X,Y] = BHMBucket[X,Y]
    type Value[X,Y] = BHMBucket[X,Y]
    protected def getField[A,B](instance: BHMBucket[A,B]) = instance._next
    protected def setField[A,B](instance: BHMBucket[A,B], v: BHMBucket[A,B]) { instance._next = v }
  }
}
