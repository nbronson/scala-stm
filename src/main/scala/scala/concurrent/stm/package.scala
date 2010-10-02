/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent

package object stm {

  /** Atomically executes atomic blocks using the default `TxnExecutor`.  See
   *  `TxnExecutor.apply`.
   */
  def atomic: scala.concurrent.stm.TxnExecutor = scala.concurrent.stm.TxnExecutor.default

  /** Equivalent to `Txn.retry`. */
  def retry(implicit txn: scala.concurrent.stm.Txn): Nothing = scala.concurrent.stm.Txn.retry

  /** This is the first half of the machinery for implementing `orAtomic`. */
  implicit def delayAtomic[A](lhs: => A) = new scala.concurrent.stm.DelayedAtomicBlock(lhs)
}
