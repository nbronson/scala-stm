/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA

trait Source[+A] {

  def apply()(implicit txn: Txn): A = get
  
  def get(implicit txn: Txn): A
}
