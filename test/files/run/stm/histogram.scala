/* scala-stm - (c) 2009-2010, Stanford University, PPL */


import java.util.concurrent.CyclicBarrier
import scala.concurrent.stm._
import scala.concurrent.stm.skel._
import scala.concurrent.stm.japi._
import scala.concurrent.stm.impl._


object Test {

  def test(name: String, slowTest: Boolean)(block: => Unit) {
    if (!slowTest) {
      println("running retry " + name)
      block
    }
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

    for ((opsPerTest, name, slow) <- List((10000, "10K", false),
                                          (1000000, "1M", true))) {
      for (buckets <- List(1, 30, 10000)) {
        for (threads <- List(1, 2, 4, 8, 16, 32, 64, 128, 256, 512) if (threads <= 2*Runtime.getRuntime.availableProcessors)) {
          for (useTArray <- List(false, true)) {
            val str = ("" + buckets + " buckets, " + threads + " threads, " +
                    (if (useTArray) "TArray[Int]" else "Array[Ref[Int]]"))
            test("single-op-txn, " + str + ", " + name, true) {
              histogram(buckets, threads, opsPerTest / threads, useTArray, 100, 1)
            }

            for ((accesses, slow2) <- List((1, true), (3, false), (100, true))) {
              test("txn, " + str + ", " + accesses + " incr per txn, " + name, true) {
                histogram(buckets, threads, opsPerTest / threads, useTArray, 0, accesses)
              }
              test("mix, " + str + ", " + accesses + " incr per txn " + name, slow || slow2) {
                histogram(buckets, threads, opsPerTest / threads, useTArray, 50, accesses)
              }
            }
          }
        }
      }
    }

    def histogram(bucketCount: Int,
                  workerCount: Int,
                  samplesPerWorker: Int,
                  useTArray: Boolean,
                  singlePct: Int,
                  samplesPerTxn: Int) {

      val buckets: IndexedSeq[Ref[Int]] = (if (useTArray) {
        TArray.ofDim[Int](bucketCount).refs
      } else {
        Array.tabulate(bucketCount)({ _ => Ref(0)})
      })
      val threads = new Array[Thread](workerCount)
      val barrier = new CyclicBarrier(workerCount, new Runnable {
        var start = 0L
        def run {
          val now = System.nanoTime
          if (start == 0) {
            start = now
          } else {
            val elapsed = now - start
            if (false) println("hist(" + bucketCount + "," + workerCount + "," + samplesPerWorker + "," +
              useTArray + "," + singlePct +
              "," + samplesPerTxn + ")  total_elapsed=" + elapsed + " nanos,  throughput=" +
              (samplesPerWorker * workerCount * 1000000000L) / elapsed + " ops/sec,  per_thread_latency=" +
              elapsed / samplesPerWorker + " nanos/op,  avg_arrival=" +
              elapsed / (samplesPerWorker * workerCount) + " nanos/op")
          }
        }
      })
      
      for (worker <- 0 until workerCount) {
        val work = new Runnable {
          def run {
            barrier.await
            var i = 0
            while (i < samplesPerWorker) {
              if (math.abs(hash(i, worker) % 100) < singlePct) {
                if ((i % 2) == 0) {
                  buckets(math.abs(hash(worker, i) % bucketCount)).single.transform(_ + 1)
                } else {
                  val nt = buckets(math.abs(hash(worker, i) % bucketCount)).single
                  var x = nt()
                  while (!nt.compareAndSet(x, x + 1)) x = nt()
                }
                i += 1
              } else {
                atomic { implicit t =>
                  var j = 0
                  while (j < samplesPerTxn && i + j < samplesPerWorker) {
                    val tv = buckets(math.abs(hash(worker, i + j) % bucketCount))
                    //tv.getAndTransform(_ + 1)
                    tv() = tv() + 1
                    //tv() = tv.bind.readForWrite + 1
                    j += 1
                  }
                }
                i += samplesPerTxn
              }
            }
            barrier.await
          }
        }
        if (worker < workerCount - 1) {
          threads(worker) = new Thread(work, "worker " + worker)
          threads(worker).start
        } else {
          work.run
        }
      }

      for (worker <- 0 until workerCount - 1) threads(worker).join

      val sum = buckets.map(_.single.get).reduceLeft(_+_)
      assert(samplesPerWorker * workerCount == sum)
    }

    def hash(i: Int, j: Int) = {
      var h = i * 37 + j * 101
      h ^= (h >>> 20) ^ (h >>> 12)
      h ^ (h >>> 7) ^ (h >>> 4)
    }
  }
}
