/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA

object atomic {
  import impl.TxnFactory

  def atomic[Z](block: Txn => Z)(implicit mt: MaybeTxn) = TxnFactory.instance.atomic(block)
}