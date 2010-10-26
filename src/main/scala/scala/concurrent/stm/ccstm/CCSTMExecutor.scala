/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ccstm

import scala.util.control.ControlThrowable

private[ccstm] object CCSTMExecutor {
  val DefaultControlFlowTest = { x: Throwable => x.isInstanceOf[ControlThrowable] }

  val DefaultPostDecisionFailureHandler = { (status: Txn.Status, x: Throwable) =>
    new Exception("status=" + status, x).printStackTrace()
  }
}

private[ccstm] class CCSTMExecutor(val controlFlowTest: Throwable => Boolean,
                                   val postDecisionFailureHandler: (Txn.Status, Throwable) => Unit) extends TxnExecutor {

  def this() = this(CCSTMExecutor.DefaultControlFlowTest, CCSTMExecutor.DefaultPostDecisionFailureHandler)

  def runAtomically[Z](block: InTxn => Z)(implicit mt: MaybeTxn): Z = InTxnImpl().atomic(this, block)

  override def oneOf[Z](blocks: (InTxn => Z)*)(implicit mt: MaybeTxn): Z = InTxnImpl().atomicOneOf(this, blocks)

  def pushAlternative[Z](mt: MaybeTxn, block: (InTxn) => Z): Boolean = InTxnImpl().pushAlternative(block)

  // CCSTM has no configuration at the moment
  def configuration: Map[Symbol, Any] = Map.empty

  def withConfig(p: (Symbol, Any)): TxnExecutor = throw new IllegalArgumentException

  def isControlFlow(x: Throwable): Boolean = controlFlowTest(x)
  
  def withControlFlowRecognizer(pf: PartialFunction[Throwable, Boolean]): TxnExecutor = {
    val chained = { x: Throwable => if (pf.isDefinedAt(x)) pf(x) else controlFlowTest(x) }
    new CCSTMExecutor(chained, postDecisionFailureHandler)
  }

  def withPostDecisionFailureHandler(handler: (Txn.Status, Throwable) => Unit): TxnExecutor =
      new CCSTMExecutor(controlFlowTest, handler)
}
