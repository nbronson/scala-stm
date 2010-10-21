/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ccstm

import annotation.tailrec
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

object TxnLevelImpl {
  val nextId = new AtomicLong
}

class TxnLevelImpl(val txn: InTxnImpl, val par: TxnLevelImpl) extends skel.AbstractNestingLevel with AccessHistory.UndoLog {
  import TxnLevelImpl._

  lazy val id = nextId.incrementAndGet

  val root = if (par == null) this else par.root

  /** If this is the current level of txn, then `localStatusRef.get` will be
   *  `Txn.Active`.  Once it is merged into the parent then the local status
   *  will be null, in which case the parent's status should be used instead.
   */
  val localStatusRef = new AtomicReference[Txn.Status](Txn.Active)

  def status: Txn.Status = {
    val s = localStatusRef.get
    if (s != null) s else par.status
  }

  def checkAccess() {
    if (localStatus.get != Left(InTxnImpl.Active))
      throw new IllegalStateException
  }


  @tailrec private def requestRollback(rb: InTxnImpl.RolledBack, local: Boolean): InTxnImpl.Status = {
    localStatus.get match {
      case Left(rb @ InTxnImpl.RolledBack(_)) => rb // nothing to do
      case Left(InTxnImpl.MergedIntoParent) => _mergedInto.requestRollback(rb, local) // roll back parent
      case Left(InTxnImpl.Prepared) if !local => InTxnImpl.Prepared // not allowed
      case ls => {
        ls match {
          case Left(_) =>
          case Right(ch) => ch.requestRollback(rb, local)
        }
        if (localStatus.compareAndSet(ls, Left(rb)))
          rb // success
        else
          requestRollback(rb, local) // retry
      }
    }
  }

  def requestRollback(cause: Txn.RollbackCause): Txn.Status = {
    val local = DefaultContext.current.get == ctx
    requestRollback(InTxnImpl.RolledBack(cause), local)
  }

  
}
