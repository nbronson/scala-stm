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
trait Source[+A] extends SourceLike[A, InTxn] {

  /** See `Ref.single`. */
  def single: Source.View[A]
}
