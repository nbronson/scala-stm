/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ri

import concurrent.stm.Txn._

class StubTxn extends Txn {
  def status: Status = throw new AbstractMethodError
  def forceRollback(cause: RollbackCause): Nothing = throw new AbstractMethodError
  def requestRollback(cause: RollbackCause): Status = throw new AbstractMethodError

  def beforeCommit(handler: Txn => Unit) = throw new AbstractMethodError
  def afterCommit(handler: Txn => Unit) = throw new AbstractMethodError
  def afterRollback(handler: Txn => Unit) = throw new AbstractMethodError
  def afterCompletion(handler: Txn => Unit) = throw new AbstractMethodError

  def addExternalResource(res: ExternalResource, order: Int) = throw new AbstractMethodError
  def setExternalDecider(decider: ExternalDecider) = throw new AbstractMethodError
}
