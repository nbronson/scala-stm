/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package impl

/** `RefFactory` is responsible for creating concrete `Ref` instances. */ 
trait RefFactory {
  def newRef(v0: Boolean): Ref[Boolean]
  def newRef(v0: Byte):    Ref[Byte]
  def newRef(v0: Short):   Ref[Short]
  def newRef(v0: Char):    Ref[Char]
  def newRef(v0: Int):     Ref[Int]
  def newRef(v0: Float):   Ref[Float]
  def newRef(v0: Long):    Ref[Long]
  def newRef(v0: Double):  Ref[Double]
  def newRef(v0: Unit):    Ref[Unit]

  /** `T` will not be one of the primitive types (for which a `newRef`
   *  specialization exists).
   */ 
  def newRef[T](v0: T)(implicit m: ClassManifest[T]): Ref[T]
}
