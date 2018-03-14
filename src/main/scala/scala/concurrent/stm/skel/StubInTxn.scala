/* scala-stm - (c) 2009-2011, Stanford University, PPL */

package scala.concurrent.stm
package skel

class StubInTxn extends InTxn {
  import concurrent.stm.Txn._

  // TODO: Return types should probably not be refined from Unit to Nothing

  def executor: TxnExecutor = throw new AbstractMethodError
  def status: Status = throw new AbstractMethodError
  def rootLevel: NestingLevel = throw new AbstractMethodError
  def currentLevel: NestingLevel = throw new AbstractMethodError
  def rollback(cause: RollbackCause): Nothing = throw new AbstractMethodError
  def retry(): Nothing = throw new AbstractMethodError
  def retryFor(timeoutNanos: Long): Unit = { throw new AbstractMethodError }
  def beforeCommit(handler: InTxn => Unit): Nothing = throw new AbstractMethodError
  def whilePreparing(handler: InTxnEnd => Unit): Nothing = throw new AbstractMethodError
  def whileCommitting(handler: InTxnEnd => Unit): Nothing = throw new AbstractMethodError
  def afterCommit(handler: Status => Unit): Nothing = throw new AbstractMethodError
  def afterRollback(handler: Status => Unit): Nothing = throw new AbstractMethodError
  def afterCompletion(handler: Status => Unit): Nothing = throw new AbstractMethodError
  def setExternalDecider(decider: ExternalDecider): Nothing = throw new AbstractMethodError
}
