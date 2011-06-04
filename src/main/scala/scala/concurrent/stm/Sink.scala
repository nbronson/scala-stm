/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm

object Sink {

  /** `Sink.View[+A]` consists of the contra-variant write-only operations of
   *  `Ref.View[A]`.
   */
  trait View[-A] {

    /** Returns a `Sink` that accesses the same memory location as this view.
     *  The returned `Sink` might be the original reference that was used to
     *  construct this view, or it might be a `Sink` that is equivalent (and
     *  `==`) to the original.
     *  @return a `Sink` that accesses the same memory location as this view.
     */
    def ref: Sink[A]

    def bypass: BypassView[A]

    /** Performs an atomic write of the value in `ref`.  If an atomic block is
     *  active (see `Txn.current`) then the write will be performed as part of
     *  the transaction, otherwise it will act as if it was performed inside a
     *  new atomic block.  Equivalent to `set(v)`.
     */
    def update(v: A) { set(v) }

    /** Performs an atomic write; equivalent to `update(v)`. */
    def set(v: A)

    /** Performs an atomic write and returns true, or returns false.  The
     *  STM implementation may choose to return false to reduce (not
     *  necessarily avoid) blocking.  If no other threads are performing any
     *  transactional or atomic accesses then this method will succeed.
     */
    def trySet(v: A): Boolean
  }

  /** (rare) `Sink.BypassView[+A]` consists of the contra-variant write-only
   *  operations of `Ref.BypassView[A]`.  Prefer `Ref.View[A]`.
   */
  trait BypassView[-A] {

    def ref: Sink[A]
    def single: Sink.View[A]

    /** Performs an atomic write of the value in `ref`.  If an atomic block is
     *  active (see `Txn.current`) and then this method may throw an exception
     *  if it conflicts with the current thread's transaction.  Equivalent to
     *  `set(v)`.
     *  @throw IllegalStateException if the STM cannot perform the write
     *      without invalidating the current thread's atomic block.
     */
    def update(v: A)(implicit ctx: BypassCtx) { set(v) }

    /** Performs an atomic write; equivalent to `update(v)`. */
    def set(v: A)(implicit ctx: BypassCtx)

    /** Performs an atomic write and returns true, or returns false.  The
     *  STM implementation may choose to return false to reduce (not
     *  necessarily avoid) blocking.  If no threads are performing any
     *  transactional or atomic accesses then this method will succeed.
     */
    def trySet(v: A)(implicit ctx: BypassCtx): Boolean
  }
}

/** `Sink[+A]` consists of the contra-variant write-only operations of
 *  `Ref[A]`.
 *
 *  @author Nathan Bronson
 */
trait Sink[-A] extends SinkLike[A, InTxn] {

  /** See `Ref.single`. */
  def single: Sink.View[A]

  def bypass: Sink.BypassView[A]
}
