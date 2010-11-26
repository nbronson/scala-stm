/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm
package impl

private[stm] object STMImpl {

  private def instanceClassName: String = System.getProperty("scala.stm.impl", "scala.concurrent.stm.ccstm.CCSTM")

  private def instanceClass = Class.forName(instanceClassName)

  val instance: STMImpl = instanceClass.newInstance.asInstanceOf[STMImpl]
}

/** `STMImpl` gathers all of the functionality required to plug an STM
 *  implementation into `scala.concurrent.stm`.  Set the JVM system
 *  property "scala.stm.impl" to the name of a class that implements
 *  `STMImpl` to use that implementation. 
 *
 *  @author Nathan Bronson
 */
trait STMImpl extends RefFactory with TxnContext with TxnExecutor
