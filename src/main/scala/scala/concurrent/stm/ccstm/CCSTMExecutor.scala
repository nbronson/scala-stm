/* scala-stm - (c) 2009-2010, Stanford University, PPL */

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

  def apply[Z](block: InTxn => Z)(implicit mt: MaybeTxn): Z = InTxnImpl().atomic(this, block)

  def oneOf[Z](blocks: (InTxn => Z)*)(implicit mt: MaybeTxn): Z = InTxnImpl().atomicOneOf(this, blocks)

  def pushAlternative[Z](mt: MaybeTxn, block: (InTxn) => Z): Boolean = InTxnImpl().pushAlternative(block)

  def compareAndSet[A, B](a: Ref[A], a0: A, a1: A, b: Ref[B], b0: B, b1: B): Boolean = {
    val ah = a.asInstanceOf[Handle.Provider[A]].handle
    val bh = b.asInstanceOf[Handle.Provider[B]].handle
    InTxnImpl.dynCurrentOrNull match {
      case null => NonTxn.transform2[A, B, Boolean](ah, bh, { (av, bv) => if (a0 == av && b0 == bv) (a1, b1, true) else (a0, b0, false) })
      case txn => a0 == txn.get(ah) && b0 == txn.get(bh) && { txn.set(ah, a1) ; txn.set(bh, b1) ; true }
    }
  }

  def compareAndSetIdentity[A <: AnyRef, B <: AnyRef](a: Ref[A], a0: A, a1: A, b: Ref[B], b0: B, b1: B): Boolean = {
    if (a0 eq a1)
      ccasi(a, a0, b, b0, b1)
    else if (b0 eq b1)
      ccasi(b, b0, a, a0, a1)
    else
      dcasi(a, a0, a1, b, b0, b1)
  }

  private def ccasi[A <: AnyRef, B <: AnyRef](a: Ref[A], a0: A, b: Ref[B], b0: B, b1: B): Boolean = {
    val ah = a.asInstanceOf[Handle.Provider[A]].handle
    val bh = b.asInstanceOf[Handle.Provider[B]].handle
    InTxnImpl.dynCurrentOrNull match {
      case null => NonTxn.ccasi(ah, a0, bh, b0, b1)
      case txn => (a0 eq txn.get(ah)) && (b0 eq txn.get(bh)) && { txn.set(bh, b1) ; true }
    }
  }

  private def dcasi[A <: AnyRef, B <: AnyRef](a: Ref[A], a0: A, a1: A, b: Ref[B], b0: B, b1: B): Boolean = {
    val ah = a.asInstanceOf[Handle.Provider[A]].handle
    val bh = b.asInstanceOf[Handle.Provider[B]].handle
    InTxnImpl.dynCurrentOrNull match {
      case null => NonTxn.transform2[A, B, Boolean](ah, bh, { (av, bv) => if ((a0 eq av) && (b0 eq bv)) (a1, b1, true) else (a0, b0, false) })
      case txn => (a0 eq txn.get(ah)) && (b0 eq txn.get(bh)) && { txn.set(ah, a1) ; txn.set(bh, b1) ; true }
    }
  }

  def isControlFlow(x: Throwable): Boolean = controlFlowTest(x)
  
  def withControlFlowRecognizer(pf: PartialFunction[Throwable, Boolean]): TxnExecutor = {
    val chained = { x: Throwable => if (pf.isDefinedAt(x)) pf(x) else controlFlowTest(x) }
    new CCSTMExecutor(chained, postDecisionFailureHandler)
  }

  def withPostDecisionFailureHandler(handler: (Txn.Status, Throwable) => Unit): TxnExecutor =
      new CCSTMExecutor(controlFlowTest, handler)
}
