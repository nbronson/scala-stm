/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm

import java.util.concurrent.CyclicBarrier
import org.scalatest.FunSuite


/** Flipper is derived from a test originally used for the ATLAS HTM prototype.
 *
 *  @author Nathan Bronson
 */
class FlipperSuite extends FunSuite {
  val DEFAULT_SYNC_COUNT = 3
  val DEFAULT_TRANS_COUNT = 100
  val DEFAULT_INSTR_COUNT = 100
  val DEFAULT_THREAD_COUNT = 4
  val DEFAULT_WORD_COUNT = 4096
  val DEFAULT_FLIP_PROB = 0.5f
  val DEFAULT_REF_ARRAY_FACTORY = { (n: Int) => (Array.tabulate[Ref[Int]](n) { _ => Ref(0) }) : IndexedSeq[Ref[Int]] }

  test("small flipper test") {
    Config(
      DEFAULT_SYNC_COUNT,
      DEFAULT_TRANS_COUNT / 2,
      DEFAULT_INSTR_COUNT / 2,
      DEFAULT_THREAD_COUNT,
      DEFAULT_WORD_COUNT / 2,
      DEFAULT_FLIP_PROB,
      0,
      DEFAULT_REF_ARRAY_FACTORY).runTest
  }

  test("default flipper test", Slow) {
    Config(
      DEFAULT_SYNC_COUNT,
      DEFAULT_TRANS_COUNT,
      DEFAULT_INSTR_COUNT,
      DEFAULT_THREAD_COUNT,
      DEFAULT_WORD_COUNT,
      DEFAULT_FLIP_PROB,
      0,
      DEFAULT_REF_ARRAY_FACTORY).runTest
  }

  test("small flipper test w/TArray") {
    Config(
      DEFAULT_SYNC_COUNT,
      DEFAULT_TRANS_COUNT / 2,
      DEFAULT_INSTR_COUNT / 2,
      DEFAULT_THREAD_COUNT,
      DEFAULT_WORD_COUNT / 2,
      DEFAULT_FLIP_PROB,
      0,
      { (n: Int) => TArray.ofDim[Int](n).refs }).runTest
  }

  test("default flipper test w/TArray", Slow) {
    Config(
      DEFAULT_SYNC_COUNT,
      DEFAULT_TRANS_COUNT,
      DEFAULT_INSTR_COUNT,
      DEFAULT_THREAD_COUNT,
      DEFAULT_WORD_COUNT,
      DEFAULT_FLIP_PROB,
      0,
      { (n: Int) => TArray.ofDim[Int](n).refs }).runTest
  }

  test("random flipper test", Slow) {
    for (i <- 0 until 1) {
      Config(
        DEFAULT_SYNC_COUNT,
        DEFAULT_TRANS_COUNT,
        DEFAULT_INSTR_COUNT,
        DEFAULT_THREAD_COUNT,
        DEFAULT_WORD_COUNT,
        DEFAULT_FLIP_PROB,
        System.currentTimeMillis + System.nanoTime,
        DEFAULT_REF_ARRAY_FACTORY).runTest
    }
  }

  case class Config(syncCount: Int,
                    transCount: Int,
                    instrCount: Int,
                    threadCount: Int,
                    wordCount: Int,
                    flipProb: Float,
                    randSeed: Long,
                    refArrayFactory: Int => IndexedSeq[Ref[Int]]) {

    private val len = syncCount*transCount*instrCount*threadCount
    private val rand = new java.util.Random(randSeed)
    val R = Array.tabulate(len)({ _ => rand.nextInt(wordCount) })
    val F = Array.tabulate(len)({ _ => rand.nextDouble() < flipProb })
     
    def index(id: Int, sync: Int, trans: Int, instr: Int) = {
      ((id*syncCount+sync)*transCount+trans)*instrCount+instr;
    }

    def runTest {
      println(this)

      print("computing sequentially...")
      Console.flush

      val P = Array.tabulate[Ref[Boolean]](len)({ _ => Ref(false) })
      val expected = computeSequential(this, P)

      print("\ncomputing in parallel with transactions...")
      Console.flush()
      
      val actual = computeParallelTxn(this, P)

      println()      
      for (i <- 0 until expected.length) {
        assert(expected(i).single.get === actual(i).single.get)
      }
    }
  }

  abstract class FlipperTask(val config: Config,
                             val A: IndexedSeq[Ref[Int]],
                             val P: Array[Ref[Boolean]],
                             val computeP: Boolean,
                             val id: Int,
                             val sync: Int) extends (() => Unit) {
    def doWork(task: => Unit)

    def read[T](ref: Ref[T]): T
    def write[T](ref: Ref[T], v: T)

    def apply() {
      val mask = 1 << id
      for (trans <- 0 until config.transCount) {
        doWork {
          for (instr <- 0 until config.instrCount) {
            val i = config.index(id, sync, trans, instr)
            val target = config.R(i)
            val a = read(A(target))
            val p = (a & mask) != 0
            if (computeP) {
              write(P(i), p)
            }
            else {
              assert(read(P(i)) === p)
            }
            if (config.F(i)) {
              // do some work before storing to A, to increase probability of a conflict
              var h = a
              var j = 0
              while (j < 10000) {
                h |= 1+((h >>> 1)^(h*13))
                j += 1
              }
              if (h == a) println("?")
              write(A(target), a ^ mask)
            }
          }
        }
        //println("thread " + id + " transaction " + trans + " completed (" + computeP + ")")
      }
    }
  }

  def computeSequential(config: Config, P: Array[Ref[Boolean]]): Array[Ref[Int]] = {
    val A = Array.tabulate[Ref[Int]](config.wordCount) { _ => Ref(0) }
    for (sync <- 0 until config.syncCount) {
      for (thread <- 0 until config.threadCount) {
        (new FlipperTask(config, A, P, true, thread, sync) {
          def read[T](ref: Ref[T]): T = ref.single()
          def write[T](ref: Ref[T], v: T) { ref.single() = v }
          def doWork(task: => Unit) { task }
        })()
      }
    }
    A
  }

  def computeParallelTxn(config: Config, P: Array[Ref[Boolean]]): IndexedSeq[Ref[Int]] = {
    val A = config.refArrayFactory(config.wordCount)
    for (sync <- 0 until config.syncCount) {
      val tasks = (for (thread <- 0 until config.threadCount) yield {
        new FlipperTask(config, A, P, false, thread, sync) {
          implicit var txn: InTxn = null

          def read[T](ref: Ref[T]): T = ref()
          def write[T](ref: Ref[T], v: T) { ref() = v }
          def doWork(task: => Unit) {
            atomic { t =>
              txn = t
              task
            }
            txn = null
          }
        }
      })
      parallelRun(tasks)
    }
    A
  }

  private def parallelRun(tasks: Iterable[() => Unit]) {
    val barrier = new CyclicBarrier(tasks.size)
    var failure: Throwable = null
    val threads = for (task <- tasks.toList) yield new Thread {
      override def run {
        barrier.await
        try {
          task()
        } catch {
          case x => {
            x.printStackTrace
            failure = x
          }
        }
      }
    }
    for (t <- threads) t.start
    for (t <- threads) t.join
    if (null != failure)
      throw failure
  }
}
