/* scala-stm - (c) 2009-2011, Stanford University, PPL */

package scala.concurrent

package object stm {

  /** Atomically executes atomic blocks using the default `TxnExecutor`.  See
   *  `TxnExecutor.apply`.
   */
  def atomic: scala.concurrent.stm.TxnExecutor = scala.concurrent.stm.TxnExecutor.defaultAtomic

  /** Equivalent to `Txn.retry`. */
  def retry(implicit txn: scala.concurrent.stm.InTxn): Nothing = scala.concurrent.stm.Txn.retry

  def hasElapsed(millis: Long)(implicit txn: scala.concurrent.stm.InTxn): Boolean = scala.concurrent.stm.Txn.hasElapsed(millis)

  /** Equivalent to `Txn.retryFor(timeoutMillis)`. */
  def retryFor(timeoutMillis: Long)(implicit txn: scala.concurrent.stm.InTxn) { scala.concurrent.stm.Txn.retryFor(timeoutMillis) }

  /** This is the first half of the machinery for implementing `orAtomic`. */
  implicit def delayAtomic[A](lhs: => A) = new scala.concurrent.stm.DelayedAtomicBlock(lhs)
}
