/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm

object BypassCtx {
  implicit val alwaysAvailable: BypassCtx = null
}

/** The implicit `BypassCtx` instance is used so that the methods of `Ref.View`
 *  have a different VM signature from those of `Ref.BypassView`, despite
 *  appearing the same to the caller.  This allows STM implementations to use
 *  implement `Ref`, `Ref.View` and `Ref.BypassView` in a single concrete
 *  class, resulting in an important reduction in transient object instances.
 */
trait BypassCtx
