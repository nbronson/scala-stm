/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm

import org.scalatest.FunSuite


class TxnLocalSuite extends FunSuite {
  test("default initial value") {
    val tl = new TxnLocal[String]
    atomic { implicit txn =>
      assert(tl() === null)
      tl() = "hello"
    }
    atomic { implicit txn =>
      assert(tl() === null)
    }
    atomic { implicit txn =>
      tl() = "transient"
    }
    atomic { implicit txn =>
      assert(tl() === null)
      tl() = "world"
    }
  }

  test("override") {
    val tl = new TxnLocal[Int] {
      override def initialValue(txn: InTxn) = {
        txn.rootLevel.##
      }
    }
    atomic { implicit txn =>
      assert(tl() === txn.rootLevel.##)
    }
    atomic { implicit txn =>
      assert(tl() === txn.rootLevel.##)
    }
  }

  test("factory method") {
    val tl = TxnLocal("hello")
    for (i <- 0 until 100000) {
      atomic { implicit txn =>
        assert(tl() === "hello")
        tl() = "goodbye"
      }
    }
  }

  test("use in afterCompletion handler") {
    val tl = TxnLocal("default")
    atomic { implicit txn =>
      Txn.afterCompletion { status =>
        atomic { implicit txn =>
          assert(tl() === "default")
        }
      }
      tl() = "set once"
      Txn.afterCompletion { status =>
        atomic { implicit txn =>
          assert(tl() === "default")
        }
      }
    }
  }

  test("partial rollback restores previous value") {
    val tl = TxnLocal("default")
    atomic { implicit txn =>
      tl() = "outer"
      intercept[RuntimeException] {
        atomic { implicit txn =>
          assert(tl.swap("inner") === "outer")
          throw new RuntimeException
        }
      }
      assert(tl() === "outer")
    }
  }

  test("partial rollback triggers new initialization") {
    var count = 0
    val tl = TxnLocal( { count += 1 ; count } )
    atomic { implicit txn =>
      intercept[RuntimeException] {
        atomic { implicit txn =>
          assert(tl() === 1)
          assert(count === 1)
          tl() = 20
          throw new RuntimeException
        }
      }
      assert(tl() === 2)
      assert(count === 2)
    }
  }

  test("all operations") {
    val tl = TxnLocal(10)
    atomic { implicit txn =>
      assert(tl.get === 10)
      assert(tl() === 10)
      assert(tl.getWith( _ + 5 ) === 15)
      assert(tl.relaxedGet( _ == _ ) === 10)
      tl.set(15)
      assert(tl() === 15)
      tl() = 20
      assert(tl() === 20)
      assert(tl.trySet(25))
      assert(tl() === 25)
      assert(tl.swap(30) === 25)
      tl.transform( _ + 7 )
      assert(tl() === 37)
      assert(tl.transformIfDefined {
        case x if x > 20 => x + 1
      })
      assert(tl() === 38)
      assert(!tl.transformIfDefined {
        case x if x < 20 => x + 1
      })
    }
  }
}
