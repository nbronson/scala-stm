package scala.concurrent.stm.experimental.impl

import scala.concurrent.stm.impl.{Handle, RefOps}
import java.util.concurrent.atomic.AtomicLongFieldUpdater


private object TOptionRef {
  val metaUpdater = (new TOptionRef(None)).newMetaUpdater
}

/** A `Ref` implementation that holds only non-null
 *  `Option` instances.  When compared to
 *  `TAnyRef[Option[T]]` instances, instances of
 *  `TOptionRef` have lower storage overhead (the wrapping
 *  `Option` objects are discarded and recreated as needed), but a
 *  slightly higher runtime cost when accessing.
 *
 *  @author Nathan Bronson
 */
private[ccstm] class TOptionRef[T](initialValue: Option[T]) extends Handle[Option[T]] with RefOps[Option[T]] {
  import TOptionRef._

  private[ccstm] def handle: Handle[Option[T]] = this

  @volatile private[ccstm] var meta: Long = 0L
  private[ccstm] def metaCAS(before: Long, after: Long) = {
    metaUpdater.compareAndSet(this, before, after)
  }
  private[TOptionRef] def newMetaUpdater = {
    AtomicLongFieldUpdater.newUpdater(classOf[TOptionRef[_]], "meta")
  }

  private[ccstm] def ref: AnyRef = this
  private[ccstm] def offset: Int = 0
  private[ccstm] def metaOffset: Int = 0

  @volatile private var _packed = pack(initialValue)
  private[ccstm] def data = unpack(_packed)
  private[ccstm] def data_=(v: Option[T]) { _packed = pack(v) }

  private def unpack(v: AnyRef): Option[T] = {
    if (v eq null) {
      None
    } else {
      Some((if (v eq TOptionRef) null else v).asInstanceOf[T])
    }
  }

  private def pack(o: Option[T]): AnyRef = {
    if (o.isEmpty) {
      null
    } else {
      val v = o.get.asInstanceOf[AnyRef]
      if (null == v) TOptionRef else v
    }
  }
}
