/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// UnlockedNonTxnMap

package scala.concurrent.stm
package experimental
package impl

import skel.TMapViaClone


class UnlockedNonTxnMap[A,B](underlying: java.util.Map[A, AnyRef]) extends TMapViaClone[A,B] {

  //// TMap.View

  override def apply(key: A): B = {
    val v = underlying.get(key)
    if (null eq v) default(key) else NullValue.decode[B](v)
  }

  def get(key: A): Option[B] = {
    NullValue.decodeOption[B](underlying.get(key))
  }

  override def put(key: A, value: B): Option[B] = {
    NullValue.decodeOption[B](underlying.put(key, NullValue.encode(value)))
  }

  def +=(kv: (A, B)) = {
    single.put(kv._1, kv._2)
    this
  }

  override def update(key: A, value: B) {
    underlying.put(key, NullValue.encode(value))
  }

  override def remove(key: A): Option[B] = {
    NullValue.decodeOption[B](underlying.remove(key))
  }

  def -= (key: A) = {
    underlying.remove(key)
    this
  }

  def iterator: Iterator[(A,B)] = {
    NullValue.decodeEntrySetSnapshot(underlying)
  }

  override def get(key: A)(implicit txn: InTxn): Option[B] = throw new UnsupportedOperationException

  override def put(key: A, value: B)(implicit txn: InTxn): Option[B] = throw new UnsupportedOperationException

  override def remove(key: A)(implicit txn: InTxn): Option[B] = throw new UnsupportedOperationException
}
