/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA
package impl

import util.control.ControlThrowable

/** See `atomic.Delayed` */
private[stmA] case class AlternativeResult(value: Any) extends ControlThrowable
