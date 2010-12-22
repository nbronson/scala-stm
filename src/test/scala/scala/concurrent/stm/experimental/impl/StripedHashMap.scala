/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// StripedHashMap

package scala.concurrent.stm.experimental.impl

import reflect.Manifest
import scala.concurrent.stm.experimental.TMap
import scala.concurrent.stm.experimental.TMap.{Sink, BoundSink}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.stm._


object StripedHashMap {
  def NumStripes = 16
}

class StripedHashMap[K,V](implicit km: Manifest[K], vm: Manifest[V]) extends TMap[K,V] {
  import StripedHashMap._

  private val underlying = Array.tabulate(NumStripes) { _ => new ChainingHashMap[K,V] }

  private def mapFor(k: K) = underlying(k.hashCode & (NumStripes - 1))


  def bind(implicit txn: Txn): TMap.Bound[K,V] = new TMap.AbstractTxnBound[K,V,StripedHashMap[K,V]](txn, this) {
    def iterator: Iterator[(K,V)] = {
      underlying.flatMap({ u => u.bind(txn) }).iterator
    }
  }

  def escaped: TMap.Bound[K,V] = new TMap.AbstractNonTxnBound[K,V,StripedHashMap[K,V]](this) {
    override def get(key: K): Option[V] = mapFor(key).escaped.get(key)

    override def put(key: K, value: V): Option[V] = mapFor(key).escaped.put(key, value)

    override def remove(key: K): Option[V] = mapFor(key).escaped.remove(key)
    
    def iterator: Iterator[(K,V)] = {
      atomic { implicit t =>
        val buf = new ArrayBuffer[(K,V)]
        for (u <- underlying) buf ++= u.escaped
        buf.iterator
      }
    }
  }

  
  def isEmpty(implicit txn: Txn): Boolean = {
    !underlying.exists({ u => !u.isEmpty })
  }

  def size(implicit txn: Txn): Int = {
    var s = 0
    for (u <- underlying) s += u.size
    s
  }

  def get(key: K)(implicit txn: Txn): Option[V] = mapFor(key).get(key)

  def remove(key: K)(implicit txn: Txn): Option[V] = mapFor(key).remove(key)

  def put(key: K, value: V)(implicit txn: Txn): Option[V] = mapFor(key).put(key, value)
}
