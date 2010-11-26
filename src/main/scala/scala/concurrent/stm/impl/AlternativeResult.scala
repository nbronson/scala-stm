/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm
package impl

import util.control.ControlThrowable

/** See `DelayedAtomicBlock` */
private[stm] case class AlternativeResult(value: Any) extends ControlThrowable
