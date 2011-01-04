/* scala-stm - (c) 2009-2011, Stanford University, PPL */

package scala.concurrent.stm
package ccstm

import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import skel.{AbstractNestingLevel, RollbackError}

private[ccstm] object TxnLevelImpl {

  private val stateUpdater = new TxnLevelImpl(null, null, false).newStateUpdater
}

/** `TxnLevelImpl` bundles the data and behaviors from `AccessHistory.UndoLog`
 *  and `AbstractNestingLevel`, and adds handling of the nesting level status.
 *
 *  @author Nathan Bronson
 */
private[ccstm] class TxnLevelImpl(val txn: InTxnImpl,
                                  val parUndo: TxnLevelImpl,
                                  val phantom: Boolean)
        extends AccessHistory.UndoLog with AbstractNestingLevel {
  import TxnLevelImpl.stateUpdater

  // this is the first non-hidden parent
  val parLevel: AbstractNestingLevel = if (parUndo == null || !parUndo.phantom) parUndo else parUndo.parLevel

  val root: AbstractNestingLevel = if (parLevel == null) this else parLevel.root

  /** To make `requireActive` more efficient we store the raw state of
   *  `Txn.Active` as null.  If the raw state is a `TxnLevelImpl`, then that
   *  indicates that this nesting level is an ancestor of `txn.currentLevel`;
   *  this is reported as a status of `Txn.Active`.  The raw state can also be
   *  the interned string value "merged" to indicate that the status is now in
   *  lock-step with the parent.
   */
  @volatile private var _state: AnyRef = null

  private def newStateUpdater: AtomicReferenceFieldUpdater[TxnLevelImpl, AnyRef] = {
    AtomicReferenceFieldUpdater.newUpdater(classOf[TxnLevelImpl], classOf[AnyRef], "_state")
  }

  /** True if anybody is waiting for `status.completed`. */
  @volatile private var _waiters = false

  @tailrec final def status: Txn.Status = {
    val raw = _state
    if (raw == null)
      Txn.Active // we encode active as null to make requireActive checks smaller
    else if (raw eq "merged")
      parUndo.status
    else if (raw.isInstanceOf[TxnLevelImpl])
      Txn.Active // child is active
    else
      raw.asInstanceOf[Txn.Status]
  }

  def setCommitting() {
    _state = Txn.Committing
  }

  def setCommitted() {
    _state = Txn.Committed
    notifyCompleted()
  }

  def tryActiveToCommitted(): Boolean = {
    val f = stateUpdater.compareAndSet(this, null, Txn.Committed)
    if (f)
      notifyCompleted()
    f
  }

  def tryActiveToPreparing(): Boolean = stateUpdater.compareAndSet(this, null, Txn.Preparing)

  def tryPreparingToPrepared(): Boolean = stateUpdater.compareAndSet(this, Txn.Preparing, Txn.Prepared)

  def tryPreparingToCommitting(): Boolean = stateUpdater.compareAndSet(this, Txn.Preparing, Txn.Committing)

  /** Equivalent to `status` if this level is the current level, otherwise
   *  the result is undefined.
   */
  def statusAsCurrent: Txn.Status = {
    val raw = _state
    if (raw == null)
      Txn.Active
    else
      raw.asInstanceOf[Txn.Status]
  }

  private def notifyCompleted() {
    if (_waiters)
      synchronized { notifyAll() }
  }

  /** Blocks until `status.completed`. */
  @throws(classOf[InterruptedException])
  def awaitCompleted() {
    assert(parUndo == null)

    _waiters = true

    if (Stats.top != null)
      Stats.top.blockingAcquires += 1

    synchronized {
      while (!status.completed)
        wait
    }
  }


  def requireActive() {
    if (_state != null)
      slowRequireActive()
  }

  private def slowRequireActive() {
    status match {
      case Txn.RolledBack(_) => throw RollbackError
      case s => throw new IllegalStateException(s.toString)
    }
  }

  def pushIfActive(child: TxnLevelImpl): Boolean = {
    stateUpdater.compareAndSet(this, null, child)
  }

  def attemptMerge(): Boolean = {
    // First we need to set the current state to forwarding.  Regardless of
    // whether or not this fails we still need to unlink the parent.
    val f = (_state == null) && stateUpdater.compareAndSet(this, null, "merged")

    // We must use CAS to unlink ourselves from our parent, because we race
    // with remote cancels.
    if (parUndo._state eq this)
      stateUpdater.compareAndSet(parUndo, this, null)

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

  @tailrec private def rollbackImpl(rb: Txn.RolledBack): Txn.Status = {
    val raw = _state
    if (raw == null || canAttemptLocalRollback(raw)) {
      // normal case
      if (stateUpdater.compareAndSet(this, raw, rb)) {
        notifyCompleted()
        rb
      } else
        rollbackImpl(rb) // retry
    } else if (raw eq "merged") {
      // we are now taking our status from our parent
      parUndo.rollbackImpl(rb)
    } else if (raw.isInstanceOf[TxnLevelImpl]) {
      // roll back the child first, then retry
      raw.asInstanceOf[TxnLevelImpl].rollbackImpl(rb)
      rollbackImpl(rb)
    } else {
      // request denied
      raw.asInstanceOf[Txn.Status]
    }
  }

  private def canAttemptLocalRollback(raw: AnyRef): Boolean = raw match {
    case Txn.Prepared => InTxnImpl.get eq txn // remote cancel is not allowed while preparing
    case s: Txn.Status => !s.decided
    case ch: TxnLevelImpl => ch.rolledBackOrMerged
    case _ => false
  }

  private def rolledBackOrMerged = _state match {
    case "merged" => true
    case Txn.RolledBack(_) => true
    case _ => false
  }
}
