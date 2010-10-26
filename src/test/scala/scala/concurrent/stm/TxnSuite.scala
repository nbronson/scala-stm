/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

import org.scalatest.FunSuite


class TxnSuite extends FunSuite {

  test("empty transaction") {
    atomic { implicit t =>
      () // do nothing
    }
  }

  test("atomic function") {
    val answer = atomic { implicit t =>
      42
    }
    assert(Integer.parseInt(answer.toString, 13) === 6*9)
  }

  test("duplicate view with old access") {
    val x = Ref(1)
    atomic { implicit t =>
      val b1 = x.single
      assert(b1.get === 1)
      val b2 = x.single
      assert(b2.get === 1)
      b1() = 2
      assert(b1.get === 2)
      assert(b2.get === 2)
      b2() = 3
      assert(b1.get === 3)
      assert(b2.get === 3)
    }
    assert(x.single.get === 3)
  }

  class UserException extends Exception

  test("failure atomicity") {
    val x = Ref(1)
    intercept[UserException] {
      atomic { implicit t =>
        x() = 2
        throw new UserException
      }
    }
    assert(x.single.get === 1)
  }

  test("non-local return") {
    val x = Ref(1)
    val y = nonLocalReturnHelper(x)
    assert(x.single.get === 2)
    assert(y === 2)
  }

  def nonLocalReturnHelper(x: Ref[Int]): Int = {
    atomic { implicit t =>
      x() = x() + 1
      return x()
    }
    return -1
  }

  test("atomic.oneOf") {
    val x = Ref(false)
    val y = Ref(false)
    val z = Ref(false)
    for ((ref,name) <- List((x,"x"), (y,"y"), (z,"z"))) {
      new Thread("wakeup") {
        override def run {
          Thread.sleep(200)
          ref.single() = true
        }
      }.start()

      val result = Ref("")
      var sleeps = 0
      atomic.oneOf(
          { t: InTxn => implicit val txn = t; result() = "x" ; if (!x()) retry },
          { t: InTxn => implicit val txn = t; if (y()) result() = "y" else retry },
          { t: InTxn => implicit val txn = t; if (z()) result() = "z" else retry },
          { t: InTxn => implicit val txn = t; sleeps += 1 ; retry }
        )
      ref.single() = false
      assert(result.single.get === name)
      assert(sleeps <= 1)
    }
  }

  test("orAtomic w/ exception") {
    intercept[UserException] {
      atomic { implicit t =>
        if ("likely".hashCode != 0)
          retry
      } orAtomic { implicit t =>
        throw new UserException
      }
    }
  }

  test("Atomic.orAtomic") {
    val x = Ref(1)
    def a() = {
      atomic { implicit t =>
        if (x() > 1) true else retry
      } orAtomic { implicit t =>
        false
      }
    }
    assert(a() === false)
    x.single() = 2
    assert(a() === true)
  }

  test("simple nesting") {
    val x = Ref(10)
    atomic { implicit t =>
      x += 1
      atomic { implicit t =>
        assert(x.get === 11)
        x += 2
      }
      assert(x.get === 13)
    }
    assert(x.single.get === 13)
  }

  test("View in txn") {
    val x = Ref(10)
    val xs = x.single
    atomic { implicit t =>
      x += 1
      assert(x() === 11)
      assert(xs() === 11)
      xs += 1
      assert(x() === 12)
      assert(xs() === 12)
      x.single += 1
      assert(x() === 13)
      assert(xs() === 13)
      assert(x.single() === 13)
      x.single() = 14
      assert(x() === 14)
    }
  }

  test("uncontended R+W txn perf") {
    val x = Ref("abc")
    var best = java.lang.Long.MAX_VALUE
    for (pass <- 0 until 100000) {
      val begin = System.nanoTime
      var i = 0
      while (i < 5) {
        i += 1
        atomic { implicit t =>
          assert(x() == "abc")
          x() = "def"
        }
        atomic { implicit t =>
          assert(x() == "def")
          x() = "abc"
        }
      }
      val elapsed = System.nanoTime - begin
      best = best min elapsed
    }
    println("uncontended R+W txn: best was " + (best / 10.0) + " nanos/txn")

    // We should be able to get less than 5000 nanos, even on a Niagara.
    // On most platforms we should be able to do much better than this.
    // The exception is StripedIntRef, which has relatively expensive reads.
    assert(best / 10 < 5000)
  }

  def testAfterCommit(name: String, f: (InTxn, (Txn.Status => Unit)) => Unit) {
    test(name) {
      var ran = false
      atomic { implicit t =>
        f(t, { status =>
          assert(!ran)
          ran = true
        })
      }
      assert(ran)
    }
  }

  testAfterCommit("afterCompletion", { (t, h) => implicit val txn = t ; Txn.afterCompletion(h) })
  testAfterCommit("afterCommit", { (t, h) => implicit val txn = t ; Txn.afterCommit(h) })

  test("afterRollback on commit") {
    atomic { implicit t =>
      Txn.afterRollback { _ => assert(false) }
    }
  }

  test("afterRollback on rollback") {
    val x = Ref(10)
    var ran = false
    atomic { implicit t =>
      Txn.afterRollback { _ =>
        assert(!ran)
        ran = true
      }
      if (x() == 10) {
        val adversary = new Thread {
          override def run() {
            x.single.transform(_ + 1)
          }
        }
        adversary.start()
        adversary.join()
        x()
        assert(false)
      }
    }
    assert(ran)
  }

  test("afterCommit runs a txn") {
    var ran = false
    val x = Ref(0)
    atomic { implicit t =>
      x() = 1
      Txn.afterCommit { _ =>
        atomic { implicit t =>
          assert(!ran)
          ran = true
          assert(x() === 1)
          x() = 2
        }
      }
    }
    assert(ran)
    assert(x.single() === 2)
  }
}
