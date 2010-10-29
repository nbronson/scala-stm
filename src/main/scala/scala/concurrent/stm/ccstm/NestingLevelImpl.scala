/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ccstm

import java.util.concurrent.atomic.AtomicLong
import concurrent.stm.Txn.{RollbackCause, Status}

private[ccstm] object NestingLevelImpl {

  /** `NestingLevel` `id`s are generated sequentially and lazily.  If `id`s are
   *  used heavily this might be a contention point, in which case this could
   *  be augmented with a `ThreadLocal`.
   */
  private val nextId: AtomicLong = new AtomicLong
}

private[ccstm] class NestingLevelImpl extends NestingLevel {
  import NestingLevelImpl._

  lazy val id: Long = nextId.incrementAndGet()

  def parent: Option[NestingLevel] = null

  def root: NestingLevel = null

  def status: Status = null

  def requestRollback(cause: RollbackCause): Status = null
}

