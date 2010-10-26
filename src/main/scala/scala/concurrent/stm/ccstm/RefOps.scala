/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ccstm

/** The default implementation of `Ref`'s operations in CCSTM. */
private[ccstm] trait RefOps[T] extends Ref[T] with Handle.Provider[T] {

  private def impl(implicit txn: InTxn): InTxnImpl = txn.asInstanceOf[InTxnImpl]

  /** Override this to provide the handle that this `RefOps` uses. */
  def handle: Handle[T]

  //////////////// Source stuff

  def get(implicit txn: InTxn): T = impl.get(handle)
  def getWith[Z](f: (T) => Z)(implicit txn: InTxn): Z = impl.getWith(handle, f)
  def relaxedGet(equiv: (T, T) => Boolean)(implicit txn: InTxn): T = impl.relaxedGet(handle, equiv)

  //////////////// Sink stuff

  def set(v: T)(implicit txn: InTxn) { impl.set(handle, v) }

  //////////////// Ref stuff

  def swap(v: T)(implicit txn: InTxn): T = impl.swap(handle, v)

  def transform(f: T => T)(implicit txn: InTxn) {
    // only sub-types of Ref actually perform deferral, the base implementation
    // evaluates f immediately
    impl.getAndTransform(handle, f)
  }

  def transformIfDefined(pf: PartialFunction[T,T])(implicit txn: InTxn): Boolean = {
    impl.transformIfDefined(handle, pf)
  }

  override def hashCode: Int = {
    val h = handle
    CCSTM.hash(h.ref, h.offset)
  }

  override def equals(rhs: Any): Boolean = {
    (this eq rhs.asInstanceOf[AnyRef]) || (rhs match {
      case r: RefOps[_] => {
        val h1 = handle
        val h2 = r.handle
        (h1.ref eq h2.ref) && (h1.offset == h2.offset)
      }
      case r: Ref[_] => {
        // give the rhs the opportunity to compare itself to us
        r equals this
      }
      case _ => false
    })
  }
}
