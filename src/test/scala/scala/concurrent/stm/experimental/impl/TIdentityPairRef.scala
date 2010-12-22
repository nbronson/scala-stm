package scala.concurrent.stm.experimental.impl

import java.util.concurrent.atomic.AtomicLongFieldUpdater
import scala.concurrent.stm.impl.{Handle, RefOps}


private object TIdentityPairRef {
  val metaUpdater = (new TIdentityPairRef(IdentityPair("",""))).newMetaUpdater
}

/** A `Ref` implementation that holds either null or a
 *  `IdentityPair` instance with a non-null element.  When compared
 *  to `TAnyRef[IdentityPair[A,B]]` instances, instances of
 *  `TIdentityPairRef` have lower storage overhead (the wrapping
 *  `IdentityPair` objects are discarded and recreated as needed),
 *  but a slightly higher runtime cost when accessing.
 *  <p>
 *  When storing, `IdentityPair(null,null)` will be silently
 *  converted to `null`.
 *
 *  @author Nathan Bronson
 */
private[ccstm] class TIdentityPairRef[A,B](initialA: A, initialB: B
        ) extends Handle[IdentityPair[A,B]] with RefOps[IdentityPair[A,B]] {

  def this(initialPair: IdentityPair[A,B]) = this(
      (if (null == initialPair) null.asInstanceOf[A] else initialPair._1),
      (if (null == initialPair) null.asInstanceOf[B] else initialPair._2))

  import TIdentityPairRef._

  private[ccstm] def handle: Handle[IdentityPair[A,B]] = this

  @volatile private[ccstm] var meta: Long = 0L
  private[ccstm] def metaCAS(before: Long, after: Long) = {
    metaUpdater.compareAndSet(this, before, after)
  }
  private[TIdentityPairRef] def newMetaUpdater = {
    AtomicLongFieldUpdater.newUpdater(classOf[TIdentityPairRef[_,_]], "meta")
  }

  private[ccstm] def ref: AnyRef = this
  private[ccstm] def offset: Int = 0
  private[ccstm] def metaOffset: Int = 0

  @volatile private var _first = initialA
  @volatile private var _second = initialB
  private[ccstm] def data = {
    if (null == _first && null == _second) {
      null
    } else {
      IdentityPair(_first, _second)
    }
  }
  private[ccstm] def data_=(v: IdentityPair[A,B]) {
    if (null == v) {
      _first = null.asInstanceOf[A]
      _second = null.asInstanceOf[B]
    } else {
      _first = v._1
      _second = v._2
    }
  }
}
