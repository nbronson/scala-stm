/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ri

import concurrent.stm.Txn._

class StubTxn extends Txn {
  def rootLevel: NestingLevel = throw new AbstractMethodError
  def currentLevel: NestingLevel = throw new AbstractMethodError
  def rollback(cause: RollbackCause): Nothing = throw new AbstractMethodError
  def beforeCommit(handler: Txn => Unit) = throw new AbstractMethodError
  def whilePreparing(handler: TxnLifecycle => Unit) = throw new AbstractMethodError
  def whileCommitting(handler: => Unit) = throw new AbstractMethodError
  def afterCommit(handler: Status => Unit) = throw new AbstractMethodError
  def afterRollback(handler: Status => Unit) = throw new AbstractMethodError
  def afterCompletion(handler: Status => Unit) = throw new AbstractMethodError
  def setExternalDecider(decider: ExternalDecider) = throw new AbstractMethodError
}
