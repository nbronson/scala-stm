/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ri

import concurrent.stm.Txn._

class StubTxn extends Txn {
  def status: Status = throw new AbstractMethodError
  def nestingLevel: Int = throw new AbstractMethodError
  def forceRollback(invalidNestingLevel: Int, cause: RollbackCause): Nothing = throw new AbstractMethodError
  def requestRollback(cause: RollbackCause): Status = throw new AbstractMethodError

  def beforeCommit(handler: => Unit) = throw new AbstractMethodError
  def afterCommit(handler: => Unit) = throw new AbstractMethodError
  def afterRollback(handler: => Unit) = throw new AbstractMethodError
  def afterCompletion(handler: => Unit) = throw new AbstractMethodError

  def addExternalResource(res: ExternalResource, order: Int) = throw new AbstractMethodError
  def setExternalDecider(decider: ExternalDecider) = throw new AbstractMethodError
}
