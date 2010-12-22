/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// TMap2

package scala.concurrent.stm
package experimental
package impl

object TMap2 {
  trait Source[A,+B] extends TMap.Source[A,B] {
    def single: TMap2.SourceView[A,B]
    def escaped: TMap2.SourceView[A,B]
    def bind(implicit txn: Txn): TMap2.SourceView[A,B]

    def isEmpty(implicit txn: Txn): Boolean
    def size(implicit txn: Txn): Int

    override def apply(key: A)(implicit txn: Txn): B = {
      get(key) match {
        case Some(v) => v
        case None => default(key)
      }
    }
    def get(key: A)(implicit txn: Txn): Option[B]

    override def default(key: A): B = {
      throw new NoSuchElementException(key + ": is not present")
    }
  }

  trait Sink[A,-B] extends TMap.Sink[A,B] {
    def single: TMap2.SinkView[A,B]
    def escaped: TMap2.SinkView[A,B]
    def bind(implicit txn: Txn): TMap2.SinkView[A,B]

    def update(key: A, value: B)(implicit txn: Txn)
    def -=(key: A)(implicit txn: Txn)
  }

  trait SourceView[A,+B] extends scala.collection.Map[A,B] with TMap.BoundSource[A,B] {
    def unbind: TMap2.Source[A,B]
    def mode: AccessMode

    override def default(key: A): B = unbind.default(key)
  }

  trait SinkView[A,-B] extends TMap.BoundSink[A,B] {
    def unbind: TMap2.Sink[A,B]
    def mode: AccessMode

    def update(key: A, value: B)
    def -= (key: A): this.type
  }

  trait View[A,B] extends SourceView[A,B] with SinkView[A,B] with scala.collection.mutable.Map[A,B] with TMap.Bound[A,B] {
    def unbind: TMap2[A,B]

    override def += (kv: (A, B)) = { update(kv._1, kv._2); this }
  }

  class SingleView[A,B,M <: TMap2[A,B]](val unbind: M) extends View[A,B] {
    def mode = Single
    def context: Option[Txn] = Txn.current

    private def dynView = Txn.dynCurrentOrNull match {
      case null => unbind.escaped
      case txn => unbind.bind(txn)
    }

    override def isEmpty: Boolean = dynView.isEmpty
    override def size: Int = dynView.size

    override def apply(key: A): B = dynView.apply(key)
    def get(key: A): Option[B] = dynView.get(key)

    override def put(key: A, value: B): Option[B] = dynView.put(key, value)
    override def update(key: A, value: B) { dynView.update(key, value) }

    override def remove(key: A): Option[B] = dynView.remove(key)
    def -=(key: A) = { dynView.-=(key); this }

    def iterator = dynView.iterator
    override def keysIterator = dynView.keysIterator
    override def valuesIterator = dynView.valuesIterator
  }

  abstract class AbstractEscapedView[A,B,M <: TMap2[A,B]](val unbind: M) extends View[A,B] {
    def mode = Escaped
    def context: Option[Txn] = None

    override def isEmpty: Boolean = !iterator.hasNext

    // we implement in terms of put and remove, instead of in terms of += and -=
    override def update(key: A, value: B) { put(key, value) }
    def -= (key: A) = { remove(key); this }
  }

  abstract class AbstractTxnView[A,B,M <: TMap2[A,B]](val txn: Txn, val unbind: M) extends View[A,B] {
    def mode = txn
    def context: Option[Txn] = Some(txn)

    override def isEmpty: Boolean = unbind.isEmpty(txn)
    override def size: Int = unbind.size(txn)

    override def apply(key: A): B = unbind.apply(key)(txn)
    def get(key: A): Option[B] = unbind.get(key)(txn)

    override def put(key: A, value: B): Option[B] = unbind.put(key, value)(txn)
    override def update(key: A, value: B) { unbind.update(key, value)(txn) }

    override def remove(key: A): Option[B] = unbind.remove(key)(txn)
    def -=(key: A) = { unbind.-=(key)(txn); this }
  }
}

/** An interface for transactional maps. */
trait TMap2[A,B] extends TMap2.Source[A,B] with TMap2.Sink[A,B] with TMap[A,B] {

  def escaped: TMap2.View[A,B]
  def single: TMap2.View[A,B] = new TMap2.SingleView(this)
  def bind(implicit txn: Txn): TMap2.View[A,B]

  def put(key: A, value: B)(implicit txn: Txn): Option[B]
  def remove(key: A)(implicit txn: Txn): Option[B]

  //////////////// default implementations

  override def update(key: A, value: B)(implicit txn: Txn) { put(key, value) }
  override def -= (key: A)(implicit txn: Txn) { remove(key) }
}
