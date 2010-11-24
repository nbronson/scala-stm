/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

import scala.collection.{immutable, mutable}


object TSet {

  /** A `Set` that provides atomic execution of all of its methods. */
  trait View[A] extends mutable.Set[A] with mutable.SetLike[A, View[A]] {

    /** Returns the `TSet` perspective on this transactional set, which
     *  provides set functionality only inside atomic blocks.
     */
    def tset: TSet[A]

    /** Takes an atomic snapshot of this transactional set. */
    def snapshot: immutable.Set[A]

    override def empty: View[A] = throw new AbstractMethodError
  }


  /** Constructs and returns a new empty `TSet`. */
  def empty[A]: TSet[A] = impl.STMImpl.instance.newTSet[A]()

  /** Constructs and returns a new `TSet` that will contain the elements from
   *  `xs`.
   */
  def apply[A](xs: A*): TSet[A] = impl.STMImpl.instance.newTSet[A](xs)


  /** Allows a `TSet` in a transactional context to be used as a `Set`. */
  implicit def asSet[A](s: TSet[A])(implicit txn: InTxn): View[A] = s.single
}


/** A transactional set implementation that requires that all of its set-like
 *  operations be called from inside an atomic block.  Rather than extending
 *  `Set`, an implicit conversion is provided from `TSet` to `Set` if the
 *  current scope is part of an atomic block (see `TSet.asSet`).
 *
 *  The elements (with type `A`) must be immutable, or at least not modified
 *  while they are in the set.  The `TSet` implementation assumes that it can
 *  safely perform equality and hash checks outside a transaction without
 *  affecting atomicity. 
 */
trait TSet[A] {

  /** Returns an instance that provides transactional set functionality without
   *  requiring that operations be performed inside the static scope of an
   *  atomic block.
   */
  def single: TSet.View[A]

  // The following methods return the wrong receiver when invoked via the asSet
  // conversion.  They are exactly the methods of mutable.Set whose return type
  // is this.type.  Note that there are other methods of mutable.Set that we
  // allow to use the implicit mechanism, such as add(k).
  
  def += (x: A)(implicit txn: InTxn): this.type = { single += x ; this }
  def += (x1: A, x2: A, xs: A*)(implicit txn: InTxn): this.type = { single.+= (x1, x2, xs: _*) ; this }
  def ++= (xs: TraversableOnce[A])(implicit txn: InTxn): this.type = { single ++= xs ; this }
  def -= (x: A)(implicit txn: InTxn): this.type = { single -= x ; this }
  def -= (x1: A, x2: A, xs: A*)(implicit txn: InTxn): this.type = { single.-= (x1, x2, xs: _*) ; this }
  def --= (xs: TraversableOnce[A])(implicit txn: InTxn): this.type = { single --= xs ; this }
  def retain(p: A => Boolean)(implicit txn: InTxn): this.type = { single retain p ; this }
}
