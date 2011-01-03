/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm

import org.scalatest.FunSuite


class CallbackSuite extends FunSuite {

  class UserException extends Exception

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

  test("afterCommit doesn't access txn") {
    var ran = false
    val x = Ref(0)
    atomic { implicit t =>
      x() = 1
      Txn.afterCommit { _ =>
        intercept[IllegalStateException] {
          assert(!ran)
          ran = true
          x() = 2
        }
      }
    }
    assert(ran)
    assert(x.single() === 1)
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

  test("whilePreparing") {
    var i = 0
    var observed = -1
    val x = Ref(0)
    atomic { implicit txn =>
      i += 1
      x() = i
      Txn.whilePreparing { _ =>
        observed = i
        if (i < 4) Txn.rollback(Txn.OptimisticFailureCause('test, None))
      }
    }
    assert(x.single() == 4)
    assert(observed == 4)
    assert(i == 4)
  }

  test("whileCommitting") {
    var count = 0
    val x = Ref(0)
    atomic { implicit txn =>
      x() = 1
      Txn.whileCommitting { _ => count += 1 }
    }
    assert(x.single() == 1)
    assert(count == 1)
  }

  test("whileCommitting ordering", Slow) {
    val numThreads = 10
    val numPutsPerThread = 100000
    val startingGate = new java.util.concurrent.CountDownLatch(1)
    val active = Ref(numThreads)
    val failure = Ref(null : Throwable)

    val x = Ref(0)
    val notifier = new scala.concurrent.forkjoin.LinkedTransferQueue[Int]()
    val EOF = -1

    for (i <- 1 to numThreads) {
      (new Thread {
        override def run() {
          try {
            startingGate.await()
            for (i <- 1 to numPutsPerThread) {
              atomic { implicit txn =>
                x() = x() + 1
                val y = x()
                Txn.whileCommitting { _ =>
                  if ((i & 127) == 0) // try to perturb the timing
                    Thread.`yield`
                  notifier.put(y)
                }
              }
            }
          } catch {
            case xx => failure.single() = xx
          }
          if (active.single.transformAndGet( _ - 1 ) == 0)
            notifier.put(EOF)
        }
      }).start
    }

    startingGate.countDown
    for (expected <- 1 to numThreads * numPutsPerThread)
      assert(expected === notifier.take())
    assert(EOF === notifier.take())

    if (failure.single() != null)
      throw failure.single()
  }
}
