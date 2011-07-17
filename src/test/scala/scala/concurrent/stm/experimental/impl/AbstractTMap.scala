package scala.concurrent.stm
package experimental.impl

import concurrent.stm.skel.TMapViaClone
import java.lang.UnsupportedOperationException

abstract class AbstractTMap[A, B] extends TMapViaClone[A, B] {

  def nonTxnGet(key: A): Option[B]
  def nonTxnPut(key: A, value: B): Option[B]
  def nonTxnRemove(key: A): Option[B]

  //// TMap.View stuff

  override def get(key: A): Option[B] = impl.STMImpl.instance.dynCurrentOrNull match {
    case null => nonTxnGet(key)
    case txn => tmap.get(key)(txn)
  }

  override def put(key: A, value: B): Option[B] = impl.STMImpl.instance.dynCurrentOrNull match {
    case null => nonTxnPut(key, value)
    case txn => tmap.put(key, value)(txn)
  }
  override def += (kv: (A, B)) = { single.put(kv._1, kv._2) ; this }
  override def update(key: A, value: B) { single.put(key, value) }

  override def remove(key: A): Option[B] = impl.STMImpl.instance.dynCurrentOrNull match {
    case null => nonTxnRemove(key)
    case txn => tmap.remove(key)(txn)
  }
  override def -=(key: A) = { single.remove(key) ; this }

  override def iterator: Iterator[(A,B)] = throw new UnsupportedOperationException

  //// TMap stuff

  override def isEmpty(implicit txn: InTxn) = throw new UnsupportedOperationException
  override def size(implicit txn: InTxn) = throw new UnsupportedOperationException
  override def foreach[U](f: ((A, B)) => U)(implicit txn: InTxn) = throw new UnsupportedOperationException

  override def apply(key: A)(implicit txn: InTxn) = tmap.get(key).get
  override def contains(key: A)(implicit txn: InTxn) = !tmap.get(key).isEmpty

  override def += (kv: (A, B))(implicit txn: InTxn) = { tmap.put(kv._1, kv._2) ; this }
  override def update(k: A, v: B)(implicit txn: InTxn) = tmap.put(k, v)

  override def -= (k: A)(implicit txn: InTxn) = { tmap.remove(k) ; this }

  override def transform(f: (A, B) => B)(implicit txn: InTxn) = throw new UnsupportedOperationException
  override def retain(p: (A, B) => Boolean)(implicit txn: InTxn) = throw new UnsupportedOperationException
}