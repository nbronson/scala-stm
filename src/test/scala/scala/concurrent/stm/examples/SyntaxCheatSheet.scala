/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm.examples

object SyntaxCheatSheet {
  import scala.concurrent.stm._

  val x: Ref[Int]       = Ref(0) // allocate a Ref[Int]
  val y: Ref[String]    = Ref.make[String]() // type-specific default
  val z: Ref.View[Int]  = x.single // Ref.View[Int]

  atomic { implicit txn =>
    val i = x() // read
    y() = "x was " + i // write
    val eq = atomic { implicit txn => // nested atomic
      x() == z() // both Ref and Ref.View can be used inside atomic
    }
    assert(eq)
    y.set(y.get + ", long-form access")
  }

  // only Ref.View can be used outside atomic
  println("y was '" + y.single() + "'")
  println("z was " + z())

  atomic { implicit txn =>
    y() = y() + ", first alternative"
    if (x getWith { _ > 0 }) // read via a function
      retry // try alternatives or block 
  } orAtomic { implicit txn =>
    y() = y() + ", second alternative"
  }

  val prev: Int = z.swap(10) // atomic swap
  val success: Boolean = z.compareAndSet(10, 11) // atomic compare-and-set
  z.transform { _ max 20 } // atomic transformation
  val pre : String = y.single.getAndTransform { _.toUpperCase }
  val post: String = y.single.transformAndGet { _.filterNot { _ == ' ' } }
}
