/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// ChainingHashMap

package scala.concurrent.stm.experimental.impl

import reflect.Manifest
import scala.concurrent.stm.experimental.TMap
import scala.concurrent.stm.experimental.TMap.Bound
import scala.concurrent.stm._
import collection.TArray
import impl.LazyConflictRef

object ChainingHashMap {
  private class Bucket[K,V](val hash: Int, val key: K, val value: V, val next: Bucket[K,V]) {
    def find(h: Int, k: K): Bucket[K,V] = {
      if (hash == h && key == k) {
        this
      } else if (null == next) {
        null
      } else {
        next.find(h, k)
      }
    }

    def remove(b: Bucket[K,V]): Bucket[K,V] = {
      if (this eq b) {
        next
      } else {
        new Bucket(hash, key, value, next.remove(b))
      }
    }
  }
}

class ChainingHashMap[K,V](implicit km: Manifest[K], vm: Manifest[V]) extends TMap[K,V] {
  import ChainingHashMap._

  private val bucketsRef = Ref(TArray[Bucket[K,V]](16))
  private val sizeRef = new LazyConflictRef(0)

  private def hash(key: K) = {
    // this is the bit mixing code from java.util.HashMap
    var h = key.hashCode
    h ^= (h >>> 20) ^ (h >>> 12)
    h ^= (h >>> 7) ^ (h >>> 4)
    h
  }


  def escaped: Bound[K,V] = new TMap.AbstractNonTxnBound[K,V,ChainingHashMap[K,V]](ChainingHashMap.this) {

    override def size: Int = sizeRef.escaped.get

    // This uses unrecordedRead, a CCSTM special op.
//    def get(key: K): Option[V] = {
//      // attempt an ad-hoc txn first
//      val h = hash(key)
//      val unrecorded = bucketsRef.escaped.unrecordedRead
//      val buckets = unrecorded.value
//      val head = buckets.escaped(h & (buckets.length - 1))
//      if (unrecorded.stillValid) {
//        // coherent read of bucketsRef and buckets(i)
//        val bucket = if (null == head) null else head.find(h, key)
//        if (null == bucket) None else Some(bucket.value)
//      } else {
//        // fall back to a txn
//        STM.atomic(unbind.get(key)(_))
//      }
//    }

    // This hand-coded optimistic implementation should work on any Java STM.
    def get(key: K): Option[V] = {
      // attempt an ad-hoc txn first
      val h = hash(key)
      val buckets = bucketsRef.escaped.get
      val head = buckets.escaped(h & (buckets.length - 1))
      if (bucketsRef.escaped.get eq buckets) {
        // coherent read of bucketsRef and buckets(i)
        val bucket = if (null == head) null else head.find(h, key)
        if (null == bucket) None else Some(bucket.value)
      } else {
        // fall back to a txn
        STM.atomic(unbind.get(key)(_))
      }
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
      val snap = STM.atomic(t => bucketsRef.get(t).bind(t).toArray)
      return new Iterator[Tuple2[K,V]] {
        var i = 0
        var avail: Bucket[K,V] = null
        advance()

        private def advance() {
          if (null != avail) {
            avail = avail.next
          }
          while (null == avail && i < snap.length) {
            avail = snap(i)
            i += 1
          }
        }

        def hasNext = null != avail
        def next(): (K,V) = {
          val z = (avail.key, avail.value)
          advance()
          z
        }
      }
    }
  }


  def bind(implicit txn0: Txn): Bound[K,V] = new TMap.AbstractTxnBound[K,V,ChainingHashMap[K,V]](txn0, ChainingHashMap.this) {

    def iterator: Iterator[Tuple2[K,V]] = {
      val buckets = bucketsRef.get
      return new Iterator[Tuple2[K,V]] {
        var i = 0
        var avail: Bucket[K,V] = null
        advance()

        private def advance() {
          if (null != avail) {
            avail = avail.next
          }
          while (null == avail && i < buckets.length) {
            avail = buckets(i)
            i += 1
          }
        }

        def hasNext = null != avail
        def next(): (K,V) = {
          val z = (avail.key, avail.value)
          advance()
          z
        }
      }
    }
  }

  def isEmpty(implicit txn: Txn): Boolean = sizeRef getWith { _ > 0 }

  def size(implicit txn: Txn): Int = sizeRef.get

  def get(key: K)(implicit txn: Txn): Option[V] = {
    val h = hash(key)
    val buckets = bucketsRef.get
    val i = h & (buckets.length - 1)
    val head = buckets(i)
    val bucket = if (null == head) null else head.find(h, key)
    if (null == bucket) None else Some(bucket.value)
  }

  def put(key: K, value: V)(implicit txn: Txn): Option[V] = {
    val h = hash(key)
    val buckets = bucketsRef.get
    val i = h & (buckets.length - 1)
    val head = buckets(i)
    val bucket = if (null == head) null else head.find(h, key)
    val newHead = if (null == bucket) head else head.remove(bucket)
    buckets(i) = new Bucket(h, key, value, newHead)
    if (null == bucket) {
      // size change
      sizeRef += 1
      val n = buckets.length
      if (sizeRef getWith { _ > n - n/4 }) {
        grow()
      }
      None
    } else {
      Some(bucket.value)
    }
  }

  def remove(key: K)(implicit txn: Txn): Option[V] = {
    val h = hash(key)
    val buckets = bucketsRef.get
    val i = h & (buckets.length - 1)
    val head = buckets(i)
    val bucket = if (null == head) null else head.find(h, key)
    if (null != bucket) {
      sizeRef -= 1
      buckets(i) = head.remove(bucket)
      Some(bucket.value)
    } else {
      None
    }
  }

  protected def transformIfDefined(key: K,
                                   pfOrNull: PartialFunction[Option[V],Option[V]],
                                   f: Option[V] => Option[V])(implicit txn: Txn): Boolean = {
    val h = hash(key)
    val buckets = bucketsRef.get
    val i = h & (buckets.length - 1)
    var head = buckets(i)
    val bucket = if (null == head) null else head.find(h, key)
    val b = if (null == bucket) None else Some(bucket.value)
    if (null != pfOrNull && !pfOrNull.isDefinedAt(b)) {
      return false
    }

    val after = f(b)
    if (null == bucket && after.isEmpty) {
      // nothing to do, but we were definedAt
      return true
    }

    val withoutOld = if (null == bucket) head else head.remove(bucket)
    val withNew = if (after.isEmpty) withoutOld else new Bucket(h, key, after.get, withoutOld)
    buckets(i) = withNew

    if (after.isEmpty) {
      // smaller
      sizeRef -= 1
    } else if (null == bucket) {
      // bigger
      sizeRef += 1
      val n = buckets.length
      if (sizeRef getWith { _ > n - n/4 }) {
        grow()
      }
    }

    return true
  }

  private def grow()(implicit txn: Txn) {
    val before = bucketsRef.bind.readForWrite

    // rehash into a regular array first
    val an = before.length * 2
    val after = new Array[Bucket[K,V]](an)
    var bi = 0
    while (bi < before.length) {
      var head = before(bi)
      while (null != head) {
        val ai = head.hash & (an - 1)
        after(ai) = new Bucket(head.hash, head.key, head.value, after(ai))
        head = head.next
      }
      bi += 1
    }

    // now create the transactional array, giving ourself up to 512 metadata
    // locations, with neighboring array elements getting different metadata
    // mappings
    bucketsRef() = TArray(after, TArray.Striped(512))
  }
}
