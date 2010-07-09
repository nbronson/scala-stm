/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA

object Txn {
  import impl.TxnFactory

  /** Returns `Some(t)` if called from inside the static or dynamic scope of
   *  the transaction `t`, `None` otherwise.  If an implicit `Txn` is
   *  available it is used, otherwise a dynamic lookup is performed.
   */
  def current(implicit mt: MaybeTxn): Option[Txn] = Option(currentOrNull)

  /** Equivalent to `current getOrElse null`. */
  def currentOrNull(implicit mt: MaybeTxn): Txn = TxnFactory.instance.currentOrNull
}

trait Txn extends MaybeTxn