/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA
package impl

trait TxnExecutor {

  /** Executes `block` one or more times until an atomic execution is achieved.
   *
   *  If the implicit `MaybeTxn` is `TxnUnknown` then a dynamic search will be
   *  performed for a parent transaction.  If `mt` is a `Txn` then it will be
   *  used as the parent.
   *
   *  @param    block code to execute atomically
   *  @tparam   Z the return type of the atomic block
   *  @return   the value returned from `block` after a successful optimistic
   *            concurrency attempt
   *  @usecase  def atomic[Z](block: Txn => Z): Z
   */
  def apply[Z](block: Txn => Z)(implicit mt: MaybeTxn): Z

  /** Returns the parameters of this `TxnExecutor` that are specific to the
   *  currently configured STM implementation.  The parameters of a particular
   *  `TxnExecutor` instance don't change, but a new instance with changed
   *  parameters can be obtained using either `withConfig` or `withHint`.
   */
  def configuration: Map[String,Any]

  /** Returns a `TxnExecutor` in which the parameter identified by the key has
   *  been set to the value, or throws an `IllegalArgumentException` if the
   *  dynamically configured STM implementation does not have a parameter by
   *  that name or if the value is not of the correct type.  This method does
   *  not affect the current executor.
   *
   *  The returned value may be saved for reuse, or this method may be used
   *  inline to affect only the execution of a single atomic block.  If the
   *  underlying STM has a parameter that limits the number of transaction
   *  retries, for example: {{{
   *    atomic.withConfig("maxRetries" -> 1) {
   *      // only a single attempt will be made
   *    }
   *  }}}
   *  This code will throw an exception if the underlying STM does not support
   *  a "maxRetries" parameter or if "maxRetries" should be specified as a type
   *  other than `Int`.  For configuration parameters that may be safely
   *  discarded, see `withHint`.
   *
   *  Both `withConfig` and `withHint` use the same parameter namespace, the
   *  only difference is their operation when an unsupported parameter is
   *  given.
   */
  def withConfig(p: (String,Any)): TxnExecutor

  def withConfig(p1: (String,Any), p2: (String,Any), ps: (String,Any)*): TxnExecutor = {
    (this.withConfig(p1).withConfig(p2) /: ps) { (e,p) => e.withConfig(p) }
  }

  /** Returns a `TxnExecutor` in which the parameter identified by the key has
   *  been set to the value if it is supported by the dynamically configured STM
   *  implementation, otherwise returns this executor.  This method does not
   *  affect the current executor.
   *
   *  The returned value may be saved for reuse, or this method may be used
   *  inline to affect only the execution of a single atomic block.  If the
   *  underlying STM selects its `Txn` differently for read-only transactions,
   *  for example, a caller might pass the type of transaction to the STM
   *  implementation: {{{
   *    atomic.withHint("readOnly" -> true) {
   *      // just reads
   *    }
   *  }}}
   *  This code will work find even if the underlying STM does not support a
   *  "readOnly" configuration parameter.  For configuration parameters that
   *  are not safe to discard, see `withConfig`.
   *
   *  Both `withConfig` and `withHint` use the same parameter namespace, the
   *  only difference is their operation when an unsupported parameter is
   *  given.
   */
  def withHint(p: (String,Any)): TxnExecutor = try {
    withConfig(p)
  } catch {
    case x: IllegalArgumentException => this
  }

  def withHint(p1: (String,Any), p2: (String,Any), ps: (String,Any)*): TxnExecutor = {
    (this.withHint(p1).withHint(p2) /: ps) { (e,p) => e.withHint(p) }
  }
}
