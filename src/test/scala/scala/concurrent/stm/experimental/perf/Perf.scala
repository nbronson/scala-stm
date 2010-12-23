/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// Perf

package scala.concurrent.stm
package experimental
package perf

import java.util.concurrent.CyclicBarrier
import management.{GarbageCollectorMXBean, ManagementFactory}
import scala.collection.mutable.Map
import scala.concurrent.stm.experimental.impl.TMapFactory
import scala.concurrent.stm._

object Perf {
  {
    Runtime.getRuntime.addShutdownHook(new Thread("printHeapStatus shutdown hook") {
      override def run { printHeapStatus }
    })
  }


  object Op extends Enumeration {
    type Op = Value
    val NoOp = Value(0, "no_op")
    val PutOp = Value(1, "put")
    val RemoveOp = Value(2, "remove")
    val ClearOp = Value(3, "clear")
    val GetOp = Value(4, "get")
    val HigherOp = Value(5, "higher")
    val BarrierOp = Value(6, "barrier")
    val IterationOp = Value(7,"iteration")
  }
  import Op._


  def main(args: Array[String]) {
    var numThreads = 1
    var warmupPasses = 10
    var passes = 10
    var size = 10000
    var mergePasses = false
    val sampleIntervalMillis = 50

    if (args.length < 2) exitUsage

    for (arg <- args.take(args.length - 2)) {
      val v = Integer.parseInt(arg.substring(arg.indexOf('=') + 1))
      if (arg.startsWith("--threads=")) numThreads = v
      else if (arg.startsWith("--warmup=")) warmupPasses = v
      else if (arg.startsWith("--passes=")) passes = v
      else if (arg.startsWith("--size=")) size = v
      else if (arg.startsWith("--merge-passes=")) mergePasses = (v != 0)
      else exitUsage
    }

    val targetType = args(args.length - 2)

    val workerClass = (try {
      Class.forName(args(args.length - 1))
    } catch {
      case _ => Class.forName("scala.concurrent.stm.experimental.perf." + args(args.length - 1))
    }).asInstanceOf[Class[Worker]]

    var master: Worker = null    
    val workers = Array.tabulate(numThreads) { id => 
      val w: Worker = workerClass.newInstance
      if (master == null) master = w
      w.setup(id, size, numThreads, targetType, master)
      w.currentOp = BarrierOp
      w
    }

    println("args=" + args.mkString(" "))
    printVMConfig

    println()
    println("numThreads=" + numThreads)
    println("warmupPasses=" + warmupPasses)
    println("passes=" + passes)
    println("size=" + size)
    println("mergePasses=" + mergePasses)

    val barrier = new CyclicBarrier(numThreads, new Runnable() {
      var pass = 0
      def run {
        val w = if (mergePasses) 1 else warmupPasses
        val p = w + (if (mergePasses) 1 else passes)
        workers(0).reset
        if (pass == 0) {
          print("warming up   ")
        } else if (pass < w) {
          if (((pass-1) * 50) / warmupPasses != (pass * 50) / warmupPasses) print(".")
        } else if (pass == w) {
          print("\ntimed passes ")
          Worker.startSampling(sampleIntervalMillis)
        } else if (pass < p) {
          val pp = pass - warmupPasses
          if (((pp-1) * 50) / passes != (pp * 50) / passes) print(".")          
        } else {
          Worker.stopSampling
          println()
        }
        pass += 1
      }
    })

    val threads = for (w <- workers) yield {
      val t = new Thread("worker " + w.id) {
        override def run {
          for (p <- 0 until warmupPasses) {
            if (p == 0 || !mergePasses) {
              w.currentOp = BarrierOp
              barrier.await
              w.currentOp = NoOp
            }
            w.run(true, p)
          }
          w.counts.clear
          for (p <- 0 until passes) {
            if (p == 0 || !mergePasses) {
              w.currentOp = BarrierOp
              barrier.await
              w.counts += BarrierOp
              w.currentOp = NoOp
            }
            w.run(false, p)
          }
          w.currentOp = BarrierOp
          barrier.await
        }
      }
      t.start
      t
    }
    
    for (t <- threads) t.join

    printResults

    // try to get all of the SoftRef-s to clean up
    System.gc
    Thread.sleep(1000)
  }

  def exitUsage {                             
    println("scala scala.concurrent.stm.experimental.perf.Perf [options] map_type TestClass\n" +
            "Options:\n" +
            "  --threads=N (default 1)\n" +
            "  --warmup=N (default 10)\n" +
            "  --passes=N (default 10)\n" +
            "  --size=N (default 10000)\n" +
            "  --merge-passes=(0|1) (default 0)")
    System.exit(1)
  }

  def printVMConfig {
    for (k <- List("java.vm.name", "java.runtime.version", "os.name", "os.version", "os.arch")) {
      println(k + " = " + System.getProperty(k))
    }

    val gcs = ManagementFactory.getGarbageCollectorMXBeans
    if (gcs.isEmpty) {
      println("no GC MXBeans, G1?")
    } else {
      for (e <- gcs.toArray) {
        val gc = e.asInstanceOf[GarbageCollectorMXBean]
        println("gc[" + gc.getName + "].pools = " + gc.getMemoryPoolNames.mkString("{", ", ", "}"))
      }
    }
  }

  def printHeapStatus {
    val heapUsage = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
    println("\nDATA heap: used_k " + (heapUsage.getUsed / 1024) +
            "  committed_k " + (heapUsage.getCommitted / 1024) +
            "  max_k " + (heapUsage.getMax / 1024))
  }

  def printResults {
    val elapsedMillis = Worker.sampleEnd - Worker.sampleBegin

    val totalCounts = new OpCounts
    val totalBusySamples = new OpCounts
    for (w <- Worker.workers.reverse) {
      printResult("worker" + w.id, w.counts, w.busySamples, elapsedMillis)
      totalCounts ++= w.counts
      totalBusySamples ++= w.busySamples
    }
    println()
    println("DATA total:  elapsed_millis " + elapsedMillis)
    printResult("total", totalCounts, totalBusySamples, elapsedMillis)

    print("DATA total:")
    val useful = totalBusySamples.sum - totalBusySamples(BarrierOp)
    for (op <- List(PutOp, RemoveOp, ClearOp, GetOp, HigherOp)) {
      val count = totalCounts(op)
      val secs = (totalBusySamples(op) * elapsedMillis * 0.001) / useful
      val rate = count / secs
      printf("  %s_per_sec %1.0f", op, rate)
    }
    val usefulOps = totalCounts.sum - totalCounts(BarrierOp)
    println()
    printf("DATA total:  useful_ops %d  throughput %1.0f\n", usefulOps, usefulOps * 1000.0 / elapsedMillis)
  }

  def printResult(name: String, counts: OpCounts, samples: OpCounts, elapsedMillis: Long) {
    println("DATA " + name + ":  " + counts.mkString("_count"))

    val s = samples.clone
    val useful = samples.sum - samples(BarrierOp)
    if (useful > 0) {
      s *= elapsedMillis
      s /= useful
    }
    println("DATA " + name + ":  " + s.mkString("_millis"))
  }

  class OpCounts {
    val data = new Array[Long](Op.maxId + 1)

    override def clone = {
      val z = new OpCounts
      data.copyToArray(z.data, 0)
      z
    }

    def clear { for (i <- 0 until data.length) data(i) = 0 }

    def apply(op: Op) = data(op.id)
    def += (op: Op) { data(op.id) = data(op.id) + 1 }
    def weightedAdd(op: Op, weight: Long) { data(op.id) = data(op.id) + weight }

    def *= (v: Long) { for (i <- 0 until data.length) data(i) = data(i) * v }
    def /= (v: Long) { for (i <- 0 until data.length) data(i) = data(i) / v }

    def sum = data.reduceLeft(_+_)

    def ++= (rhs: OpCounts) {
      for (i <- 0 until data.length) data(i) = data(i) + rhs.data(i)
    }

    def mkString(opSuffix: String) = {
      (for (op <- Op.values) yield (op + opSuffix + " " + data(op.id))).mkString("  ")
    }

    override def toString = mkString("")
  }


  object Worker {
    @volatile private var _workers: List[Worker] = Nil
    @volatile private var _sampler: Thread = null

    @volatile var sampleBegin: Long = 0
    @volatile var sampleEnd: Long = 0 

    def workers = _workers

    def addWorker(w: Worker) {
      Worker.synchronized {
        _workers = w :: _workers
      }
    }

    def sample { for (w <- workers) w.sample }

    def startSampling(intervalMillis: Int) {
      Worker.synchronized {
        if (_sampler == null) {
          _sampler = new Thread("Sampler") {
            override def run {
              while (_sampler == this) {
                sample
                Thread.sleep(intervalMillis)
              }
            }
          }
          _sampler.setDaemon(true)
          _sampler.start

          sampleBegin = System.currentTimeMillis
        }
      }
    }

    def stopSampling {
      sampleEnd = System.currentTimeMillis

      val prev = Worker.synchronized {
        val s = _sampler
        _sampler = null
        s
      }
      if (prev != null) prev.join
    }
  }

  abstract class Worker {
    var id = -1
    var size = -1
    var numThreads = -1
    var targetType: String = null
    var target: TMap[Int,String] = null
    var master: Worker = null
    
    @volatile var currentOp: Op = NoOp
    val counts = new OpCounts
    val busySamples = new OpCounts

    { Worker.addWorker(this) }

    def setup(id: Int, size: Int, numThreads: Int, targetType: String, master: Worker) {
      this.id = id
      this.size = size
      this.numThreads = numThreads
      this.targetType = targetType
      this.master = master
    }

    def sample { busySamples += currentOp }

    def doPut(key: Int, value: String) {
      counts += PutOp
      currentOp = PutOp
      master.target.single(key) = value
      currentOp = NoOp
    }

    def doRemove(key: Int) {
      counts += RemoveOp
      currentOp = RemoveOp
      master.target.single -= key
      currentOp = NoOp
    }

    def doClear {
      counts += ClearOp
      currentOp = ClearOp
      master.target.single.clear
      currentOp = NoOp
    }

    def doGet(key: Int): Option[String] = {
      counts += GetOp
      currentOp = GetOp
      val z = master.target.single.get(key)
      currentOp = NoOp
      z
    }

//    def doHigher(key: Int): Option[(Int,String)] = {
//      counts += HigherOp
//      currentOp = HigherOp
//      val z = master.target.single.higher(key)
//      currentOp = NoOp
//      z
//    }

    def doTxnPut(key: Int, value: String)(implicit txn: InTxn) {
      counts += PutOp
      currentOp = PutOp
      master.target(key) = value
      currentOp = NoOp
    }

    def doTxnRemove(key: Int)(implicit txn: InTxn) {
      counts += RemoveOp
      currentOp = RemoveOp
      master.target -= key
      currentOp = NoOp
    }

    def doTxnGet(key: Int)(implicit txn: InTxn): Option[String] = {
      counts += GetOp
      currentOp = GetOp
      val z = master.target.get(key)
      currentOp = NoOp
      z
    }

//    def doTxnHigher(key: Int)(implicit txn: InTxn): Option[(Int,String)] = {
//      counts += HigherOp
//      currentOp = HigherOp
//      val z = master.target.bind.higher(key)
//      currentOp = NoOp
//      z
//    }
//
//    def doIteration {
//      //if (target.isInstanceOf[SnapMap[_,_]]) println(target)
//      var count = 0
//      currentOp = IterationOp
//      atomic { implicit t =>
//        //currentTxn.afterCompletion(t => { println(t.status + ", barging=" + t.barging)})
//
//        count = 0
//        val iter = master.target.bind.iterator
//        while (iter.hasNext) {
//          count += 1
//          iter.next()
//        }
//      }
//      currentOp = NoOp
//      counts.weightedAdd(IterationOp, count)
//    }

    ////////////// override this

    /** Called on all workers. */
    def run(warmup: Boolean, pass: Int)

    /** Called on only one worker. */
    def reset {
      assert(this == master)
      target = TMapFactory[Int,String](targetType)
    }
  }
}
