/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package skel

trait AbstractNestingLevel extends NestingLevel {
  import Txn._

  def txn: AbstractInTxn
  def par: AbstractNestingLevel
  override def root: AbstractNestingLevel

  def parent: Option[NestingLevel] = Option(par)

  private[skel] var _beforeCommitSize = 0
  private[skel] var _whileValidatingSize = 0
  private[skel] var _whilePreparingSize = 0
  private[skel] var _whileCommittingSize = 0
  private[skel] var _afterCommitSize = 0
  private[skel] var _afterRollbackSize = 0
}
