/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package impl

object STMImpl {
  var instance: STMImpl = null
}

trait STMImpl extends RefFactory with TxnContext with TxnExecutor
