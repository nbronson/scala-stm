/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm.skel

import util.control.ControlThrowable


/** A reusable exception instance, thrown by CCSTM when a transaction is doomed
 *  and should not continue executing.  User code should either rethrow this
 *  exception or not catch it.
 *
 *  @author Nathan Bronson
 */
private[stm] object RollbackError extends Error with ControlThrowable {
  override def fillInStackTrace(): Throwable = this
}
