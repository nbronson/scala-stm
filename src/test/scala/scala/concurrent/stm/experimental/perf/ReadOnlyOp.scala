package scala.concurrent.stm.experimental.perf

/* ReadOnlyOp
 *
 * Copyright 2010 Nathan Bronson and Stanford University.
 */

import scala.concurrent.stm._
import impl.FastSimpleRandom

object ReadOnlyOp {
  val HitRate = System.getProperty("hit-rate", "50").toInt
  val TxnSize = System.getProperty("txn-size", "2").toInt
  val TxnOpPct = System.getProperty("txn-op-pct", "0").toInt

  println("ReadOnlyOp.HitRate = " + HitRate)
  println("ReadOnlyOp.TxnSize = " + TxnSize)
  println("ReadOnlyOp.TxnOpPct = " + TxnOpPct)
}

class ReadOnlyOp extends Perf.Worker {
  import ReadOnlyOp._

  { ReadOnlyOp.HitRate }

  var rng: FastSimpleRandom = null

  override def setup(id: Int, size: Int, numThreads: Int, targetType: String, master: Perf.Worker) {
    super.setup(id, size, numThreads, targetType, master)
    rng = new FastSimpleRandom(id)
  }

  override def reset {
    super.reset
    val values = Array.tabulate(1024) { i => "x"+i }
    for (i <- 0 until ((size * HitRate) / 100)) {
      doPut(i, values(rng.nextInt(values.length)))
    }
  }

  def run(warmup: Boolean, pass: Int) {
    var i = 0
    //println(master.target.nonTxn.size)
    while (i < 1000000) {
      if (TxnOpPct > 0 && (TxnOpPct == 100 || rng.nextInt(100 * TxnSize) < TxnOpPct)) {
        val rngOrig = rng

        var a = 0
        atomic { implicit t =>
          rng = rngOrig.clone

          a += 1
          if (a == 100) println("livelock")

          var j = 0
          while (j < TxnSize) {
            doTxnGet(rng.nextInt(size))
            j += 1
          }
        }
        i += TxnSize
      } else {
        doGet(rng.nextInt(size))
        i += 1
      }
    }
  }
}
