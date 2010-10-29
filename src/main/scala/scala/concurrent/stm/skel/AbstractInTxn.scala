/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package skel

import collection.mutable.ArrayBuffer

object AbstractInTxn {
  trait UndoLog {
    var prevBeforeCommitSize = 0
    var prevWhileValidatingSize = 0
    var prevWhilePreparingSize = 0
    var prevWhileCommittingSize = 0
    var prevAfterCommitSize = 0
    var prevAfterRollbackSize = 0
  }
}

trait AbstractInTxn extends InTxn {
  import Txn.{Status, Active, RolledBack, UncaughtExceptionCause, ExternalDecider}
  
  //////////// implementation of functionality for the InTxn implementer

  protected def requireActive() {
    status match {
      case Active =>
      case RolledBack(_) => throw RollbackError
      case s => throw new IllegalStateException(s.toString)
    }
  }

  protected def requireNotDecided() {
    status match {
      case s if !s.decided =>
      case RolledBack(_) => throw RollbackError
      case s => throw new IllegalStateException(s.toString)
    }
  }

  protected def requireNotCompleted() {
    status match {
      case s if !s.completed =>
      case RolledBack(_) => throw RollbackError
      case s => throw new IllegalStateException(s.toString)
    }
  }

  private var _decider: ExternalDecider = null
  protected def externalDecider = _decider

  protected val beforeCommitList = new CallbackList[InTxn]
  protected val whileValidatingList = new ArrayBuffer[() => Boolean]
  protected val whilePreparingList = new CallbackList[InTxnEnd]
  protected val whileCommittingList = new CallbackList[InTxnEnd]
  protected val afterCommitList = new CallbackList[Status]
  protected val afterRollbackList = new CallbackList[Status]

  protected def checkpointCallbacks(dst: AbstractInTxn.UndoLog) {
    dst.prevBeforeCommitSize = beforeCommitList.size
    dst.prevWhileValidatingSize = whileValidatingList.size
    dst.prevWhilePreparingSize = whilePreparingList.size
    dst.prevWhileCommittingSize = whileCommittingList.size
    dst.prevAfterCommitSize = afterCommitList.size
    dst.prevAfterRollbackSize = afterRollbackList.size
  }

  /** Returns the discarded `afterRollbackList` entries. */
  protected def rollbackCallbacks(src: AbstractInTxn.UndoLog): Seq[Status => Unit] = {
    beforeCommitList.size = src.prevBeforeCommitSize
    whileValidatingList.reduceToSize(src.prevWhileValidatingSize)
    whilePreparingList.size = src.prevWhilePreparingSize
    whileCommittingList.size = src.prevWhileCommittingSize
    afterCommitList.size = src.prevAfterCommitSize
    afterRollbackList.truncate(src.prevAfterRollbackSize)
  }

  /** Returns the discarded `afterCommitList` entries. */
  protected def resetCallbacks(): Seq[Status => Unit] = {
    beforeCommitList.size = 0
    whileValidatingList.clear() // TODO: discard underlying array if it is too large, like CallbackList does
    whilePreparingList.size = 0
    whileCommittingList.size = 0
    afterRollbackList.size = 0
    afterCommitList.truncate(0)
  }

  //////////// implementation of functionality for the InTxn user

  def beforeCommit(handler: InTxn => Unit) { requireActive() ; beforeCommitList += handler }
  def whileValidating(handler: () => Boolean) { requireActive() ; whileValidatingList += handler }
  def whilePreparing(handler: InTxnEnd => Unit) { requireNotDecided() ; whilePreparingList += handler }
  def whileCommitting(handler: InTxnEnd => Unit) { requireNotCompleted() ; whileCommittingList += handler }
  def afterCommit(handler: Status => Unit) { requireNotCompleted() ; afterCommitList += handler }
  def afterRollback(handler: Status => Unit) { requireNotCompleted() ; afterRollbackList += handler }

  def afterCompletion(handler: Status => Unit) {
    requireNotCompleted()
    afterCommitList += handler
    afterRollbackList += handler
  }

  def setExternalDecider(decider: ExternalDecider) {
    if (status.decided)
      throw new IllegalArgumentException("can't set ExternalDecider after decision, status = " + status)

    if (_decider != null) {
      if (_decider != decider)
        throw new IllegalArgumentException("can't set two different ExternalDecider-s in the same top-level atomic block")
    } else {
      _decider = decider
      // if this nesting level rolls back then the decider should be unregistered
      afterRollback { status =>
        assert(_decider eq decider)
        _decider = null
      }
    }
  }
}
