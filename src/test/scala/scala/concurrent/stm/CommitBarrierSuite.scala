/* scala-stm - (c) 2009-2011, Stanford University, PPL */

package scala.concurrent.stm

import org.scalatest.FunSuite
import skel.SimpleRandom
import actors.threadpool.TimeUnit
import concurrent.forkjoin.LinkedTransferQueue
import util.control.Breaks

class CommitBarrierSuite extends FunSuite {

  test("single member commit") {
    val x = Ref(0)
    val cb = CommitBarrier()
    val m = cb.addMember()
    val z = m.atomic { implicit t =>
      x() = x() + 1
      "result"
    }
    assert(z === Left("result"))
    assert(x.single() === 1)
  }

  test("single member cancel") {
    val x = Ref(0)
    val cb = CommitBarrier()
    val m = cb.addMember()
    val z = m.atomic { implicit t =>
      m.cancel(CommitBarrier.UserCancel("cancel"))
      x() = x() + 1
      "result"
    }
    assert(z === Right(CommitBarrier.UserCancel("cancel")))
    assert(x.single() === 0)

    // commit barrier can still be used
    val m2 = cb.addMember()
    val z2 = m2.atomic { implicit t =>
      x() = x() + 1
      "result2"
    }
    assert(z2 === Left("result2"))
    assert(x.single() === 1)
  }

  test("single member failure") {
    val x = Ref(0)
    val cb = CommitBarrier()
    val m = cb.addMember()
    intercept[Exception] {
      m.atomic { implicit t =>
        x() = x() + 1
        throw new Exception
      }
    }
    assert(x.single() === 0)

    // commit barrier is now dead
    intercept[IllegalStateException] {
      cb.addMember()
    }
  }

  def parRun(n: Int)(body: Int => Unit) {
    // the CountDownLatch is not strictly necessary, but increases the chance
    // of truly concurrent execution
    val startingGate = new java.util.concurrent.CountDownLatch(1)

    val failure = Ref(null : Throwable)

    val threads = new Array[Thread](n)
    for (i <- 0 until n) {
      threads(i) = new Thread() {
        override def run() {
          startingGate.await()
          try {
            body(i)
          } catch {
            case x => failure.single() = x
          }
        }
      }
      threads(i).start()
    }

    startingGate.countDown()

    for (t <- threads) {
      while (t.isAlive && failure.single() == null) {
        t.join(10)
      }
    }

    if (failure.single() != null) {
      throw failure.single()
    }
  }

  def runStress(barrierSize: Int, barrierCount: Int) {
    val refs = Array.tabulate(barrierSize) { _ => Ref(0) }
    val cbs = Array.tabulate(barrierCount) { _ => CommitBarrier() }
    val members = Array.tabulate(barrierCount, barrierSize) { (i, _) => cbs(i).addMember() }
    parRun(barrierSize + 1) { j =>
      if (j == barrierSize) {
        // we are the cpu-hogging observer
        var prev = 0
        var samples = 0
        val rand = new SimpleRandom()
        while (prev < barrierCount) {
          val x = refs(rand.nextInt(barrierSize)).single()
          assert(x >= prev)
          prev = x
          samples += 1
          if ((samples % 137) == 0) {
            // give single-threaded machines a fighting chance
            Thread.`yield`()
          }
        }
      } else {
        // we are a member
        for (m <- members) {
          m(j).atomic { implicit txn =>
            refs(j) += 1
          }
        }
      }
    }
  }

  test("stress 2") {
    runStress(2, 10000)
  }

  test("stress 10") {
    runStress(10, 1000)
  }

  test("stress 1000") {
    runStress(1000, 10)
  }

  test("timeout") {
    val refs = Array.tabulate(2) { _ => Ref(0) }
    val cb = CommitBarrier(100, TimeUnit.MILLISECONDS)
    val members = Array.tabulate(2) { _ => cb.addMember() }
    parRun(2) { i =>
      val z = members(i).atomic { implicit txn =>
        refs(i)() = 1
        if (i == 1) Thread.sleep(200)
      }
      assert(z === Right(CommitBarrier.Timeout))
      assert(refs(i).single() === 0)
    }
  }

  test("interrupt") {
    val refs = Array.tabulate(2) { _ => Ref(0) }
    val cb = CommitBarrier()
    val members = Array.tabulate(2) { _ => cb.addMember() }
    val target = new LinkedTransferQueue[Thread]()
    parRun(3) { i =>
      if (i == 0) {
        // thread 0 is slow
        val z = members(i).atomic { implicit txn =>
          refs(i)() = 1
          Thread.sleep(100)
          "result"
        }
        assert(z.isRight)
        assert(z.right.get.isInstanceOf[CommitBarrier.MemberUncaughtExceptionCause])
        assert(z.right.get.asInstanceOf[CommitBarrier.MemberUncaughtExceptionCause].x.isInstanceOf[InterruptedException])
        assert(refs(i).single() === 0)
      } else if (i == 1) {
        // thread 1 must wait and receives the interrupt
        intercept[InterruptedException] {
          members(i).atomic { implicit txn =>
            refs(i)() = 1
            target.put(Thread.currentThread())
          }
        }
      } else {
        target.take().interrupt()
      }
    }
  }

  test("control flow exception") {
    val ref = Ref(0)
    val cb = CommitBarrier()
    val b = new Breaks()

    b.breakable {
      while (true) {
        cb.addMember().atomic { implicit txn =>
          ref() = ref() + 1
          b.break
        }
      }
    }

    assert(ref.single() === 1)
  }

  test("cycle") {
    val refs = Array.tabulate(3) { _ => Ref(0) }
    val cb = CommitBarrier(100, TimeUnit.MILLISECONDS)
    val members = Array.tabulate(3) { _ => cb.addMember() }
    parRun(3) { i =>
      val z = members(i).atomic { implicit txn =>
        refs(i) += 1
        refs((i + 1) % 3) += 1
      }
      println(z)
      assert(z.isRight)
      assert(z.right.get.isInstanceOf[CommitBarrier.MemberCycle])
    }
  }
}
