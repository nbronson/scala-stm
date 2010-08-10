/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent

package object stm {

  /** Equivalent to `implicitly[Txn].retry()`. */
  def retry(implicit txn: scala.concurrent.stm.Txn): Nothing = txn.retry()

  /** This is the first half of the machinery for implementing `orAtomic`. */
  implicit def delayAtomic[A](lhs: => A) = new scala.concurrent.stm.atomic.Delayed(lhs)
}
