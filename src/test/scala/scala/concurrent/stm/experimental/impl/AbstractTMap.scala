package scala.concurrent.stm
package experimental.impl

import concurrent.stm.skel.TMapViaClone

abstract class AbstractTMap[A, B] extends TMapViaClone[A, B] {

  def nonTxnGet(key: A): Option[B]
  def nonTxnPut(key: A, value: B): Option[B]
  def nonTxnRemove(key: A): Option[B]

  //// TMap.View stuff

  def get(key: A): Option[B] = impl.STMImpl.instance.dynCurrentOrNull match {
    case null => nonTxnGet(key)
    case txn => tmap.get(key)(txn)
  }

  override def put(key: A, value: B): Option[B] = impl.STMImpl.instance.dynCurrentOrNull match {
    case null => nonTxnPut(key, value)
    case txn => tmap.put(key, value)(txn)
  }
  def += (kv: (A, B)) = { single.put(kv._1, kv._2) ; this }
  override def update(key: A, value: B) { single.put(key, value) }

  override def remove(key: A): Option[B] = impl.STMImpl.instance.dynCurrentOrNull match {
    case null => nonTxnRemove(key)
    case txn => tmap.remove(key)(txn)
  }
  def -=(key: A) = { single.remove(key) ; this }

  def iterator = throw new UnsupportedOperationException

  //// TMap stuff

  override def += (kv: (A, B))(implicit txn: InTxn) = { tmap.put(kv._1, kv._2) ; this }
  override def update(k: A, v: B)(implicit txn: InTxn) = { tmap.put(k, v) }

  override def -= (k: A)(implicit txn: InTxn) = { tmap.remove(k) ; this }
}