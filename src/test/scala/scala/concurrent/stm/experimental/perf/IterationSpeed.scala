/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// IterationSpeed

package scala.concurrent.stm.experimental.perf


object IterationSpeed {

  val AddPct = System.getProperty("add-pct", "20").toInt
  val RemovePct = System.getProperty("remove-pct", "10").toInt
  val numOPs = System.getProperty("num-ops", "1000000").toInt
  val iterITV = System.getProperty("iteration-itv", "100000").toInt 
  val getPct = 100 - AddPct - RemovePct

  println("IterationSpeed.AddPct = " + AddPct)
  println("IterationSpeed.RemovePct = " + RemovePct)
  println("IterationSpeed.getPct = " + getPct)
  println("IterationSpeed.num-ops = " + numOPs)
  println("IterationSpeed.iteration-itv = " + iterITV)
  
}


class IterationSpeed extends Perf.Worker {

  import IterationSpeed._

  { IterationSpeed.AddPct }

  var rng: scala.util.Random = null
  var tid = 0


  override def setup(id: Int, size: Int, numThreads: Int, targetType: String, master: Perf.Worker) {
    super.setup(id, size, numThreads, targetType, master)
    rng = new scala.util.Random(id)
    tid = id;
  }

   def run(warmup: Boolean, pass: Int) {
      for (i <- 1 to numOPs) {
      val r = rng.nextInt(100)
      val k = rng.nextInt(size)
      if (r < AddPct) {
        doPut(k, "value")
      } else if (r < AddPct + RemovePct) {
        doRemove(k)
      } else {
        doGet(k)
      }
      if((i % iterITV) == (tid % 10) * 10000) {
        doIteration
      }
    }
   }
}
