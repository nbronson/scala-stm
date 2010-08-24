/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ri

import java.lang.Throwable

class StubSTMImpl extends impl.STMImpl {
  {
    println("constructing " + getClass.getSimpleName)
  }

  //////// RefFactory

  def newRef(v0: Boolean): Ref[Boolean] = throw new AbstractMethodError
  def newRef(v0: Byte): Ref[Byte] = throw new AbstractMethodError
  def newRef(v0: Short): Ref[Short] = throw new AbstractMethodError
  def newRef(v0: Char): Ref[Char] = throw new AbstractMethodError
  def newRef(v0: Int): Ref[Int] = throw new AbstractMethodError
  def newRef(v0: Float): Ref[Float] = throw new AbstractMethodError
  def newRef(v0: Long): Ref[Long] = throw new AbstractMethodError
  def newRef(v0: Double): Ref[Double] = throw new AbstractMethodError
  def newRef(v0: Unit): Ref[Unit] = throw new AbstractMethodError
  def newRef[T](v0: T)(implicit m: ClassManifest[T]): Ref[T] = throw new AbstractMethodError

  //////// TxnContext

  def dynCurrentOrNull: Txn = throw new AbstractMethodError

  //////// TxnExecutor

  def apply[Z](block: (Txn) => Z)(implicit mt: MaybeTxn): Z = throw new AbstractMethodError
  def pushAlternative[Z](mt: MaybeTxn, block: (Txn) => Z): Boolean = throw new AbstractMethodError
  def configuration: Map[Symbol, Any] = throw new AbstractMethodError
  def withConfig(param: (Symbol,Any)): impl.TxnExecutor = throw new AbstractMethodError
  def isControlFlow(x: Throwable): Boolean = throw new AbstractMethodError
  def withControlFlowRecognizer(pf: PartialFunction[Throwable, Boolean]) = throw new AbstractMethodError
}
