/* scala-stm - (c) 2009-2012, Stanford University, PPL */

package scala.concurrent.stm

import collection.{TraversableOnce, immutable, mutable, generic}


object TQueue {

  object View extends generic.SeqFactory[TQueue.View] {

    implicit def canBuildFrom[A]: generic.CanBuildFrom[Coll, A, TQueue.View[A]] = new GenericCanBuildFrom[A]

    override def empty[A] = TQueue.empty[A].single

    override def newBuilder[A] = new mutable.Builder[A, View[A]] {
      private val underlying = TQueue.newBuilder[A]

      def clear() { underlying.clear() }
      def += (x: A): this.type = { underlying += x ; this }
      override def ++=(xs: TraversableOnce[A]): this.type = { underlying ++= xs ; this }
      def result() = underlying.result().single
    }

    override def apply[A](xs: A*): TQueue.View[A] = (TQueue.newBuilder[A] ++= xs).result().single
  }

  /** A `Queue` that provides atomic execution of all of its methods. */
  trait View[A] extends mutable.Seq[A] with mutable.SeqLike[A, View[A]] {

    /** Returns the `TQueue` perspective on this transactional queue, which
     *  provides queue functionality only inside atomic blocks.
     */
    def tqueue: TQueue[A]

    def clone: TQueue.View[A]

    /** Takes an atomic snapshot of this transactional queue. */
    def snapshot: immutable.Seq[A]

    override def empty: View[A] = TQueue.empty[A].single
    override def companion: generic.GenericCompanion[View] = View    
    override protected[this] def newBuilder: mutable.Builder[A, View[A]] = View.newBuilder[A]

    //////// queue operations not found in mutable.Seq

    // TODO
  }
  

  /** Constructs and returns a new empty `TQueue`. */
  def empty[A]: TQueue[A] = impl.STMImpl.instance.newTQueue[A]

  /** Returns a builder of `TQueue`. */
  def newBuilder[A]: mutable.Builder[A, TQueue[A]] = impl.STMImpl.instance.newTQueueBuilder[A]

  /** Constructs and returns a new `TQueue` that will contain the elements from
   *  `xs`.
   */
  def apply[A](xs: A*): TQueue[A] = (newBuilder[A] ++= xs).result()


  /** Allows a `TQueue` in a transactional context to be used as a
   *  `TQueue.View`.
   */
  implicit def asQueue[A](s: TQueue[A])(implicit txn: InTxn): View[A] = s.single
}


/** A transactional queue implementation that requires that all of its
 *  queue-like operations be called from inside an atomic block.  An implicit
 *  conversion is provided from `TQueue` to `TQueue.View` if the current
 *  scope is part of an atomic block (see `TQueue.asQueue`), which provides
 *  access to all of the methods of `Seq`.
 *
 *  The elements (with type `A`) must be immutable, or at least not modified
 *  while they are in the queue.  The `TQueue` implementation assumes that it
 *  can safely perform equality and hash checks outside a transaction without
 *  affecting atomicity. 
 *
 *  @author Nathan Bronson
 */
trait TQueue[A] {

  /** Returns an instance that provides transactional queue functionality
   *  without requiring that operations be performed inside the static scope
   *  of an atomic block.
   */
  def single: TQueue.View[A]

  def clone(implicit txn: InTxn): TQueue[A] = single.clone.tqueue

  // The following methods work fine via the asQueue mechanism, but are heavily
  // used.  We add transactional versions of them to allow overrides.

  def isEmpty(implicit txn: InTxn): Boolean
  def size(implicit txn: InTxn): Int
  def foreach[U](f: A => U)(implicit txn: InTxn)

  def contains(elem: A)(implicit txn: InTxn): Boolean
  def apply(elem: A)(implicit txn: InTxn): Boolean = contains(elem)
  def add(elem: A)(implicit txn: InTxn): Boolean
  def update(elem: A, included: Boolean)(implicit txn: InTxn) { if (included) add(elem) else remove(elem) }
  def remove(elem: A)(implicit txn: InTxn): Boolean

  // The following methods return the wrong receiver when invoked via the
  // asQueue conversion.  They are exactly the methods of mutable.Seq whose
  // return type is this.type.

  def transform(f: A => A)(implicit txn: InTxn): this.type
}
