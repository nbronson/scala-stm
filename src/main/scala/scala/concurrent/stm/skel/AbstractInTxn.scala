/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package skel

import concurrent.stm.Txn.{RollbackCause, Status, ExternalDecider}
import collection.mutable.ArrayBuffer

object AbstractInTxn {
  abstract class SuccessCallback[A, B] {
    protected def buffer(owner: A): ArrayBuffer[B => Unit]

    def add(owner: A, handler: B => Unit) { }
  }
}

trait AbstractInTxn extends InTxn {
  import Txn._

  override def currentLevel: AbstractNestingLevel

  //////////// implementation of functionality for the InTxn implementer

  protected def requireActive() {
    currentLevel.status match {
      case Active =>
      case RolledBack(_) => throw RollbackError
      case s => throw new IllegalStateException(s.toString)
    }
  }

  protected def requireNotDecided() {
    currentLevel.status match {
      case s if !s.decided =>
      case RolledBack(_) => throw RollbackError
      case s => throw new IllegalStateException(s.toString)
    }
  }

  protected def requireNotCompleted() {
    currentLevel.status match {
      case s if !s.completed =>
      case RolledBack(_) => throw RollbackError
      case s => throw new IllegalStateException(s.toString)
    }
  }

  private var _decider: ExternalDecider = null
  protected def externalDecider = _decider

  protected val beforeCommitList = new CallbackList[InTxn]
  protected val whileValidatingList = new CallbackList[NestingLevel]
  protected val whilePreparingList = new CallbackList[InTxnEnd]
  protected val whileCommittingList = new CallbackList[InTxnEnd]
  protected val afterCommitList = new CallbackList[Status]
  protected val afterRollbackList = new CallbackList[Status]

  protected def checkpointCallbacks() {
    val level = currentLevel
    level._beforeCommitSize = beforeCommitList.size
    level._whileValidatingSize = whileValidatingList.size
    level._whilePreparingSize = whilePreparingList.size
    level._whileCommittingSize = whileCommittingList.size
    level._afterCommitSize = afterCommitList.size
    level._afterRollbackSize = afterRollbackList.size
  }

  protected def rollbackCallbacks(): Seq[Status => Unit] = {
    val level = currentLevel
    beforeCommitList.size = level._beforeCommitSize
    whileValidatingList.size = level._whileValidatingSize
    whilePreparingList.size = level._whilePreparingSize
    whileCommittingList.size = level._whileCommittingSize
    afterCommitList.size = level._afterCommitSize
    afterRollbackList.truncate(level._afterRollbackSize)
  }

  protected def fireWhileValidating() {
    var level = currentLevel
    var i = whileValidatingList.size - 1
    while (i >= 0) {
      while (level._whileValidatingSize > i)
        level = level.par
      if (level.status != Txn.Active) {
        // skip the remaining handlers for this level
        i = level._whileValidatingSize
      } else {
        try {
          whileValidatingList(i)(level)
        } catch {
          case x => level.requestRollback(UncaughtExceptionCause(x))
        }
      }
      i -= 1
    }
  }

  //////////// implementation of functionality for the InTxn user

  override def rootLevel: AbstractNestingLevel = currentLevel.root
  def beforeCommit(handler: InTxn => Unit) { requireActive() ; beforeCommitList += handler }
  def whileValidating(handler: NestingLevel => Unit) { requireActive() ; whileValidatingList += handler }
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
