/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

object Txn {
  import impl.STMImpl

  //////////// dynamic Txn binding

  /** Returns `Some(t)` if called from inside the static or dynamic scope of
   *  the transaction `t`, `None` otherwise.  If an implicit `Txn` is
   *  available it is used, otherwise a dynamic lookup is performed.
   */
  def current(implicit mt: MaybeTxn): Option[Txn] = Option(currentOrNull)

  /** Equivalent to `current getOrElse null`. */
  def currentOrNull(implicit mt: MaybeTxn): Txn = STMImpl.instance.currentOrNull


  //////////// status

  /** The current state of a single attempt to execute an atomic block. */
  sealed abstract class Status

  /** `Status` instances that are terminal states. */
  sealed abstract class CompletedStatus extends Status

  /** The `Status` for a `Txn` in which `Ref` reads and writes may currently be
   *  performed.
   */
  case object Active extends Status

  /** The `Status` for a `Txn` that may become `Active` again after a child
   *  transaction is completed.
   */
  case object AwaitingChild extends Status

  /** The `Status` for a nested `Txn` that has been committed into its parent
   *  but whose parent's status is still `Active`, `AwaitingChild` or
   *  `MergedWithParent`.  If the parent transaction is rolled back, the status
   *  for all of its nested transactions will also be an instance of
   *  `RolledBack`.
   */
  case object MergedWithParent extends Status

  /** The `Status` for a `Txn` that is part of a tree of transactions
   *  attempting a top-level commit, but that might still commit or roll back.
   *  No `Ref` reads or writes are allowed, and no additional before-commit
   *  handlers or external resources may be registered.
   */
  case object Preparing extends Status

  /** The `Status` for a `Txn` that is part of a tree of transactions that has
   *  successfully acquired all write permissions necessary to perform a
   *  top-level commit, and that has delegated the final commit decision to
   *  an external decider.
   */
  case object Prepared extends Status

  /** The `Status` for a `Txn` that is being or has been cancelled.  None of
   *  the `Ref` writes made during this transaction or in any child context
   *  of this transaction will be visible to other threads.  The atomic block
   *  will be automatically retried if no outer atomic block is not active, if
   *  `cause` is a `TransientRollbackCause`, and if no retry thresholds have
   *  been exceeded.
   */
  case class RolledBack(cause: RollbackCause) extends CompletedStatus

  /** The `Status` for a `Txn` that is part of a tree of transactions that was
   *  successful.  All `Ref` reads and writes made through the `Txn` and
   *  its `Committed` child transactions will appear to have occurred at a
   *  single point in time.  External resource cleanup and after-commit
   *  callbacks may still be running.
   */
  case object Committed extends CompletedStatus


  /** A record of the reason that a `Txn` was rolled back. */
  sealed abstract class RollbackCause

  /** `RollbackCause`s for which the failure is transient and another attempt
   *  should be made to execute the underlying atomic block.
   */
  sealed abstract class TransientRollbackCause extends RollbackCause

  /** `RollbackCause`s for which the failure is permanent and no attempt should
   *  be made to retry the underlying atomic block.
   */
  sealed abstract class PermanentRollbackCause extends RollbackCause

  /** The `RollbackCause` for a `Txn` whose optimistic execution was invalid,
   *  and that should be retried.  The specific situations in which an
   *  optimistic failure can occur are specific to the STM algorithm, but may
   *  include:
   *  - the STM detected that the value returned by a previous read in this
   *    `Txn` is no longer valid;
   *  - a cyclic dependency has occurred and this `Txn` must be rolled back to
   *    avoid deadlock;
   *  - a `Txn` with a higher priority wanted to write to a `Ref` written by
   *    this `Txn`;
   *  - the STM decided to switch execution strategies for this atomic block;
   *    or
   *  - no apparent reason (*).
   *
   *  (*) - Some STMs perform validation, conflict detection and deadlock cycle
   *  breaking using algorithms that are conservative approximations.  This
   *  means that any particular attempt to execute an atomic block (one `Txn`)
   *  might fail spuriously.
   *
   *  @param category an STM-specific label for the reason behind this
   *                  optimistic failure. The set of possible categories is
   *                  bounded.
   *  @param trigger  the specific object that led to the optimistic failure,
   *                  if it is available, otherwise `None`.
   */
  case class OptimisticFailureCause(category: Symbol, trigger: Option[Any]) extends TransientRollbackCause

  /** The `RollbackCause` for an atomic block execution attempt that ended with
   *  a call to `retry`.  The atomic block will be retried in a new `Txn` after
   *  some memory location read in the previous `Txn` has changed.
   */
  case object ExplicitRetryCause extends TransientRollbackCause

  /** The `RollbackCause` for an atomic block that should not be restarted
   *  because it had no optimistic failure but threw an exception.  The
   *  exception might have been thrown from the body of the atomic block, from
   *  a before-commit handler, or from the prepare phase of an external
   *  resource.  Exceptions used for control flow are not included (see
   *  `TxnExecutor.isControlFlow`).
   *
   *  Scala's STM discards `Ref` writes performed by atomic blocks that throw
   *  an exception.  This is referred to as "failure atomicity".  In a system
   *  that uses exceptions for error cleanup this design tends to preserve the
   *  invariants of shared data structures, which is a good thing.  If a system
   *  uses exceptions for control flow, however, this design may lead to
   *  unexpected behavior.  The `TxnExecutor` object's `isControlFlow` method
   *  is used to distinguish exceptions representing control flow transfers
   *  from those used to represent error conditions.  See
   *  `TxnExecutor.transformDefault` to change the default rules.
   */
  case class UncaughtExceptionCause(x: Throwable) extends PermanentRollbackCause


  //////////// explicit retry and rollback

  // These are methods of the Txn object because it is generally only correct
  // to call them for the current transaction.  Methods to add lifecycle
  // callbacks are also object methods for the same reason.

  /** Causes the current transaction to roll back.  It will not be retried
   *  until a write has been performed to some memory location read by this
   *  transaction.  If an alternative to this atomic block was provided via
   *  `orAtomic` or `atomic.oneOf`, then the alternative will be tried.
   *  @throws IllegalStateException if the transaction is not active.
   */
  def retry(implicit txn: Txn): Nothing = rollback(ExplicitRetryCause)

  /** Causes the current transaction to be rolled back due to the specified
   *  `cause`.  This method may only be called by the thread executing the
   *  transaction; use `t.requestRollback(cause)` if you wish to doom a
   *  transaction `t` running on another thread.
   *  @throws IllegalStateException if `status` is `Committed` or if called
   *      from a thread that is not attached to the transaction.
   */
  def rollback(cause: RollbackCause)(implicit txn: Txn): Nothing = txn.rollback(cause)


  //////////// life-cycle callbacks

  /** Arranges for `handler` to be executed as late as possible while the
   *  current transaction is still `Active`, if the current transaction
   *  eventually participates in the top-level commit.  The `Txn` passed to the
   *  handler will be the active root transaction, which may be used for
   *  performing reads, writes and additional nested transactions from inside
   *  the handler.  Details:
   *  - it is possible that after `handler` is run the transaction tree might
   *    still be rolled back;
   *  - it is okay to call `beforeCommit` from inside `handler`, the
   *    reentrantly added handler will be included in this before-commit phase;
   *    and
   *  - before-commit handlers will be executed in their registration order.
   */
  def beforeCommit(handler: Txn => Unit)(implicit txn: Txn) { txn.beforeCommit(handler) }

  /** Arranges for `handler` to be executed as soon as possible after the
   *  current transaction is committed.  Details:
   *  - no transaction will be active while the handler is run, but it may
   *    access `Ref`s using a new top-level atomic block or `.single`;
   *  - the handler runs after all internal locks have been released, so any
   *    values read or written in the transaction might already have been
   *    changed by another thread before the handler is executed;
   *  - handlers will be executed in their registration order; and
   *  - handlers may be registered during the `Preparing` and `Prepared`
   *    states.
   */
  def afterCommit(handler: Status => Unit)(implicit txn: Txn) { txn.afterCommit(handler) }

  /** Arranges for `handler` to be executed as soon as possible after the
   *  current `Txn` is rolled back.  Details:
   *  - the handler will be executed immediately during a partial rollback that
   *    includes the current `Txn`, even if the parent is not rolled back;
   *  - the handler will be run before an attempt is made (if any) to retry the
   *    atomic block in a new `Txn`;
   *  - handlers will be invoked in the reverse of their registration order;
   *    and
   *  - handlers may be registered while `status` is `Active`, `Preparing` or
   *    `Prepared`.
   */
  def afterRollback(handler: Status => Unit)(implicit txn: Txn) { txn.afterRollback(handler) }

  /** Arranges for `handler` to be called as both an after-commit and
   *  after-rollback handler.
   *
   *  Equivalent to: {{{
   *     afterCommit(handler)
   *     afterRollback(handler)
   *  }}}
   */
  def afterCompletion(handler: Status => Unit)(implicit txn: Txn) { txn.afterCompletion(handler) }


  //////////// external resource integration (two-phase commit)

  /** `ExternalResource`s participate in a two-phase commit.  Each resource is
   *  given the opportunity to veto commit.  After a decision is made each
   *  resource is informed of the decision.
   */
  trait ExternalResource {
    /** Called while `txn`'s status is `Preparing`, returns true if this
     *  resource agrees to commit.  Only guaranteed to be called for
     *  transactions that enter the `Prepared` or `Committed` state.  `txn`
     *  will be the transaction to which the external resource was added, which
     *  might be a nested transaction.
     *
     *  All locks or other resources required to complete the commit must be
     *  acquired during this callback or else this method must return false.
     *  The resource may call `txn.forceRollback` instead of returning false,
     *  if that is more convenient.
     *
     *  If this method throws an exception, the top-level root transaction and
     *  all of its preparing children will be rolled back with a
     *  `CallbackExceptionCause`, no retry will be performed, and the exception
     *  will be rethrown after rollback is complete.
     */
    def prepare(txn: Txn): Boolean

    /** Called during the `Committed` state.  Either `performCommit` or
     *  `performRollback` is guaranteed to be called for each registered
     *  external resource.  Note that `performCommit` won't be called until the
     *  top-level (non-nested) `Txn` commits.
     */
    def performCommit(txn: Txn)

    /** Called during the `RolledBack` state.  Either `performCommit` or
     *  `performRollback` is guaranteed to be called for each registered
     *  external resource.  `performRollback` will be called as soon as
     *  rollback is inevitable for `txn`, regardless of the status of the
     *  top-level root transaction.
     */
    def performRollback(txn: Txn)
  }

  /** Adds an external resource to the current transaction that will
   *  participate in a two-phase commit protocol.  If two external resources
   *  have different `order`s then the one with the smaller order will be
   *  invoked first, otherwise the one registered earlier will be invoked
   *  first.
   *  @throws IllegalStateException if this transaction is not active.
   */
  def addExternalResource(res: ExternalResource, order: Int)(implicit txn: Txn) { txn.addExternalResource(res, order) }

  /** Adds an external resource with the default order of 0. */
  def addExternalResource(res: ExternalResource)(implicit txn: Txn) { addExternalResource(res, 0) }

  /** An `ExternalDecider` is given the final control over the decision of
   *  whether or not to commit a `Txn`, which allows transactions to be
   *  integrated with a single non-transactional resource.  `shouldCommit` will
   *  only be called if a `Txn` has successfully acquired all necessary write
   *  locks, prepared all external resources, and validated all of its reads.
   *  The decider may then attempt a non-transactional operation whose outcome
   *  is uncertain, and based on the outcome may directly cause the `Txn` to
   *  commit or roll back.
   */
  trait ExternalDecider {
    /** Should return true if `txn` should commit.  On entry `txn`'s status
     *  will be `Prepared`.
     */
    def shouldCommit(txn: Txn): Boolean
  }

  /** Delegates final decision of the outcome of the current transaction to
   *  `decider` (if it hasn't been rolled back for some other reason first).
   *  This method can succeed with at most one value per top-level `Txn`.
   *  @throws IllegalStateException if the current transaction's status is not
   *      `Active` or `Preparing`, or if `setExternalDecider` was previously
   *      called with a different value for any `Txn` that has the same `root`
   *      as this `Txn` and that has not rolled back.
   */
  def setExternalDecider(decider: ExternalDecider)(implicit txn: Txn) { txn.setExternalDecider(decider) }
}

/** A `Txn` represents one attempt to execute a top-level or nested atomic
 *  block.
 */
trait Txn extends MaybeTxn {
  import Txn._

  /** Returns the nearest enclosing transaction, if any. */
  def parent: Option[Txn]

  /** Returns the outermost enclosing transaction, or this instance if this is
   *  a top-level transaction.  `txn.parent.isEmpty == (txn.root == txn)`
   */
  def root: Txn

  /** Returns a snapshot of the transaction's current status.  The status may
   *  change to `Txn.RolledBack` due to the actions of a concurrent thread.
   *  This method may be called from any thread.
   */
  def status: Status

  /** Attempts to cause a transaction running on another thread to be marked
   *  for rollback, possibly also rolling back some or all of the enclosing
   *  transactions.  Returns the transaction status after the attempt.  The
   *  returned status will be one of `Prepared`, `Committed` or `RolledBack`.
   *  Regardless of the status, this method does not throw an exception.
   *
   *  Unlike `Txn.rollback(cause)`, this method may be called from any thread.
   *  Note that there is no facility for remotely triggering a rollback during
   *  the `Prepared` state, as the `ExplicitDecider` is given the final choice.
   */
  def requestRollback(cause: RollbackCause): Status


  //////////// methods only appropriate for the current Txn

  // The user-visible versions of these methods are in the Txn object.

  protected def rollback(cause: RollbackCause): Nothing
  protected def beforeCommit(handler: Txn => Unit)
  protected def afterCommit(handler: Status => Unit)
  protected def afterRollback(handler: Status => Unit)
  protected def afterCompletion(handler: Status => Unit)
  protected def addExternalResource(res: ExternalResource, order: Int)
  protected def setExternalDecider(decider: ExternalDecider)
}
