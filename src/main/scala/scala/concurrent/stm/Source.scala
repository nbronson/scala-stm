/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm

object Source {

  /** `Source.View[+A]` consists of the covariant read-only operations of
   *  `Ref.View[A]`.
   */
  trait View[+A] {

    /** Returns a `Source` that accesses the same memory location as this view.
     *  The returned `Source` might be the original reference that was used to
     *  construct this view, or it might be a `Source` that is equivalent (and
     *  `==`) to the original.
     *  @return a `Source` that accesses the same memory location as this view.
     */
    def ref: Source[A]

    /** Performs an atomic read of the value in `ref`.  If an atomic block is
     *  active (see `Txn.findCurrent`) then the read will be performed as part
     *  of the transaction, otherwise it will act as if it was performed inside
     *  a new atomic block.  Equivalent to `get`.
     *  @return the value of the `Ref` as observed by the current context.
     */
    def apply(): A = get

    /** Performs an atomic read; equivalent to `apply()`.
     *  @return the value of the `Ref` as observed by the current context.
     */
    def get: A

    /** Acts like `ref.getWith(f)` if there is an active transaction, otherwise
     *  just returns `f(get)`.
     *  @param f an idempotent function.
     *  @return the result of applying `f` to the value contained in `ref`.
     */
    def getWith[Z](f: A => Z): Z

    /** Acts like `ref.relaxedGet(equiv)` if there is an active transaction,
     *  otherwise just returns `get`.
     *  @param equiv an equivalence function that returns true if a transaction
     *      that observed the first argument will still complete correctly,
     *      where the second argument is the actual value that should have been
     *      observed.
     *  @return a value of the `Ref`, not necessary consistent with the rest of
     *      the reads performed by the active transaction, if any.
     */
    def relaxedGet(equiv: (A, A) => Boolean): A

    /** Blocks until `f(get)` is true, in a manner consistent with the current
     *  context.  Requires that the predicate be safe to reevaluate, and that
     *  `f(x) == f(y)` if `x == y`.
     *
     *  `v.retryUntil(f)` is equivalent to {{{
     *    atomic { implicit t =>
     *      if (!f(v.get)) retry
     *    }
     *  }}}
     *
     *  If you want to wait for a predicate that involves more than one `Ref`
     *  then use `retry` directly.
     *  @param f a predicate that is safe to evaluate multiple times.
     */
    def retryUntil(f: A => Boolean)
  }
}

/** `Source[+A]` consists of the covariant read-only operations of `Ref[A]`. */
trait Source[+A] {

  /** See `Ref.single`. */
  def single: Source.View[A]

  /** Performs a transactional read and checks that it is consistent with all
   *  reads already made by `txn`.  Equivalent to `get`.
   *
   *  Example: {{{
   *    val x = Ref(0)
   *    atomic { implicit t =>
   *      ...
   *      val v = x() // perform a read inside a transaction
   *      ...
   *    }
   *  }}}
   *  @param txn an active transaction.
   *  @return the value of the `Ref` as observed by `txn`.
   *  @throws IllegalStateException if `txn` is not active.
   */
  def apply()(implicit txn: InTxn): A = get
  
  /** Performs a transactional read and checks that it is consistent with all
   *  reads already made by `txn`.  Equivalent to `apply()`, which is more
   *  concise in many situations.
   *  @param txn an active transaction.
   *  @return the value of the `Ref` as observed by `txn`.
   *  @throws IllegalStateException if `txn` is not active.
   */
  def get(implicit txn: InTxn): A

  /** Returns `f(get)`, possibly reevaluating `f` to avoid rollback if a
   *  conflicting change is made but the old and new values are equal after
   *  application of `f`.  Requires that `f(x) == f(y)` if `x == y`.
   *
   *  `getWith(f)` is equivalent to `f(relaxedGet({ f(_) == f(_) }))`, although
   *  perhaps more efficient.
   *  @param f an idempotent function.
   *  @return the result of applying `f` to the value contained in this `Ref`.
   */
  def getWith[Z](f: A => Z)(implicit txn: InTxn): Z

  /** Returns the same value as `get`, but allows the caller to determine
   *  whether `txn` should be rolled back if another thread changes the value
   *  of this `Ref` before `txn` is committed.  If `ref.relaxedGet(equiv)`
   *  returns `v0` in `txn`, another context changes `ref` to `v1`, and
   *  `equiv(v0, v1) == true`, then `txn` won't be required to roll back (at
   *  least not due to this read).  If additional changes are made to `ref`
   *  additional calls to the equivalence function will be made, always with
   *  `v0` as the first parameter.
   *
   *  `equiv` will always be invoked on the current thread.  Extreme care
   *  should be taken if the equivalence function accesses any `Ref`s.
   *
   *  As an example, to perform a read that will not be validated during commit
   *  you can use the maximally permissive equivalence function: {{{
   *    val unvalidatedValue = ref.relaxedGet({ (_, _) => true })
   *  }}}
   *  To check view serializability rather than conflict serializability for a
   *  read: {{{
   *    val viewSerializableValue = ref.relaxedGet({ _ == _ })
   *  }}}
   *  The `getWith` method provides related functionality.
   *  @param equiv an equivalence function that returns true if a transaction
   *      that observed the first argument will still complete correctly,
   *      where the second argument is the actual value that should have been
   *      observed.
   *  @return a value of the `Ref`, not necessary consistent with the rest of
   *      the reads performed by `txn`.
   */
  def relaxedGet(equiv: (A, A) => Boolean)(implicit txn: InTxn): A
}
