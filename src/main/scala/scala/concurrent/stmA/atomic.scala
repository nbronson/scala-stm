/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA

import impl.{AlternativeResult, STMImpl, TxnExecutor}

object atomic extends TxnExecutor {

  // TODO: customize scaladoc for atomic vs TxnExecutor

  def apply[Z](block: Txn => Z)(implicit mt: MaybeTxn): Z = STMImpl.instance.apply(block)

  def pushAlternative[Z](mt: MaybeTxn, block: (Txn) => Z): Boolean = STMImpl.instance.pushAlternative(mt, block)

  override def oneOf[Z](blocks: (Txn => Z)*)(implicit mt: MaybeTxn): Z = {
    // we forward instead of relying on the base version because a STMImpl
    // might have a more efficient implementation
    STMImpl.instance.oneOf(blocks: _*)
  }

  def configuration: Map[Symbol,Any] = STMImpl.instance.configuration

  def withConfig(param: (Symbol,Any)): TxnExecutor = STMImpl.instance.withConfig(param)

  /** Instanced of `atomic.Delayed` delay the execution of an atomic block
   *  until all of the alternatives can be gathered.  There is an implicit
   *  conversion in the `stmA` package object from any type `A` to a
   *  `Delayed[A]`, which will kick in if there is an attempt to call
   *  `.orAtomic` on a value.
   */
  class Delayed[A](above: => A) {
    /** See `atomic.oneOf`. */
    def orAtomic[B >: A](below: Txn => B)(implicit mt: MaybeTxn): B = {
      // Execution of Delayed.orAtomic proceeds bottom to top, with the upper
      // portions being captured by-name in `above`.  The actual transactional
      // execution is all performed inside the top-most block (the one that
      // used atomic rather than orAtomic).  If a block other than the top one
      // is the one that eventually succeeds, we must tunnel the value out with
      // an exception because the alternatives may have a wider type.  We only
      // catch the exception if we are the bottom-most alternative, because
      // only it is guaranteed to have been fully widened.
      if (!STMImpl.instance.pushAlternative(mt, below)) {
        // we're not the bottom
        above
      } else {
        // we're the bottom, this is the end of the result tunnel
        try {
          above
        } catch {
          case AlternativeResult(x) => x.asInstanceOf[B]
        }
      }
    }
  }
}
