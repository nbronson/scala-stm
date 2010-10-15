/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

import org.scalatest.FunSuite

class MaybeTxnSuite extends FunSuite {
  test("implicit InTxn match") {
    implicit val txn: InTxn = new ri.StubInTxn

    assert(implicitly[MaybeTxn] eq txn)
  }

  test("implicit TxnUnknown match") {
    assert(implicitly[MaybeTxn] eq TxnUnknown)
  }

  test("TxnUnknown is found") {
    assert(context eq TxnUnknown)
  }

  test("InTxn is found") {
    atomic { t0 =>
      implicit val t = t0
      assert(context eq t)
    }
    atomic { implicit t =>
      assert(context eq t)
    }
  }

  private def context(implicit mt: MaybeTxn) = mt

  test("Static nesting lookup") {
    val x = Ref(10)
    atomic { implicit t =>
      assert(x() === 10)
      x() = 11
      atomic { implicit t =>
        assert(x() === 11)
        x() = 12
        atomic { implicit t =>
          assert(x() === 12)
          x() = 13
        }
        assert(x() === 13)
      }
      assert(x() === 13)
    }
    assert(x.single() === 13)
  }

  test("Dynamic nesting lookup") {
    val x = Ref(10)
    val xs = x.single
    def loop(expected: Int) {
      atomic { implicit t =>
        assert(x() === expected)
        assert(xs() === expected)
        x() = expected + 1
        if (expected < 100)
          loop(expected + 1)
        assert(x() === 101)
      }
    }
    loop(10)
    assert(xs() === 101)
    assert(x.single() === 101)
  }

  test("Static vs dynamic lookup") {
    implicit var t0: InTxn = null
    val n0 = atomic { t =>
      t0 = t
      assert(Txn.current === Some(t))
      assert(impl.STMImpl.instance.current === Some(t))
      Txn.rootLevel
    }
    assert(n0.status === Txn.Committed)
    assert(Txn.current === Some(t0))
    assert(impl.STMImpl.instance.current === None)
    atomic { t =>
      assert(t0 ne t)
      assert(Txn.rootLevel(t).status === Txn.Active)
      assert(Txn.current === Some(t0))
      assert(impl.STMImpl.instance.current === Some(t))
    }
  }
}
