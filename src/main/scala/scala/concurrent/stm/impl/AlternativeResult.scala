/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package impl

import util.control.ControlThrowable

/** See `DelayedAtomicBlock` */
private[stm] case class AlternativeResult(value: Any) extends ControlThrowable
