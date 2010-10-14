/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

object Txn {
  import impl.STMImpl

  //////////// dynamic Txn binding

  /** Returns `Some(t)` if called from inside the static or dynamic scope of
   *  the transaction context `t`, `None` otherwise.  If an implicit `Txn` is
   *  available it is used, otherwise a dynamic lookup is performed.
   */
  def current(implicit mt: MaybeTxn): Option[Txn] = STMImpl.instance.current
  

  //////////// status

  /** The current state of a single attempt to execute an atomic block. */
  sealed abstract class Status

  /** `Status` instances that are terminal states. */
  sealed abstract class CompletedStatus extends Status

  /** The `Status` for a transaction attempt in which `Ref` reads and writes
   *  may currently be performed.
   */
  case object Active extends Status

  /** The `Status` for a transaction nesting level that may become `Active`
   *  again after a child transaction is completed.
   */
  case object AwaitingChild extends Status

  /** The `Status` for a `NestingLevel` that has been committed into its parent
   *  and whose parent's status is still `Active`, `AwaitingChild` or
   *  `MergedWithParent`.
   */
  case object MergedWithParent extends Status

  /** The `Status` for a `NestingLevel` that is part of an attempted top-level
   *  commit for which the outcome is uncertain.  No `Ref` reads or writes are
   *  allowed, and no additional before-commit handlers may be registered.
   */
  case object Preparing extends Status

  /** The `Status` for a `NestingLevel` that is part of an attempted top-level
   *  commit that has successfully acquired all write permissions necessary to
   *  succeed, and that has delegated the final commit decision to an external
   *  decider.
   */
  case object Prepared extends Status

  /** The `Status` for a `NestingLevel` that has decided to commit, but whose
   *  `Ref` writes are not yet visible to other threads.
   */
  case object Committing extends Status

  /** The `Status` for a `NestingLevel` that has successfully been committed.
   *  All `Ref` reads and writes made through this `NestingLevel` and its
   *  committed child nesting levels will have appeared to have occurred at a
   *  single point in time.  After-commit callbacks may still be running.
   */
  case object Committed extends CompletedStatus

  /** The `Status` for a `NestingLevel` that is being or has been cancelled.
   *  None of the `Ref` writes made during this nesting level or in any child
   *  nesting level will ever be visible to other threads.  The atomic block
   *  will be automatically retried if `cause` is a `TransientRollbackCause`,
   *  unless STM-specific retry thresholds are exceeded.
   */
  case class RolledBack(cause: RollbackCause) extends CompletedStatus


  /** A record of the reason that a `NestingLevel` was rolled back. */
  sealed abstract class RollbackCause

  /** `RollbackCause`s for which the failure is transient and another attempt
   *  should be made to execute the underlying atomic block.
   */
  sealed abstract class TransientRollbackCause extends RollbackCause

  /** `RollbackCause`s for which the failure is permanent and no attempt should
   *  be made to retry the underlying atomic block.
   */
  sealed abstract class PermanentRollbackCause extends RollbackCause

  /** The `RollbackCause` for a `NestingLevel` whose optimistic execution was
   *  invalid, and that should be retried.  The specific situations in which an
   *  optimistic failure can occur are specific to the STM algorithm, but may
   *  include:
   *  - the STM detected that the value returned by a previous read in this
   *    nesting level is no longer valid;
   *  - a cyclic dependency has occurred and this nesting level must be rolled
   *    back to avoid deadlock;
   *  - a `Txn` with a higher priority wanted to write to a `Ref` written by
   *    this `Txn`;
   *  - the STM decided to switch execution strategies for this atomic block;
   *    or
   *  - no apparent reason (*).
   *
   *  (*) - Some STMs perform validation, conflict detection and deadlock cycle
   *  breaking using algorithms that are conservative approximations.  This
   *  means that any particular attempt to execute an atomic block might fail
   *  spuriously.
   *
   *  @param category an STM-specific label for the reason behind this
   *                  optimistic failure. The set of possible categories is
   *                  bounded.
   *  @param trigger  the specific object that led to the optimistic failure,
   *                  if it is available, otherwise `None`.
   */
  case class OptimisticFailureCause(category: Symbol, trigger: Option[Any]) extends TransientRollbackCause

  /** The `RollbackCause` for an atomic block execution attempt that ended with
   *  a call to `retry`.  The atomic block will be retried after some memory
   *  location read in the previous attempt has changed.
   */
  case object ExplicitRetryCause extends TransientRollbackCause

  /** The `RollbackCause` for an atomic block that should not be restarted
   *  because it threw an exception.  The exception might have been thrown from
   *  the body of the atomic block or from a handler invoked before the commit
   *  decision.  Exceptions used for control flow are not included (see
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


  //////////// reification of a single execution attempt

  // TODO: documentation
  trait NestingLevel {

    /** Returns a unique identifier of this `NestingLevel` instance. */
    def id: Long

    /** Returns the nearest enclosing nesting level, if any. */
    def parent: Option[NestingLevel]

    /** Returns the outermost enclosing nested transaction context, or this
     *  instance if it is the outermost nesting level.  Equal to `txn.root`.
     *  Always, `a.parent.isEmpty == (a.root == a)`.
     */
    def root: NestingLevel

    /** Returns a snapshot of this nesting level's current status.  The status
     *  may change to `Txn.RolledBack` due to the actions of a concurrent
     *  thread.  This method may be called from any thread.
     */
    def status: Status

    /** Requests that a transaction attempt be marked for rollback, possibly
     *  also rolling back some or all of the enclosing nesting levels.  Returns
     *  the resulting status, which will be one of `Prepared`, `Committed` or
     *  `RolledBack`.  Regardless of the status, this method does not throw an
     *  exception.
     *
     *  Unlike `Txn.rollback(cause)`, this method may be called from any thread.
     *  Note that there is no facility for remotely triggering a rollback during
     *  the `Prepared` state, as the `ExplicitDecider` is given the final choice.
     */
    def requestRollback(cause: RollbackCause): Status
  }


  /** Returns `Some(nl)` if `nl` is the current nesting level of the current
   *  `Txn`, `None` otherwise.
   */
  def currentLevel(implicit mt: MaybeTxn): Option[NestingLevel] = current map { _.currentLevel }

  /** Returns the root `NestingLevel` of the current transaction. */
  def rootLevel(implicit txn: Txn): NestingLevel = txn.rootLevel


  //////////// explicit retry and rollback

  // These are methods of the Txn object because it is generally only correct
  // to call them inside the static context of an atomic block.  If they were
  // methods on the Txn instance, then users might expect to be able to call
  // them from any thread.  Methods to add lifecycle callbacks are also object
  // methods for the same reason.

  /** Causes the current nesting level to roll back.  It will not be retried
   *  until a write has been performed to some memory location read by this
   *  transaction.  If an alternative to this atomic block was provided via
   *  `orAtomic` or `atomic.oneOf`, then the alternative will be tried.
   *  @throws IllegalStateException if the transaction is not active.
   */
  def retry(implicit txn: Txn): Nothing = rollback(ExplicitRetryCause)

  /** Causes the current nesting level to be rolled back due to the specified
   *  `cause`.  This method may only be called by the thread executing the
   *  transaction; use `t.rootLevel.requestRollback(cause)` if you wish to doom
   *  a transaction `t` running on another thread.
   *  @throws IllegalStateException if the current transaction has already
   *      decided to commit.
   */
  def rollback(cause: RollbackCause)(implicit tl: TxnLifecycle): Nothing = tl.rollback(cause)


  //////////// life-cycle callbacks

  /** Arranges for `handler` to be executed as late as possible while the root
   *  nesting level of the current transaction is still `Active`, unless the
   *  current nesting level is rolled back.  Reads, writes and additional
   *  nested transactions may be performed inside the handler.  Details:
   *  - it is possible that after `handler` is run the transaction might still
   *    be rolled back;
   *  - it is okay to call `beforeCommit` from inside `handler`, the
   *    reentrantly added handler will be included in this before-commit phase;
   *    and
   *  - before-commit handlers will be executed in their registration order.
   */
  def beforeCommit(handler: Txn => Unit)(implicit txn: Txn) { txn.beforeCommit(handler) }

  /** (rare) Arranges for `handler` to be called after the `Ref` reads and
   *  writes have been checked for serializability, but before the decision has
   *  been made to commit or roll back.  While-preparing handlers can lead to
   *  scalability problems, because while this transaction is in the
   *  `Preparing` state it might obstruct other transactions.  Details:
   *  - the handler must not access any `Ref`s, even using `Ref.single`;
   *  - handlers will be executed in their registration order; and
   *  - handlers may be registered so long as the current transaction status is
   *    not `Preparing`, `Prepared, `RolledBack` or `Committed`.
   */
  def whilePreparing(handler: TxnLifecycle => Unit)(implicit tl: TxnLifecycle) { tl.whilePreparing(handler) }

  /** (rare) Arranges for `handler` to be called after (if) it has been decided
   *  that the current transaction will commit, but before the writes made by
   *  the transaction have become available to other threads.  While-committing
   *  handlers can lead to scalability problems, because while this transaction
   *  is in the `Committing` state it might obstruct other transactions.
   *  Details:
   *  - the handler must not access any `Ref`s, even using `Ref.single`;
   *  - handlers will be executed in their registration order; and
   *  - handlers may be registered so long as the current transaction status is
   *    not `RolledBack` or `Committed`.
   */
  def whileCommitting(handler: => Unit)(implicit tl: TxnLifecycle) { tl.whileCommitting(handler) }

  /** Arranges for `handler` to be executed as soon as possible after the
   *  current transaction is committed.  Details:
   *  - no transaction will be active while the handler is run, but it may
   *    access `Ref`s using a new top-level atomic block or `.single`;
   *  - the handler runs after all internal locks have been released, so any
   *    values read or written in the transaction might already have been
   *    changed by another thread before the handler is executed;
   *  - handlers will be executed in their registration order; and
   *  - handlers may be registered so long as the current transaction status is
   *    not `RolledBack` or `Committed`.
   */
  def afterCommit(handler: Status => Unit)(implicit tl: TxnLifecycle) { tl.afterCommit(handler) }

  /** Arranges for `handler` to be executed as soon as possible after the
   *  current `NestingLevel` is rolled back.  Details:
   *  - the handler will be executed immediately during a partial rollback that
   *    includes the current `NestingLevel`;
   *  - the handler will be run before any additional attempts to execute the
   *    atomic block;
   *  - handlers will be run in the reverse of their registration order; and
   *  - handlers may be registered while the current transaction is `Active`,
   *    `Preparing` or `Prepared`.
   */
  def afterRollback(handler: Status => Unit)(implicit tl: TxnLifecycle) { tl.afterRollback(handler) }

  /** Arranges for `handler` to be called as both an after-commit and
   *  after-rollback handler.
   *
   *  Equivalent to: {{{
   *     afterCommit(handler)
   *     afterRollback(handler)
   *  }}}
   */
  def afterCompletion(handler: Status => Unit)(implicit tl: TxnLifecycle) { tl.afterCompletion(handler) }


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
    /** Should return true if the transaction `txn` should commit.  On entry `txn`'s status
     *  will be `Prepared`.
     */
    def shouldCommit(txn: Txn): Boolean
  }

  /** (rare) Delegates final decision of the outcome of the transaction to
   *  `decider` if the current nesting level participates in the top-level
   *  commit.  This method can succeed with at most one value per top-level
   *  transaction.
   *  @throws IllegalStateException if the current transaction's status is not
   *      `Active` or `Preparing`, or if `setExternalDecider(d)` was previously
   *      called in this transaction, `d != decider`, and the nesting level
   *      from which `setExternalDecider(d)` was called has not rolled back.
   */
  def setExternalDecider(decider: ExternalDecider)(implicit tl: TxnLifecycle) { tl.setExternalDecider(decider) }
}

/** The presence of an implicit `TxnLifecycle` instance grants the caller
 *  permission to register transaction lifecycle callbacks via methods on
 *  `object Txn`.
 */
trait TxnLifecycle extends MaybeTxn {
  import Txn._

  // The user-visible versions of these methods are in the Txn object.

  protected[stm] def rootLevel: NestingLevel
  protected[stm] def currentLevel: NestingLevel
  protected[stm] def rollback(cause: RollbackCause): Nothing
  protected[stm] def beforeCommit(handler: Txn => Unit)
  protected[stm] def whilePreparing(handler: TxnLifecycle => Unit)
  protected[stm] def whileCommitting(handler: => Unit)
  protected[stm] def afterCommit(handler: Status => Unit)
  protected[stm] def afterRollback(handler: Status => Unit)
  protected[stm] def afterCompletion(handler: Status => Unit)
  protected[stm] def setExternalDecider(decider: ExternalDecider)
}

/** The presence of an implicit `Txn` instance grants the caller permission to
 *  perform transactional reads and writes on `Ref` instances, and to register
 *  transaction lifecycle callbacks via methods on the companion `object Txn`.
 *  `Txn` instances themselves may be reused, see `Txn.NestingLevel` if you
 *  need an instance that tracks individual execution attempts.
 */
trait Txn extends TxnLifecycle
