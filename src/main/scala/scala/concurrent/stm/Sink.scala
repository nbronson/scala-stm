/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

object Sink {
  trait View[-A] {

    def unbind: Sink[A]

    def update(v: A) { set(v) }

    def set(v: A)
  }
}

trait Sink[-A] {

  def single: Sink.View[A]
  
  def update(v: A)(implicit txn: Txn) { set(v) }

  def set(v: A)(implicit txn: Txn)
}
