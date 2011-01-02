/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm

import org.scalatest.FunSuite
import skel.FastSimpleRandom

class ContentionSuite extends FunSuite {
  // probability that two txns of size M touch the same element out of N
  // (M substantially less than N) is about 1 - (1 - M/N)^M

  for (numRefs <- List(1000, 100000)) {
    for (readPct <- List(80)) {
      for (numThreads <- List(4, 32)) {
        for (txnSize <- List(8, 64)) {
          for (nested <- List(false, true)) {
            val opsPerThread = 20000000 / numThreads
            val numReads = opsPerThread * readPct / 100
            val numWrites = opsPerThread - numReads
            val name = "%d refs, %d %% read, %d threads, %d ops/txn, nested=%s".format(
                numRefs, readPct, numThreads, txnSize, nested)
            test(name) {
              runTest(numRefs, numReads, numWrites, numThreads, txnSize, nested, name)
            }
          }
        }
      }
    }
  }

  /** Runs one thread per element of `txnSizes`. */
  private def runTest(numRefs: Int, numReads: Int, numWrites: Int, numThreads: Int, txnSize: Int, nested: Boolean, name: String) {
    val values = (0 until 37) map { i => "foo" + i }

    val refs = Array.tabulate(numRefs) { _ => Ref(values(0)) }

    val threads = for (t <- 0 until numThreads) yield new Thread {
      override def run {
        var rand = new FastSimpleRandom(hashCode)
        var i = 0
        while (i < numReads + numWrites) {
          val body: (InTxn => FastSimpleRandom) = { implicit txn =>
            val r = rand.clone
            var j = 0
            while (j < txnSize) {
              val key = r.nextInt(numRefs)
              val pct = r.nextInt(numReads + numWrites)
              if (pct < numReads)
                refs(key)()
              else
                refs(key)() = values(r.nextInt(values.length))
              j += 1
            }
            r
          }
          if (!nested)
            rand = atomic(body)
          else {
            rand = atomic { implicit txn =>
              atomic(body) orAtomic { implicit txn => throw new Error("execution should not reach here") }
            }
          }
          i += txnSize
        }
      }
    }

    val begin = System.currentTimeMillis
    for (t <- threads) t.start()
    for (t <- threads) t.join()
    val elapsed = System.currentTimeMillis - begin

    val nanosPerOp = elapsed * 1000000L / ((numReads + numWrites) * 1L * numThreads)
    printf("ContentionSuite: %s, %d nanos/op aggregate throughput\n", name, nanosPerOp)
  }

}