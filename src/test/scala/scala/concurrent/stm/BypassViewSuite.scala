/* scala-stm - (c) 2009-2011, Stanford University, PPL */

package scala.concurrent.stm

import org.scalatest.FunSuite


/** Performs tests of `BypassView`. */
class BypassViewSuite extends FunSuite {

  test("bypassing read") {
    val x = Ref(0)
    atomic { implicit t =>
      x() = 1
      assert(x.bypass() === 0)
    }
    assert(x.bypass() === 1)
  }

  test("bypass read during callbacks") {
    val x = Ref(0)
    atomic { implicit t =>
      x() = 1
      Txn.beforeCommit { implicit t =>
        assert(x.bypass() === 0)
        assert(x.bypass.weakGet === 0)
      }
      Txn.whilePreparing { implicit t =>
        assert(x.bypass() === 0)
        assert(x.bypass.weakGet === 0)
      }
      Txn.whileCommitting { implicit t =>
        assert(x.bypass() === 0)
        assert(x.bypass.weakGet === 0)
      }
      Txn.setExternalDecider(new Txn.ExternalDecider {
        def shouldCommit(implicit t: InTxnEnd) = {
          assert(x.bypass() === 0)
          assert(x.bypass.weakGet === 0)
          true
        }
      })
      Txn.afterCommit { implicit t =>
        assert(x.bypass() === 1)
        assert(x.bypass.weakGet === 1)
      }
    }
  }

  test("write deadlock avoided for set") {
    val x = Ref(0)
    atomic { implicit t =>
      x() = 1
      try {
        x.bypass() = 2
      } catch {
        case _: IllegalStateException => {}
      }
    }
  }

  test("write deadlock avoided for swap") {
    val x = Ref(0)
    atomic { implicit t =>
      x() = 1
      try {
        assert(x.bypass.swap(2) === 0)
      } catch {
        case _: IllegalStateException => {}
      }
    }
  }

  test("after-commit swap") {
    val x = Ref(0)
    atomic { implicit t =>
      x() = 1
      Txn.afterCommit { implicit t =>
        assert(x.bypass.swap(2) === 1)
      }
    }
  }
}