/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// RandomOp

package scala.concurrent.stm.experimental.perf

import scala.concurrent.stm.experimental.TMap
import scala.concurrent.stm._
import impl.FastSimpleRandom

object RandomOp {
  val LeadingAdds = System.getProperty("leading-adds", "0").toInt
  val AddPct = System.getProperty("add-pct", "20").toInt
  val RemovePct = System.getProperty("remove-pct", "10").toInt
  val HigherPct = System.getProperty("higher-pct", "0").toInt
  val GetPct = 100 - AddPct - RemovePct - HigherPct
  val TxnSize = System.getProperty("txn-size", "2").toInt
  val TxnOpPct = System.getProperty("txn-op-pct", "0").toInt
  val TxnNoReadOnlyCommit = "1tTyY".indexOf((System.getProperty("txn-no-ro-commit", "") + "f").charAt(0)) >= 0

  println("RandomOp.LeadingAdds = " + LeadingAdds)
  println("RandomOp.AddPct = " + AddPct)
  println("RandomOp.RemovePct = " + RemovePct)
  println("RandomOp.HigherPct = " + HigherPct)
  println("RandomOp.GetPct = " + GetPct)
  println("RandomOp.TxnSize = " + TxnSize)
  println("RandomOp.TxnOpPct = " + TxnOpPct)
  println("RandomOp.TxnNoReadOnlyCommit = " + TxnNoReadOnlyCommit)

  val Values = Array.tabulate(1024)({ i => "x"+i })
}

private object DummyWriteResource extends Txn.WriteResource {
  def prepare(txn: Txn): Boolean = true
  def performCommit(txn: Txn) {}
  def performRollback(txn: Txn) {}
}

class RandomOp extends Perf.Worker {
  import RandomOp._

  { RandomOp.AddPct }

  var rng: FastSimpleRandom = null

  override def setup(id: Int, size: Int, numThreads: Int, targetType: String, master: Perf.Worker) {
    super.setup(id, size, numThreads, targetType, master)
    rng = new FastSimpleRandom(id)
  }

  def run(warmup: Boolean, pass: Int) {
    var i = 0
    while (i < 1000000) {
      if (TxnOpPct > 0 && (TxnOpPct == 100 || rng.nextInt(100 * TxnSize) < TxnOpPct)) {
        val rngOrig = rng

        var a = 0
        atomic { implicit currentTxn =>
          if (TxnNoReadOnlyCommit) currentTxn.addWriteResource(DummyWriteResource)
          rng = rngOrig.clone

          a += 1
          if (a == 100) println("livelock")

          var j = 0
          while (j < TxnSize) {
            val r = rng.nextInt(100)
            val k = rng.nextInt(size)
            if (r < AddPct || i < LeadingAdds) {
              doTxnPut(k, Values(rng.nextInt(Values.length)))
            } else if (r < AddPct + RemovePct) {
              doTxnRemove(k)
            } else if (r < AddPct + RemovePct + HigherPct) {
              doTxnHigher(k)
            } else {
              doTxnGet(k)
            }
            j += 1
          }
        }
        i += TxnSize
      } else {
        val r = rng.nextInt(100)
        val k = rng.nextInt(size)
        if (r < AddPct || i < LeadingAdds) {
          doPut(k, Values(rng.nextInt(Values.length)))
        } else if (r < AddPct + RemovePct) {
          doRemove(k)
        } else if (r < AddPct + RemovePct + HigherPct) {
          doHigher(k)
        } else {
          doGet(k)
        }
        i += 1
      }
    }
  }
}
