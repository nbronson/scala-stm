/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ccstm

import scala.annotation.tailrec
import java.util.concurrent.atomic.{AtomicLong, AtomicReferenceFieldUpdater}

private[ccstm] object TxnLevelImpl {

  private val stateUpdater = new TxnLevelImpl(null, null).newStateUpdater
}

/** `TxnLevelImpl` bundles the data and behaviors from `AccessHistory.UndoLog`
 *  and `AbstractNestingLevel`, and adds handling of the nesting level status.
 *  Some of the internal states (see `state`) are not instances of
 *  `Txn.Status`, but rather record that this level is no longer current.
 */
private[ccstm] class TxnLevelImpl(val txn: InTxnImpl, val par: TxnLevelImpl)
        extends AccessHistory.UndoLog with skel.AbstractNestingLevel {
  import skel.RollbackError
  import TxnLevelImpl._

  val root: TxnLevelImpl = if (par == null) this else par.root

  /** If `state` is a `TxnLevelImpl`, then that indicates that this nesting
   *  level is an ancestor of `txn.currentLevel`; this is reported as a status
   *  of `Txn.Active`.
   *
   *  Child nesting levels can also have a `state` of:
   *   - null : after they have been merged (committed) into `par`;
   *   - `Txn.Active` : if they are active and `txn.currentLevel`; or
   *   - a `Txn.RolledBack` instance : if they have been rolled back.
   *  A state of null indicates that this nesting level's status is in
   *  lock-step with its parent.
   *
   *  In addition to instances of `TxnLevelImpl`, the root nesting level can
   *  have any `Txn.Status` instance as its `state`.
   */
  @volatile private var _state: AnyRef = Txn.Active

  private def newStateUpdater: AtomicReferenceFieldUpdater[TxnLevelImpl, AnyRef] = {
    AtomicReferenceFieldUpdater.newUpdater(classOf[TxnLevelImpl], classOf[AnyRef], "_state")
  }

  /** True if anybody is waiting for `status.completed`. */
  @volatile private var _waiters = false

  def status: Txn.Status = _state match {
    case null => par.status
    case s: Txn.Status => s
    case _ => Txn.Active
  }

  def status_=(v: Txn.Status) {
    _state = v
    if (v.completed)
      notifyCompleted()
  }

  def statusCAS(v0: Txn.Status, v1: Txn.Status): Boolean = {
    val f = stateUpdater.compareAndSet(this, v0, v1)
    if (f && v1.completed)
      notifyCompleted()
    f
  }

  /** Equivalent to `status` if this level is the current level, otherwise
   *  the result is undefined.
   */
  def statusAsCurrent: Txn.Status = _state.asInstanceOf[Txn.Status]

  private def notifyCompleted() {
    if (_waiters)
      synchronized { notifyAll() }
  }

  /** Blocks until `status.completed`. */
  def awaitCompleted() {
    if (par != null)
      throw new IllegalStateException("awaitCompleted() is only supported for root levels")

    _waiters = true

    var interrupted = false
    synchronized {
      while (!status.completed) {
        try {
          wait
        } catch {
          case _: InterruptedException => interrupted = true
        }
      }
    }
    if (interrupted)
      Thread.currentThread.interrupt()
  }


  def requireActive() {
    if (_state ne Txn.Active)
      slowRequireActive()
  }

  private def slowRequireActive() {
    status match {
      case Txn.RolledBack(_) => throw RollbackError
      case s => throw new IllegalStateException(s.toString)
    }
  }

  def pushIfActive(child: TxnLevelImpl): Boolean = {
    stateUpdater.compareAndSet(this, Txn.Active, child)
  }

  def attemptMerge(): Boolean = {
    // First we need to set the current state to forwarding.  Regardless of
    // whether or not this fails we still need to unlink the parent.
    val f = (_state eq Txn.Active) && stateUpdater.compareAndSet(this, Txn.Active, null)

    // We must use CAS to unlink ourselves from our parent, because we race
    // with remote cancels.
    if (par._state eq this)
      stateUpdater.compareAndSet(par, this, Txn.Active)

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

  @tailrec private def rollbackImpl(rb: Txn.RolledBack): Txn.Status = _state match {
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
    case before if stateUpdater.compareAndSet(this, before, rb) => {
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
