/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package skel

import concurrent.stm.Txn.{RollbackCause, Status, ExternalDecider}

abstract class AbstractInTxn(val executor: TxnExecutor) extends InTxn {

  //////////// interface for concrete implementations

  private var _decider: ExternalDecider = null

  def externalDecider = _decider

  override def currentLevel: AbstractNestingLevel

  //////////// implementation of InTxn functionality

  def rootLevel: NestingLevel = currentLevel.root
  def beforeCommit(handler: InTxn => Unit) { currentLevel.beforeCommit += handler }
  def whilePreparing(handler: InTxnEnd => Unit) { currentLevel.whilePreparing += handler }
  def whileCommitting(handler: InTxnEnd => Unit) { currentLevel.whileCommitting += handler }
  def afterCommit(handler: Status => Unit) { currentLevel.afterCommit += handler }
  def afterRollback(handler: Status => Unit) { currentLevel.afterRollback += handler }

  def afterCompletion(handler: Status => Unit) {
    afterCommit(handler)
    afterRollback(handler)
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
        assert(_decider == null || (_decider eq decider))
        _decider = null
      }
    }
  }
}
