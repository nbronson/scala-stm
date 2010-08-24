/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package impl

private[stm] object STMImpl {

  private def instanceClassName: String = System.getProperty("scala.stm.impl", "scala.concurrent.stm.ri.StubSTMImpl")

  private def instanceClass = Class.forName(instanceClassName)

  val instance: STMImpl = instanceClass.newInstance.asInstanceOf[STMImpl]
}

trait STMImpl extends RefFactory with TxnContext with TxnExecutor
