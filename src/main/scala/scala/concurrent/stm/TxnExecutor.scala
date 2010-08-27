/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

/** `object TxnExecutor` manages the system-wide default `TxnExecutor`. */
object TxnExecutor {
  @volatile private var _default: TxnExecutor = impl.STMImpl.instance

  /** Returns the default `TxnExecutor`. */
  def default: TxnExecutor = _default

  /** Atomically replaces the default `TxnExecutor` with `f(default)`. */
  def transformDefault(f: TxnExecutor => TxnExecutor) {
    synchronized { _default = f(_default) }
  }
}

/** A `TxnExecutor` is responsible for executing atomic blocks transactionally
 *  using a set of configuration parameters.  Configuration changes are made by
 *  constructing a new `TxnExecutor` using `withConfig` or `withHint`.  The
 *  new executor may be used immediately, saved and used multiple times, or
 *  registered as the new system-wide default using
 *  `TxnExecutor.transformDefault`.
 */
trait TxnExecutor {

  /** Executes `block` one or more times until an atomic execution is achieved,
   *  buffering and/or locking writes so they are not visible until success.
   *
   *  @param    block code to execute atomically
   *  @tparam   Z the return type of the atomic block
   *  @return   the value returned from `block` after a successful optimistic
   *            concurrency attempt
   *  @usecase  def atomic[Z](block: Txn => Z): Z
   */
  final def apply[Z](block: Txn => Z)(implicit mt: MaybeTxn): Z = runAtomically(block)

  // Performs the work of apply, while resulting in a more readable stack trace.
  protected def runAtomically[Z](block: Txn => Z)(implicit mt: MaybeTxn): Z

  /** Atomically executes a transaction that is composed from `blocks` by
   *  joining with a left-biased `orAtomic` operator.  The following two
   *  examples are equivalent.  Using `orAtomic`:
   *  {{{
   *    atomic { implicit t =>
   *      // body A
   *    } orAtomic { implicit t =>
   *      // body B
   *    } ...
   *  }}}
   *  Using `oneOf`:
   *  {{{
   *    atomic.oneOf( { implicit t: Txn =>
   *      // body A
   *    }, { implicit t: Txn =>
   *      // body B
   *    } )
   *  }}}
   *
   *  The first block will be attempted in an optimistic transaction until it
   *  either succeeds, fails with no retry possible (in which case the causing
   *  exception will be rethrown), or performs a call to `retry`.  If a retry
   *  is requested, then the next block will be attempted in the same fashion.
   *  If all blocks are explicitly retried then execution resumes at the first
   *  block, but only after another context has changed some value read by one
   *  of the attempts.
   *
   *  The left-biasing of the `orAtomic` composition guarantees that if the
   *  first block does not call `retry`, no other blocks will be executed.
   */
  def oneOf[Z](blocks: (Txn => Z)*)(implicit mt: MaybeTxn): Z = {
    blocks.tail.reverseMap { pushAlternative(mt, _) }
    try {
      apply(blocks.head)
    } catch {
      case impl.AlternativeResult(x) => x.asInstanceOf[Z]
    }
  }

  /** Pushes an alternative atomic block on the current thread or transaction.
   *  The next call to `apply` will consider `block` to be an alternative.
   *  Returns true if this is the first pushed alternative, false otherwise.
   *  This method is not usually called directly.  Alternative atomic blocks
   *  are only attempted if the previous alternatives call `retry`.
   *
   *  Note that it is not required that `pushAlternative` be called on the same
   *  instance of `TxnExecutor` as `apply`, just that they have been derived
   *  from the same original executor.
   */
  def pushAlternative[Z](mt: MaybeTxn, block: Txn => Z): Boolean

  /** Returns the parameters of this `TxnExecutor` that are specific to the
   *  currently configured STM implementation.  The parameters of a particular
   *  `TxnExecutor` instance don't change, but a new instance with changed
   *  parameters can be obtained using either `withConfig` or `withHint`.
   */
  def configuration: Map[Symbol,Any]

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
   *    atomic.withConfig('maxRetries -> 1) {
   *      // only a single attempt will be made
   *    }
   *  }}}
   *  This code will throw an exception if the underlying STM does not support
   *  a `'maxRetries` parameter or if `'maxRetries` should be specified as a
   *  type other than `Int`.  For configuration parameters that may be safely
   *  discarded, see `withHint`.
   *
   *  Both `withConfig` and `withHint` use the same parameter namespace, the
   *  only difference is their operation when an unsupported parameter is
   *  given.
   */
  def withConfig(p: (Symbol,Any)): TxnExecutor

  def withConfig(p1: (Symbol,Any), p2: (Symbol,Any), ps: (Symbol,Any)*): TxnExecutor = {
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
   *    atomic.withHint('readOnly -> true) {
   *      // just reads
   *    }
   *  }}}
   *  This code will work find even if the underlying STM does not support a
   *  `'readOnly' configuration parameter.  For configuration parameters that
   *  are not safe to discard, see `withConfig`.
   *
   *  Both `withConfig` and `withHint` use the same parameter namespace, the
   *  only difference is their operation when an unsupported parameter is
   *  given.
   */
  def withHint(p: (Symbol,Any)): TxnExecutor = try {
    withConfig(p)
  } catch {
    case x: IllegalArgumentException => this
  }

  def withHint(p1: (Symbol,Any), p2: (Symbol,Any), ps: (Symbol,Any)*): TxnExecutor = {
    (this.withHint(p1).withHint(p2) /: ps) { (e,p) => e.withHint(p) }
  }

  /** Returns true if `x` should be treated as a transfer of control, rather
   *  than an error.  Atomic blocks that end with an uncaught control flow
   *  exception are committed, while atomic blocks that end with an uncaught
   *  error exception are rolled back.
   *
   *  The base implementation of this returns true for instances that implement
   *  `scala.util.control.ControlThrowable`.
   */
  def isControlFlow(x: Throwable): Boolean

  /** Returns a `TxnExecutor e` that is identical to this one, except that
   *  `e.isControlFlow(x)` will return `pf(x)` if `pf.isDefined(x)`.  For
   *  exceptions for which `pf` is not defined the decision will be deferred to
   *  the previous implementation.
   *
   *  This function may be combined with `TxnExecutor.transformDefault` to add
   *  system-wide recognition of a control-transfer exception that does not
   *  extend `scala.util.control.ControlThrowable`: {{{
   *    TxnExecutor.transformDefault { e =>
   *      e.withControlFlowRecognizer {
   *        case _: DSLNonLocalControlTransferException => true
   *      }
   *    }
   *  }}}
   */
  def withControlFlowRecognizer(pf: PartialFunction[Throwable, Boolean])
}
