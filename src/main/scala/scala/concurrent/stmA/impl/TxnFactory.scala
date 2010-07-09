/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA
package impl

object TxnFactory {
  var instance: TxnFactory = null
}

trait TxnFactory {
  def currentOrNull(implicit mt: MaybeTxn): Txn
  def atomic[Z](block: Txn => Z): Z
}
