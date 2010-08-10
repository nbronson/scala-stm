/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

object Txn {
  import impl.STMImpl

  /** Returns `Some(t)` if called from inside the static or dynamic scope of
   *  the transaction `t`, `None` otherwise.  If an implicit `Txn` is
   *  available it is used, otherwise a dynamic lookup is performed.
   */
  def current(implicit mt: MaybeTxn): Option[Txn] = Option(currentOrNull)

  /** Equivalent to `current getOrElse null`. */
  def currentOrNull(implicit mt: MaybeTxn): Txn = STMImpl.instance.currentOrNull
}

/** A `Txn` represents one attempt to execute a top-level atomic block. */
trait Txn extends MaybeTxn {

  /** Causes the current transaction to roll back.  It will not be retried
   *  until a write has been performed to some memory location read by this
   *  transaction.  If an alternative to this atomic block was provided via
   *  `orAtomic` or `atomic.oneOf`, then the alternative will be tried.
   */
  def retry(): Nothing
}
