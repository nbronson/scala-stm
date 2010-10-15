/* scala-stm - (c) 2010, LAMP/EPFL */

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

    /** Performs an atomic write of the value in `ref`.  If an atomic block is
     *  active (see `Txn.current`) then the write will be performed as part of
     *  the transaction, otherwise it will act as if it was performed inside a
     *  new atomic block.  Equivalent to `set(v)`.
     */
    def update(v: A) { set(v) }

    /** Performs an atomic write; equivalent to `update(v)`. */
    def set(v: A)
  }
}

/** `Sink[+A]` consists of the contra-variant write-only operations of
 *  `Ref[A]`.
 */
trait Sink[-A] {

  /** See `Ref.single`. */
  def single: Sink.View[A]
  
  /** Performs a transactional write.  The new value will not be visible by
   *  any other threads until (and unless) `txn` successfully commits.
   *  Equivalent to `set(v)`.
   *
   *  Example: {{{
   *    val x = Ref(0)
   *    atomic { implicit t =>
   *      ...
   *      x() = 10 // perform a write inside a transaction
   *      ...
   *    }
   *  }}}
   *  @param v a value to store in the `Ref`.
   *  @throws IllegalStateException if `txn` is not active. */
  def update(v: A)(implicit txn: InTxn) { set(v) }

  /** Performs a transactional write.  The new value will not be visible by
   *  any other threads until (and unless) `txn` successfully commits.
   *  Equivalent to `update(v)`.
   *  @param v a value to store in the `Ref`.
   *  @throws IllegalStateException if `txn` is not active.
   */
  def set(v: A)(implicit txn: InTxn)
}
