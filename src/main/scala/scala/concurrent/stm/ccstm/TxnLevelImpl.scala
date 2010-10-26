/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ccstm

import scala.annotation.tailrec
import java.util.concurrent.atomic.{AtomicLong, AtomicReferenceFieldUpdater}

private[ccstm] object TxnLevelImpl {
  val nextId = new AtomicLong

  val localStatusUpdater = new TxnLevelImpl(null, null).newLocalStatusUpdater
}

private[ccstm] class TxnLevelImpl(val txn: InTxnImpl, val par: TxnLevelImpl)
        extends AccessHistory.UndoLog with skel.AbstractNestingLevel {
  import skel.RollbackError
  import TxnLevelImpl._

  lazy val id = nextId.incrementAndGet

  val root: TxnLevelImpl = if (par == null) this else par.root

  /** If this is the current level of txn, then `localStatus` will be
   *  `Txn.Active`.  Once it is merged into the parent then the local status
   *  will be null, in which case the parent's status should be used instead.
   *  If this is a `TxnLevelImpl` instance then that is the current child.
   */
  @volatile var localStatus: AnyRef = Txn.Active

  @volatile var waiters = false

  private def notifyCompleted() {
    if (waiters)
      synchronized { notifyAll() }
  }

  def awaitCompleted() {
    if (par != null)
      throw new IllegalStateException("awaitCompleted() is only supported for root levels")

    waiters = true

    var interrupted = false
    try {
      synchronized {
        while (!status.completed) {
          try {
            wait
          } catch {
            case _: InterruptedException => interrupted = true
          }
        }
      }
    } finally {
      if (interrupted)
        Thread.currentThread.interrupt()
    }
  }

  def newLocalStatusUpdater: AtomicReferenceFieldUpdater[TxnLevelImpl, AnyRef] = {
    AtomicReferenceFieldUpdater.newUpdater(classOf[TxnLevelImpl], classOf[AnyRef], "localStatus")
  }

  def status: Txn.Status = localStatus match {
    case null => par.status
    case s: Txn.Status => s
    case _ => Txn.Active
  }

  def requireActive() {
    if (localStatus ne Txn.Active)
      slowRequireActive()
  }

  private def slowRequireActive() {
    status match {
      case Txn.RolledBack(_) => throw RollbackError
      case s => throw new IllegalStateException(s.toString)
    }
  }

  def pushIfActive(child: TxnLevelImpl): Boolean = {
    localStatusUpdater.compareAndSet(this, Txn.Active, child)
  }

  def statusCAS(v0: Txn.Status, v1: Txn.Status): Boolean = {
    val f = localStatusUpdater.compareAndSet(this, v0, v1)
    if (f && v1.completed)
      notifyCompleted()
    f
  }

  def attemptMerge(): Boolean = {
    // First we need to set the current state to forwarding.  Regardless of
    // whether or not this fails we still need to unlink the parent.
    val f = localStatusUpdater.compareAndSet(this, Txn.Active, null)

    // We must use CAS to unlink ourselves from our parent, because we race
    // with remote cancels.
    localStatusUpdater.compareAndSet(par, this, Txn.Active)

    f
  }

  /** Must be called from the transaction's thread. */
  def forceRollback(cause: Txn.RollbackCause) {
    val s = rollbackImpl(Txn.RolledBack(cause))
    assert(s.isInstanceOf[Txn.RolledBack])
  }

  def requestRollback(cause: Txn.RollbackCause): Txn.Status = {
    if (cause == Txn.ExplicitRetryCause)
      throw new IllegalArgumentException("explicit retry is not available via requestRollback")
    rollbackImpl(Txn.RolledBack(cause))
  }

  @tailrec private def rollbackImpl(rb: Txn.RolledBack): Txn.Status = localStatus match {
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
      notifyCompleted()
      rb
    }
    case _ => {
      // CAS failure, try again
      rollbackImpl(rb)
    }
  }

}
