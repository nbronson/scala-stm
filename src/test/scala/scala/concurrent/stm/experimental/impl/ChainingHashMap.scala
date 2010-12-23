/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// ChainingHashMap

package scala.concurrent.stm
package experimental
package impl

import reflect.ClassManifest
import skel.TMapViaClone

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

class ChainingHashMap[K, V](implicit km: ClassManifest[K], vm: ClassManifest[V]) extends TMapViaClone[K, V] {
  import ChainingHashMap._

  private val bucketsRef = Ref(TArray.ofDim[Bucket[K,V]](16))
  private val sizeRef = Ref(0)

  private def hash(key: K) = {
    // this is the bit mixing code from java.util.HashMap
    var h = key.hashCode
    h ^= (h >>> 20) ^ (h >>> 12)
    h ^= (h >>> 7) ^ (h >>> 4)
    h
  }

  // TMap.View stuff

  override def size: Int = sizeRef.single.get

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
    val buckets = bucketsRef.single.get
    val head = buckets.single(h & (buckets.length - 1))
    if (bucketsRef.single.get eq buckets) {
      // coherent read of bucketsRef and buckets(i)
      val bucket = if (null == head) null else head.find(h, key)
      if (null == bucket) None else Some(bucket.value)
    } else {
      // fall back to a txn
      atomic { implicit txn => tmap.get(key) }
    }
  }

  override def put(key: K, value: V): Option[V] = atomic { implicit txn => tmap.put(key, value) }
  def += (kv: (K, V)) = { single.put(kv._1, kv._2) ; this }

  override def remove(key: K): Option[V] = atomic { implicit txn => tmap.remove(key) }
  def -= (key: K) = { single.remove(key) ; this }

  def iterator = throw new UnsupportedOperationException

  //// TMap stuff

  override def get(key: K)(implicit txn: InTxn): Option[V] = {
    val h = hash(key)
    val buckets = bucketsRef.get
    val i = h & (buckets.length - 1)
    val head = buckets(i)
    val bucket = if (null == head) null else head.find(h, key)
    if (null == bucket) None else Some(bucket.value)
  }

  override def put(key: K, value: V)(implicit txn: InTxn): Option[V] = {
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

  override def remove(key: K)(implicit txn: InTxn): Option[V] = {
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

  private def grow()(implicit txn: InTxn) {
    val before = bucketsRef.swap(null)

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

    bucketsRef() = TArray(after)
  }
}
