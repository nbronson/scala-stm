/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ccstm

import annotation.tailrec
import java.util.concurrent.atomic.{AtomicLong, AtomicReferenceFieldUpdater}

object TxnLevelImpl {
  val nextId = new AtomicLong

  val localStatusUpdater = new TxnLevelImpl(null, null).newLocalStatusUpdater
}

class TxnLevelImpl(val txn: InTxnImpl, val par: TxnLevelImpl)
        extends skel.AbstractNestingLevel with AccessHistory.UndoLog[TxnLevelImpl] {
  import TxnLevelImpl._

  lazy val id = nextId.incrementAndGet

  val root = if (par == null) this else par.root

  /** If this is the current level of txn, then `localStatus` will be
   *  `Txn.Active`.  Once it is merged into the parent then the local status
   *  will be null, in which case the parent's status should be used instead.
   *  If this is a `TxnLevelImpl` instance then that is the current child.
   */
  @volatile var localStatus: AnyRef = Txn.Active

  def newLocalStatusUpdater: AtomicReferenceFieldUpdater[AnyRef] = {
    AtomicReferenceFieldUpdater.newUpdater(classOf[TxnLevelImpl], classOf[AnyRef], "localStatus")
  }

  def status: Txn.Status = localStatus match {
    case null => par.status
    case s: Txn.Status => s
    case _ => Txn.Active
  }

  def checkAccess() {
    if (localStatus ne Txn.Active)
      slowCheckAccess()
  }

  private def slowCheckAccess() {
    localStatus match {
      case Txn.RolledBack(_) => throw RollbackError
      case _ => throw new IllegalStateException(status.toString)
    }
  }

  def pushIfActive(child: TxnLevelImpl): Boolean = {
    localStatusUpdater.compareAndSet(this, Txn.Active, child)
  }

  def popIfActive(child: TxnLevelImpl) {
    localStatusUpdater.compareAndSet(this, child, Txn.Active)
  }

  def requestRollback(cause: Txn.RollbackCause): Txn.Status = rollbackImpl(Txn.RolledBack(cause))

  @tailrec final def rollbackImpl(rb: Txn.RolledBack): Txn.Status = localStatus match {
    case null => {
      // already merged with parent, roll back both
      par.rollbackImpl(rb)
    }
    case ch: TxnLevelImpl if !ch.status.isInstanceOf[Txn.RolledBack] => {
      // roll back the child first, then try again
      ch.rollbackImpl(rb)
      rollbackImpl(rb)
    }
    case s: Txn.Status if s.decided || (s == Txn.Prepared && (InTxnImpl.get ne txn)) => {
      // can't roll back or already rolled back
      s
    }
    case before if localStatusUpdater.compareAndSet(this, before, rb) => {
      // success!
      rb
    }
    case _ => {
      // CAS failure, try again
      rollbackImpl(rb)
    }
  }

}
