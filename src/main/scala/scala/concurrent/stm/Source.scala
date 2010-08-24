/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

object Source {
  trait View[+A] {

    def unbind: Source[A]

    def apply(): A = get

    def get: A

    def getWith[Z](f: A => Z): Z

    def relaxedGet(revalidator: (A, A) => Boolean): A

    def relaxedGetWith[Z](f: A => Z, revalidator: (Z, Z) => Boolean)(implicit txn: Txn): Z
  }
}

trait Source[+A] {

  def single: Source.View[A]

  def apply()(implicit txn: Txn): A = get
  
  def get(implicit txn: Txn): A

  def getWith[Z](f: A => Z)(implicit txn: Txn): Z

  def relaxedGet(revalidator: (A, A) => Boolean)(implicit txn: Txn): A

  def relaxedGetWith[Z](f: A => Z, revalidator: (Z, Z) => Boolean)(implicit txn: Txn): Z
}
