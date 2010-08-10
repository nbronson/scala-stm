/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

trait Sink[-A] {
  
  def update(v: A)(implicit txn: Txn) { set(v) }

  def set(v: A)(implicit txn: Txn)
}
