/* scala-stm - (c) 2009-2010, Stanford University, PPL */

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

  test("partial rollback") {
    val x = Ref("none")
    atomic { implicit t =>
      x() = "outer"
      try {
        atomic { implicit t =>
          x() = "inner"
          throw new UserException
        }
      } catch {
        case _: UserException =>
      }
    }
    assert(x.single() === "outer")
  }

  test("retry set accumulation across alternatives") {
    val x = Ref(false)

    // this prevents the test from deadlocking
    new Thread("trigger") {
      override def run {
        Thread.sleep(200)
        x.single() = true
      }
    } start

    atomic { implicit t =>
      // The following txn and its alternative decode the value of x that was
      // observed, without x being a part of the current read set.
      val f = atomic { implicit t =>
        atomic { implicit t =>
          // this txn encodes the read of x in its retry state
          if (!x()) retry
        }
        true
      } orAtomic { implicit t =>
        false
      }
      if (!f) retry
    }
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

  perfTest("uncontended R+W txn perf") { x =>
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
  }

  for (depth <- List(0, 1, 2, 4, 8)) {
    perfTest("uncontended R+W txn perf: nesting depth " + depth) { x =>
      var i = 0
      while (i < 5) {
        i += 1
        nested(depth) { implicit t =>
          assert(x() == "abc")
          x() = "def"
        }
        nested(depth) { implicit t =>
          assert(x() == "def")
          x() = "abc"
        }
      }
    }
  }

  private def nested(depth: Int)(body: InTxn => Unit) {
    atomic { implicit txn =>
      if (depth == 0)
        body(txn)
      else
        nested(depth - 1)(body)
    }
  }

  def perfTest(name: String)(runTen: Ref[String] => Unit) {
    test(name) {
      val x = Ref("abc")
      var best = java.lang.Long.MAX_VALUE
      for (pass <- 0 until 50000) {
        val begin = System.nanoTime
        runTen(x)
        val elapsed = System.nanoTime - begin
        best = best min elapsed
      }
      println(name + ": best was " + (best / 10.0) + " nanos/txn")
    }
  }

  test("beforeCommit upgrade on read-only commit") {
    val x = Ref(0)
    var ran = false
    atomic { implicit t =>
      assert(x() === 0)
      Txn.beforeCommit { _ =>
        assert(!ran)
        x() = 1
        ran = true
      }
    }
    assert(ran)
    assert(x.single() === 1)
  }

  test("retry in beforeCommit") {
    val x = Ref(0)
    val t = new Thread("trigger") {
      override def run() {
        for (i <- 0 until 5) {
          Thread.sleep(200)
          x.single() += 1
        }
      }
    }
    var tries = 0
    t.start()
    val y = Ref(0)
    atomic { implicit t =>
      tries += 1
      y() = 1
      Txn.beforeCommit { implicit t =>
        if (x() < 5)
          retry
      }
    }
    assert(tries >= 5)
  }

  test("exception in beforeCommit") {
    val x = Ref[Option[String]](Some("abc"))
    intercept[NoSuchElementException] {
      atomic { implicit t =>
        x() = None
        Txn.beforeCommit { _ => println(x().get) }
      }
    }
  }

  test("surviving beforeCommit") {
    val x = Ref(1)
    val y = Ref(2)
    val z = Ref(3)
    var a = false
    var aa = false
    var ab = false
    var b = false
    var ba = false
    var bb = false
    var bc = false
    atomic { implicit t =>
      Txn.beforeCommit { _ => assert(!a) ; a = true }
      atomic { implicit t =>
        Txn.beforeCommit { _ => assert(!aa) ; aa = true }
        x += 1
        if (x() != 0)
          retry
      } orAtomic { implicit t =>
        Txn.beforeCommit { _ => assert(!ab) ; ab = true }
        y += 1
        if (y() != 0)
          retry
      }
      z += 8
    } orAtomic { implicit t =>
      Txn.beforeCommit { _ => assert(!b && !ba && !bb && !bc) ; b = true }
      atomic { implicit t =>
        Txn.beforeCommit { _ => assert(!ba) ; ba = true }
        z += 1
        if (x() != 0)
          retry
      } orAtomic { implicit t =>
        Txn.beforeCommit { _ => assert(!bb) ; bb = true }
        x += 1
        if (x() != 0)
          retry
      } orAtomic { implicit t =>
        Txn.beforeCommit { _ => assert(b) ; assert(!bc) ; bc = true }
        if (x() + y() + z() == 0)
          retry
      }
      z += 16
    }
    assert(!a)
    assert(!aa)
    assert(!ab)
    assert(b)
    assert(!ba)
    assert(!bb)
    assert(bc)
    assert(x.single() == 1)
    assert(y.single() == 2)
    assert(z.single() == 19)
  }

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

  test("beforeCommit during beforeCommit") {
    val handler = new Function1[InTxn, Unit] {
      var count = 0

      def apply(txn: InTxn) {
        if (txn eq null) {
          // this is the after-atomic check
          assert(count === 1000)
        } else {
          count += 1
          if (count < 1000)
            Txn.beforeCommit(this)(txn)
        }
      }
    }
    val x = Ref(0)
    atomic { implicit t =>
      x += 1
      Txn.beforeCommit(handler)
    }
    handler(null)
  }

  test("beforeCommit termination") {
    val x = Ref(0)
    var a = false
    intercept[UserException] {
      atomic { implicit t =>
        assert(x() === 0)
        Txn.beforeCommit { _ =>
          assert(!a)
          a = true
          throw new UserException
        }
        x += 2
        Txn.beforeCommit { _ =>
          assert(false)
        }
      }
    }
    assert(a)
  }

  test("manual optimistic retry") {
    var tries = 0
    val x = Ref(0)
    atomic { implicit t =>
      assert(x() === 0)
      x += tries
      tries += 1
      if (tries < 100)
        Txn.rollback(Txn.OptimisticFailureCause('manual_failure, None))
    }
    assert(x.single() === 99)
    assert(tries === 100)
  }

  test("manual optimistic retry during beforeCommit") {
    var tries = 0
    val x = Ref(0)
    atomic { implicit t =>
      assert(x() === 0)
      x += tries
      tries += 1
      Txn.beforeCommit { implicit t =>
        if (tries < 100)
          Txn.rollback(Txn.OptimisticFailureCause('manual_failure, None))
      }
    }
    assert(x.single() === 99)
    assert(tries === 100)
  }

  // TODO: whilePreparing and whileCommitting callbacks
  // TODO: exception behavior from all types of callbacks
}
