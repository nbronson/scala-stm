/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm
package ccstm

import java.util.concurrent.atomic.AtomicLong


private[ccstm] trait GV6 {

  /** The global timestamp.  We use TL2's GV6 scheme to avoid the need to
   *  increment this during every transactional commit.  Non-transactional
   *  writes are even more conservative, incrementing the global version only
   *  when it lags the local version by a (configurable) fixed amount.  This
   *  helps reduce contention (especially when there are many non-transactional
   *  writes), but it means we must always validate transactions that are not
   *  read-only.
   */
  val globalVersion = new AtomicLong(1)

  /** The approximate ratio of the number of commits to the number of
   *  increments of `globalVersion`, as in TL2's GV6 scheme.  If
   *  greater than one, the actual choice to advance or not is made with a
   *  random number generator.
   */
  private val silentCommitRatio = ((Runtime.getRuntime.availableProcessors + 1) / 2) min 16

  /** If `x` is a signed integer evenly chosen from a uniform distribution
   *  between Integer.MIN_VALUE and Integer.MAX_VALUE, then the test
   *  `(x <= silentCommitCutoff)` will succeed approximately
   *  `1.0 / silentCommitRatio` of the time.
   */
  private val silentCommitCutoff = {
    ((1 << 31) + ((1L << 32) / silentCommitRatio) - 1).asInstanceOf[Int]
  }

  private val silentCommitRand = skel.FastSimpleRandom

  /** The maximum value of `nonTxnWriteVersion - globalVersion` that
   *  will be allowed before a non-transactional store attempts to increase
   *  `globalVersion`.  Any value larger than zero admits the
   *  possibility that a non-transactional write will leave a version number
   *  that forces revalidation of a transaction that discovers it (like a
   *  silently-committed txn under GV6).  Larger values can help amortize the
   *  cost of updating the counter.
   */
  private val nonTxnSilentRunAhead = System.getProperty("ccstm.nontxn.runahead", "32").toInt

  /** Returns a value that is greater than `prevVersion` and greater
   *  than the value of `globalVersion` on entry.  May increase
   *  `globalVersion`.
   */
  def nonTxnWriteVersion(prevVersion: Long): Long = {
    val g = globalVersion.get
    val result = math.max(g, prevVersion) + 1
    if (result > g + nonTxnSilentRunAhead) {
      globalVersion.compareAndSet(g, prevVersion + 1)
    }
    result
  }

  /** Returns a version to use for reading in a new transaction. */
  def freshReadVersion: Long = globalVersion.get

  /** Guarantees that `globalVersion.get` is &ge;
   *  `minRV`, and returns `globalVersion.get`.
   */
  def freshReadVersion(minRV: Long): Long = {
    var g = globalVersion.get
    while (g < minRV) {
      if (globalVersion.compareAndSet(g, minRV)) {
        return minRV
      }
      // failed, retry
      g = globalVersion.get
    }
    return g
  }

  /** Returns a value that is greater than `gvSnap` and greater than
   *  `readVersion`, possibly increasing `globalVersion`.
   */
  def freshCommitVersion(readVersion: Long, gvSnap: Long): Long = {
    val result = math.max(readVersion, gvSnap) + 1
    if (silentCommitRatio <= 1 || silentCommitRand.nextInt <= silentCommitCutoff) {
      globalVersion.compareAndSet(gvSnap, result)
      // ignore failure
    }
    result
  }  
}
