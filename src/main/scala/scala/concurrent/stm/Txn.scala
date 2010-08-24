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

  /** The `Status` for a `Txn` for which `Ref` reads and writes may still be
   *  performed (possibly after a partial rollback).
   */
  case object Active extends Status

  /** The `Status` for a `Txn` that is in the process of attempting a top-level
   *  commit, but that has not yet decided whether to commit or roll back.  No
   *  `Ref` reads or writes are allowed, and no additional before-commit
   *  handlers or external resources may be registered.
   */
  case object Preparing extends Status

  /** The `Status` for a `Txn` that has successfully acquired all write
   *  permissions necessary, and that has delegated the final commit decision
   *  to an external decider.
   */
  case object Prepared extends Status

  /** The `Status` for a `Txn` that is being or has been completely cancelled.
   *  None of the `Ref` writes made during any of the nested contexts of the
   *  `Txn` will ever be visible to other transactions.  The atomic block will
   *  be automatically retried using a fresh `Txn` if `cause` is a
   *  `TransientRollbackCause`, unless some sort of retry threshold has been
   *  reached.
   */
  case class RolledBack(cause: RollbackCause) extends CompletedStatus

  /** The `Status` for a `Txn` that was successful.  All `Ref` reads and writes
   *  made through the `Txn` will appear to have occurred at a single point in
   *  the past.  External resource cleanup and after-commit callbacks may still
   *  be running for the `Txn`.
   */
  case object Committed extends CompletedStatus


  /** A record of the reason that a `Txn` was rolled back. */
  sealed abstract class RollbackCause

  /** `RollbackCause`s for which the failure is transient and another attempt
   *  should be made to execute the underlying atomic block.
   */
  sealed abstract class TransientRollbackCause extends RollbackCause

  /** The `RollbackCause` for a `Txn` whose optimistic execution was invalid,
   *  and that should be retried.  The specific situations in which an
   *  optimistic failure can occur are algorithm-specific, but may include:
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
   *  (*) - STMs may perform validation, conflict detection and deadlock cycle
   *  breaking using algorithms that are conservative approximations.  This
   *  means that any particular attempt to execute an atomic block (one `Txn`)
   *  might fail spuriously, so long as overall progress is made.
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
   *  from those used to represent error conditions.
   */
  case class UncaughtExceptionCause(x: Throwable) extends RollbackCause


  //////////// external transactional resources

  /** `ExternalResource`s participate in a two-phase commit.  Each resource is
   *  given the opportunity to veto commit.  After a decision is made each
   *  resource is informed of the decision.
   */
  trait ExternalResource {
    /** Called during the `Preparing` state, returns true if this resource
     *  agrees to commit.  If it has already been determined that a transaction
     *  will roll back, then this method won't be called.  All locks or other
     *  resources required to complete the commit must be acquired during this
     *  callback, or else this method must return false.  The consensus
     *  decision will be delivered via a subsequent call to `performCommit` or
     *  `performRollback`.  `performRollback` will be called on every external
     *  resource registered in a `Txn`, whether or not its `prepare` method was
     *  called.
     *
     *  The resource may call `txn.forceRollback` instead of returning false,
     *  if that is more convenient.
     *
     *  If this method throws an exception, the transaction will be rolled back
     *  with a `CallbackExceptionCause`, which will not result in automatic
     *  retry, and which will cause the exception to be rethrown after rollback
     *  is complete.
     */
    def prepare(txn: Txn): Boolean

    /** Called during the `Committed` state. */
    def performCommit(txn: Txn)

    /** Called during the `RolledBack` state. */
    def performRollback(txn: Txn)
  }

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
}

/** A `Txn` represents one attempt to execute a top-level atomic block. */
trait Txn extends MaybeTxn {
  import Txn._

  //////////// status

  /** Returns a snapshot of the transaction's current status.  The status may
   *  change due to the actions of a concurrent thread.
   */
  def status: Status

  /** Causes the current transaction to roll back.  It will not be retried
   *  until a write has been performed to some memory location read by this
   *  transaction.  If an alternative to this atomic block was provided via
   *  `orAtomic` or `atomic.oneOf`, then the alternative will be tried.
   */
  def retry(): Nothing = forceRollback(ExplicitRetryCause)

  /** Causes this transaction to fail with the specified cause, when called
   *  from the thread running the transaction.  If the transaction is already
   *  rolled back then this method does nothing.  Throws an
   *  `IllegalStateException` if the transaction is already committed.  This
   *  method may only be called by the thread executing the transaction; use
   *  `requestRollback` if you wish to doom a transaction running on another
   *  thread.
   *  @throws IllegalStateException if `status` is `Committed` or if called
   *      from a thread that is not attached to the transaction
   */
  def forceRollback(cause: RollbackCause): Nothing

  /** If the transaction is either `Active` or `Preparing`, marks it for
   *  rollback, otherwise it does not affect the transaction.  Returns the
   *  transaction status after the attempt.  The returned status will be one
   *  of `Prepared`, `Committed`, or `RolledBack`.  Regardless of the status,
   *  this method does not throw an exception.
   */
  def requestRollback(cause: RollbackCause): Status


  //////////// life-cycle callbacks

  /** Arranges for `handler` to be executed as late as possible while the `Txn`
   *  is still `Active`, if the current nested context participates in the
   *  top-level commit.  Subtleties:
   *  - it is possible that after `handler` is run the transaction might still
   *    be rolled back;
   *  - if a handler is registered in a nested context that is rolled back, it
   *    might not be executed even though the `Txn` is eventually committed;
   *  - it is okay to call `beforeCommit` from inside `handler`; and
   *  - before-commit callbacks will be executed in the same order that they
   *    were registered.
   *  @throws IllegalStateException if this transaction is not active.
   */
  def beforeCommit(handler: Txn => Unit)

  /** Arranges for `handler` to be executed as soon as possible after the `Txn`
   *  is committed, if the current nested context participates in the top-level
   *  commit.  Subtleties:
   *  - the handler can't access `Ref`s using the committed `Txn`, but it can
   *    create a new atomic block;
   *  - the handler runs after all locks have been released by the `Txn`, so
   *    any values read or written in the transaction might already have been
   *    changed by another thread before the handler is executed; and
   *  - after-commit callbacks and after-completion callbacks will be executed
   *    in the same order that they were registered.
   *  @throws IllegalStateException if this transaction's status is `Committed`
   *      or `RolledBack`.
   */
  def afterCommit(handler: Txn => Unit)

  /** Arranges for `handler` to be executed as soon as possible after the
   *  current nested context is rolled back or the top-level `Txn` is rolled
   *  back.  Subtleties:
   *  - in the case of a partial rollback, the `Txn` will still be `Active`;
   *  - in the case of a partial rollback, the handler will be run after the
   *    invalid nesting levels are popped;
   *  - in the case of a top-level rollback, the handlers will be run before an
   *    attempt is made to retry the atomic block in a new `Txn`; and
   *  - during each partial or complete rollback, applicable after-rollback and
   *    after-completion handlers will be invoked in the reverse order of their
   *    registration.
   */
  def afterRollback(handler: Txn => Unit)

  /** Arranges for `handler` to be called as both an after-commit and
   *  after-rollback handler, but may be more efficient.
   *
   *  Equivalent to: {{{
   *     txn.afterCommit(handler)
   *     txn.afterRollback(handler)
   *  }}}
   */
  def afterCompletion(handler: Txn => Unit)


  // TODO: nesting lifecycle?

  
  //////////// external resource integration

  /** Adds an external resource to the transaction that will participate in a
   *  two-phase commit protocol.  If two external resources have different
   *  `order`s then the one with the smaller order will be invoked first,
   *  otherwise the one enqueued earlier will be invoked first.
   *  @throws IllegalStateException if this transaction is not active.
   */
  def addExternalResource(res: ExternalResource, order: Int)
  
  /** Adds an external resource with the default order of 0. */
  def addExternalResource(res: ExternalResource) { addExternalResource(res, 0) }

  /** Delegates final decision of the outcome of this transaction to `decider`,
   *  assuming that all reads, writes, and external resources are valid.  This
   *  method can succeed at most once per `Txn`.
   *  @throws IllegalStateException if this transaction's status is not
   *      `Active` or `Preparing`, or if `setExternalDecider` was previously
   *      called for this `Txn`.
   */
  def setExternalDecider(decider: ExternalDecider)
}
