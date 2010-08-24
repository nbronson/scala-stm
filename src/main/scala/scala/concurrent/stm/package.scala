/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent

package object stm {
  import scala.concurrent.stm.impl.TxnExecutor

  def atomic: TxnExecutor = TxnExecutor.default 

  /** Equivalent to `implicitly[Txn].retry()`. */
  def retry(implicit txn: scala.concurrent.stm.Txn): Nothing = txn.retry()

  /** This is the first half of the machinery for implementing `orAtomic`. */
  implicit def delayAtomic[A](lhs: => A) = new scala.concurrent.stm.DelayedAtomicBlock(lhs)
}
