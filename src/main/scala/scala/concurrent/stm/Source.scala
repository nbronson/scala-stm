/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

object Source {
  trait View[+A] {

    def unbind: Source[A]

    def apply(): A = get

    def get: A
  }
}

trait Source[+A] {

  def single: Source.View[A]

  def apply()(implicit txn: Txn): A = get
  
  def get(implicit txn: Txn): A
}
