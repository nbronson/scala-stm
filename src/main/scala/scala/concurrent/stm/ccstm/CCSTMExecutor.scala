/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ccstm

import scala.util.control.ControlThrowable

object CCSTMExecutor {
  val DefaultControlFlowTest = new PartialFunction[Throwable, Boolean] {
    def isDefinedAt(x: Throwable): Boolean = true
    def apply(x: Throwable): Boolean = x.isInstanceOf[ControlThrowable]
  }

  val DefaultPostDecisionFailureHandler = { (status: Txn.Status, x: Throwable) =>
    new Exception("status=" + status, x).printStackTrace()
  }
}

class CCSTMExecutor(val controlFlowTest: PartialFunction[Throwable, Boolean],
                    val postDecisionFailureHandler: (Txn.Status, Throwable) => Unit) extends TxnExecutor {

  def this() = this(CCSTMExecutor.DefaultControlFlowTest, CCSTMExecutor.DefaultPostDecisionFailureHandler)

  def runAtomically[Z](block: InTxn => Z)(implicit mt: MaybeTxn): Z =
      InTxnImpl().atomic(block)

  override def oneOf[Z](blocks: (InTxn => Z)*)(implicit mt: MaybeTxn): Z =
      InTxnImpl().atomicOneOf(blocks)

  def pushAlternative[Z](mt: MaybeTxn, block: (InTxn) => Z): Boolean =
      InTxnImpl().pushAlternative(block)

  // no configuration is possible
  def configuration: Map[Symbol, Any] = Map.empty
  def withConfig(p: (Symbol, Any)): TxnExecutor = throw new IllegalArgumentException

  def isControlFlow(x: Throwable): Boolean = controlFlowTest(x) // safe because default accepts everything
  
  def withControlFlowRecognizer(pf: PartialFunction[Throwable, Boolean]): TxnExecutor =
      new CCSTMExecutor(pf orElse controlFlowTest, postDecisionFailureHandler)

  def withPostDecisionFailureHandler(handler: (Txn.Status, Throwable) => Unit): TxnExecutor =
      new CCSTMExecutor(controlFlowTest, handler)
}
