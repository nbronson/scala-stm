/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA

import impl.{STMImpl,TxnExecutor}

object atomic extends TxnExecutor {

  // TODO: customize scaladoc for atomic vs TxnExecutor

  def apply[Z](block: Txn => Z)(implicit mt: MaybeTxn): Z = STMImpl.instance.apply(block)

  def configuration: Map[String,Any] = STMImpl.instance.configuration

  def withConfig(param: (String,Any)): TxnExecutor = STMImpl.instance.withConfig(param)
}