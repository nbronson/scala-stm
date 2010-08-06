/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA

import impl.{RefFactory,STMImpl}
import reflect.{AnyValManifest, OptManifest}

object Ref {

  private def factory: RefFactory = STMImpl.instance

  /** Returns a new `Ref` instance suitable for holding instances of `T`.
   *  If you have an initial value `v0` available, prefer `apply(v0)`.
   */
  def make[T]()(implicit om: OptManifest[T]): Ref[T] = (om match {
    case m: ClassManifest[_] => m.newArray(0).asInstanceOf[AnyRef] match {
      // these can be reordered, so long as Unit comes before AnyRef
      case _: Array[Boolean] => apply(false)
      case _: Array[Byte]    => apply(0 : Byte)
      case _: Array[Short]   => apply(0 : Short)
      case _: Array[Char]    => apply(0 : Char)
      case _: Array[Int]     => apply(0 : Int)
      case _: Array[Float]   => apply(0 : Float)
      case _: Array[Long]    => apply(0 : Long)
      case _: Array[Double]  => apply(0 : Double)
      case _: Array[Unit]    => apply(())
      case _: Array[AnyRef]  => factory.newRef(null.asInstanceOf[T])(m.asInstanceOf[ClassManifest[T]])
    }
    case _ => factory.newRef(null.asInstanceOf[Any])(implicitly[ClassManifest[Any]])
  }).asInstanceOf[Ref[T]]

  /** Returns a new `Ref` instance with the specified initial value.  The
   *  returned instance is not part of any transaction's read or write set.
   *
   *  Example: {{{
   *    val x = Ref("initial") // creates a Ref[String]
   *    val list1 = Ref(Nil : List[String]) // creates a Ref[List[String]]
   *    val list2 = Ref[List[String]](Nil)  // creates a Ref[List[String]]
   *  }}}
   */
  def apply[T](initialValue: T)(implicit om: OptManifest[T]): Ref[T] = om match {
    case m: AnyValManifest[_] => newPrimitiveRef(initialValue, m.asInstanceOf[AnyValManifest[T]])
    case m: ClassManifest[_] => factory.newRef(initialValue)(m.asInstanceOf[ClassManifest[T]])
    case _ => factory.newRef[Any](initialValue).asInstanceOf[Ref[T]]
  }

  private def newPrimitiveRef[T](initialValue: T, m: AnyValManifest[T]): Ref[T] = {
    (m.newArray(0).asInstanceOf[AnyRef] match {
      // these can be reordered, so long as Unit comes before AnyRef
      case _: Array[Boolean] => apply(initialValue.asInstanceOf[Boolean])
      case _: Array[Byte]    => apply(initialValue.asInstanceOf[Byte])
      case _: Array[Short]   => apply(initialValue.asInstanceOf[Short])
      case _: Array[Char]    => apply(initialValue.asInstanceOf[Char])
      case _: Array[Int]     => apply(initialValue.asInstanceOf[Int])
      case _: Array[Float]   => apply(initialValue.asInstanceOf[Float])
      case _: Array[Long]    => apply(initialValue.asInstanceOf[Long])
      case _: Array[Double]  => apply(initialValue.asInstanceOf[Double])
      case _: Array[Unit]    => apply(initialValue.asInstanceOf[Unit])
    }).asInstanceOf[Ref[T]]
  }

  def apply(initialValue: Boolean): Ref[Boolean] = factory.newRef(initialValue)
  def apply(initialValue: Byte   ): Ref[Byte]    = factory.newRef(initialValue)
  def apply(initialValue: Short  ): Ref[Short]   = factory.newRef(initialValue)
  def apply(initialValue: Char   ): Ref[Char]    = factory.newRef(initialValue)
  def apply(initialValue: Int    ): Ref[Int]     = factory.newRef(initialValue)
  def apply(initialValue: Long   ): Ref[Long]    = factory.newRef(initialValue)
  def apply(initialValue: Float  ): Ref[Float]   = factory.newRef(initialValue)
  def apply(initialValue: Double ): Ref[Double]  = factory.newRef(initialValue)
  def apply(initialValue: Unit   ): Ref[Unit]    = factory.newRef(initialValue)
}

trait Ref[A] extends Source[A] with Sink[A] {

  // read-only operations (covariant) are in Source
  // write-only operations (contravariant) are in Sink
  // read+write operations go here 

  def transform(f: A => A)(implicit txn: Txn)
}
