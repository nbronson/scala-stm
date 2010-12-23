/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// StripedHashMap

package scala.concurrent.stm
package experimental
package impl

import reflect.ClassManifest
import skel.TMapViaClone


class StripedHashMap[K,V](implicit km: ClassManifest[K], vm: ClassManifest[V]) extends TMapViaClone[K,V] {

  def NumStripes = 16

  private val underlying = Array.tabulate(NumStripes) { _ => new ChainingHashMap[K,V] }

  private def mapFor(k: K) = underlying(k.## & (NumStripes - 1))

  // TMap.View stuff

  override def get(key: K): Option[V] = mapFor(key).single.get(key)

  override def put(key: K, value: V): Option[V] = mapFor(key).single.put(key, value)
  def += (kv: (K, V)) = { single.put(kv._1, kv._2) ; this }

  override def remove(key: K): Option[V] = mapFor(key).single.remove(key)
  def -= (key: K) = { single.remove(key) ; this }

  def iterator = throw new UnsupportedOperationException

  // TMap stuff

  override def get(key: K)(implicit txn: InTxn): Option[V] = mapFor(key).tmap.get(key)

  override def put(key: K, value: V)(implicit txn: InTxn): Option[V] = mapFor(key).tmap.put(key, value)

  override def remove(key: K)(implicit txn: InTxn): Option[V] = mapFor(key).tmap.remove(key)
}
