/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package impl

import org.scalatest.FunSuite
import java.lang.String
import collection.immutable.Map

class RefFactorySuite extends FunSuite {

  private case class Fact(expected: String) extends STMImpl {

    //////// RefFactory

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

    //////// TxnContext

    def dynCurrentOrNull: Txn = throw new AbstractMethodError

    //////// TxnExecutor

    def apply[Z](block: (Txn) => Z)(implicit mt: MaybeTxn): Z = throw new AbstractMethodError
    def pushAlternative[Z](mt: MaybeTxn, block: (Txn) => Z): Boolean = throw new AbstractMethodError
    def configuration: Map[Symbol, Any] = throw new AbstractMethodError
    def withConfig(param: (Symbol,Any)): TxnExecutor = throw new AbstractMethodError
  }

  test("signature specialization") {
    STMImpl.instance = Fact("Boolean")
    Ref(false)

    STMImpl.instance = Fact("Byte")
    Ref(0 : Byte)

    STMImpl.instance = Fact("Short")
    Ref(0 : Short)

    STMImpl.instance = Fact("Char")
    Ref(0 : Char)

    STMImpl.instance = Fact("Int")
    Ref(0 : Int)

    STMImpl.instance = Fact("Float")
    Ref(0 : Float)

    STMImpl.instance = Fact("Long")
    Ref(0 : Long)

    STMImpl.instance = Fact("Double")
    Ref(0 : Double)

    STMImpl.instance = Fact("Unit")
    Ref(())

    STMImpl.instance = Fact("Any")
    Ref("abc")
    Ref(null)
    Ref(0.asInstanceOf[AnyRef])
    val x: Any = 0
    Ref(x)
  }

  test("missing manifest Ref.apply") {
    STMImpl.instance = Fact("Any")

    def go[T](x: T) = Ref(x)

    go(123)
    go(1.23)
    go(null)
    go("abc")
  }

  test("dynamic specialization") {
    def go[T : ClassManifest](v0: T, which: String) {
      STMImpl.instance = Fact(which)
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
    def go[T : ClassManifest](default: T, which: String) {
      STMImpl.instance = Fact(which)
      Ref.make[T]()
      //assert(x.single() == default)
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
    go[String](null, "Any")
    go[AnyRef](null, "Any")
    go[Null](null, "Any")
    go[Any](null, "Any")
  }

  test("missing manifest Ref.make") {
    STMImpl.instance = Fact("Any")

    def go[T]() = Ref.make[T]()

    go[Boolean]()
    go[Byte]()
    go[Short]()
    go[Char]()
    go[Int]()
    go[Float]()
    go[Long]()
    go[Double]()
    go[Unit]()
    go[String]()
    go[AnyRef]()
    go[Null]()
    go[Any]()
  }

}
