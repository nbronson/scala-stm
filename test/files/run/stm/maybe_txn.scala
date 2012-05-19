/* scala-stm - (c) 2009-2010, Stanford University, PPL */


import scala.concurrent.stm._
import scala.concurrent.stm.skel._
import scala.concurrent.stm.japi._
import scala.concurrent.stm.impl._

object Test {

  def test(name: String)(block: => Unit) {
    println("running retry " + name)
    block
  }

  def intercept[X](block: => Unit)(implicit xm: ClassManifest[X]) {
    try {
      block
      assert(false, "expected " + xm.erasure)
    } catch {
      case x if (xm.erasure.isAssignableFrom(x.getClass)) => // okay
    }
  }

  def main(args: Array[String]) {
    test("implicit InTxn match") {
      implicit val txn: InTxn = new skel.StubInTxn

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

    def context(implicit mt: MaybeTxn) = mt

    test("Static nesting lookup") {
      val x = Ref(10)
      atomic { implicit t =>
        assert(x() == 10)
        x() = 11
        atomic { implicit t =>
          assert(x() == 11)
          x() = 12
          atomic { implicit t =>
            assert(x() == 12)
            x() = 13
          }
          assert(x() == 13)
        }
        assert(x() == 13)
      }
      assert(x.single() == 13)
    }

    test("Dynamic nesting lookup") {
      val x = Ref(10)
      val xs = x.single
      def loop(expected: Int) {
        atomic { implicit t =>
          assert(x() == expected)
          assert(xs() == expected)
          x() = expected + 1
          if (expected < 100)
            loop(expected + 1)
          assert(x() == 101)
        }
      }
      loop(10)
      assert(xs() == 101)
      assert(x.single() == 101)
    }

    test("Static vs dynamic lookup") {
      implicit var t0: InTxn = null
      val n0 = atomic { t =>
        t0 = t
        assert(Txn.findCurrent == Some(t))
        assert(impl.STMImpl.instance.findCurrent == Some(t))
        NestingLevel.root
      }
      assert(n0.status == Txn.Committed)
      assert(Txn.findCurrent == None)
      assert(impl.STMImpl.instance.findCurrent == None)
      atomic { t =>
        assert(NestingLevel.current(t) != n0)
        assert(NestingLevel.root(t).status == Txn.Active)
        assert(Txn.status == Txn.Active)
        assert(Txn.findCurrent == Some(t))
        assert(impl.STMImpl.instance.findCurrent == Some(t))
      }
    }
  }
}
