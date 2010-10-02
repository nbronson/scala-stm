/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ri

import concurrent.stm.Txn._

class StubTxn extends Txn {
  def parent: Option[Txn] = throw new AbstractMethodError
  def root: Txn = throw new AbstractMethodError

  def status: Status = throw new AbstractMethodError
  def requestRollback(cause: RollbackCause): Status = throw new AbstractMethodError

  protected def rollback(cause: RollbackCause): Nothing = throw new AbstractMethodError

  protected def beforeCommit(handler: Txn => Unit) = throw new AbstractMethodError
  protected def afterCommit(handler: Status => Unit) = throw new AbstractMethodError
  protected def afterRollback(handler: Status => Unit) = throw new AbstractMethodError
  protected def afterCompletion(handler: Status => Unit) = throw new AbstractMethodError

  protected def addExternalResource(res: ExternalResource, order: Int) = throw new AbstractMethodError
  protected def setExternalDecider(decider: ExternalDecider) = throw new AbstractMethodError
}
