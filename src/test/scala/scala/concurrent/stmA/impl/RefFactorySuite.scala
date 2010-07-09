/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA
package impl

import org.scalatest.FunSuite
import reflect.ClassManifest

class RefFactorySuite extends FunSuite {

  private case class Fact(expected: String) extends RefFactory {
    private def called(w: String) = {
      assert(w === expected)
      null
    }

    def newRef(v0: Boolean): Ref[Boolean] = called("Boolean")
    def newRef(v0: Byte): Ref[Byte] = called("Byte")
    def newRef(v0: Short): Ref[Short] = called("Short")
    def newRef(v0: Char): Ref[Char] = called("Char")
    def newRef(v0: Int): Ref[Int] = called("Int")
    def newRef(v0: Float): Ref[Float] = called("Float")
    def newRef(v0: Long): Ref[Long] = called("Long")
    def newRef(v0: Double): Ref[Double] = called("Double")
    def newRef(v0: Unit): Ref[Unit] = called("Unit")
    def newRef[T](v0: T)(implicit m: ClassManifest[T]): Ref[T] = called("Any")
  }

  test("signature specialization") {
    RefFactory.instance = Fact("Boolean")
    Ref(false)

    RefFactory.instance = Fact("Byte")
    Ref(0 : Byte)

    RefFactory.instance = Fact("Short")
    Ref(0 : Short)

    RefFactory.instance = Fact("Char")
    Ref(0 : Char)

    RefFactory.instance = Fact("Int")
    Ref(0 : Int)

    RefFactory.instance = Fact("Float")
    Ref(0 : Float)

    RefFactory.instance = Fact("Long")
    Ref(0 : Long)

    RefFactory.instance = Fact("Double")
    Ref(0 : Double)

    RefFactory.instance = Fact("Unit")
    Ref(())

    RefFactory.instance = Fact("Any")
    Ref("abc")
    Ref(null)
    Ref(0.asInstanceOf[AnyRef])
    val x: Any = 0
    Ref(x)
  }

  test("dynamic specialization") {
    def go[T : ClassManifest](v0: T, which: String) {
      RefFactory.instance = Fact(which)
      Ref(v0)
    }
    
    go(false, "Boolean")
    go(0 : Byte, "Byte") 
    go(0 : Short, "Short") 
    go(0 : Char, "Char")
    go(0 : Int, "Int")
    go(0 : Float, "Float") 
    go(0 : Long, "Long") 
    go(0 : Double, "Double") 
    go((), "Unit")
    go("abc", "Any")
    go(null, "Any")
    go(0.asInstanceOf[AnyRef], "Any")
    val x: Any = 0
    go(x, "Any")
  }

  test("default value specialization") {
    def go[T : ClassManifest](which: String) {
      RefFactory.instance = Fact(which)
      Ref.make[T]()
    }

    go[Boolean]("Boolean")
    go[Byte]("Byte")
    go[Short]("Short")
    go[Char]("Char")
    go[Int]("Int")
    go[Float]("Float")
    go[Long]("Long")
    go[Double]("Double")
    go[Unit]("Unit")
    go[String]("Any")
    go[AnyRef]("Any")
    go[Null]("Any")
    go[Any]("Any")
  }
}
