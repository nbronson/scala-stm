/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm
package skel

import skel.{TxnHashTrie => Impl}

private[stm] class HashTrieTSet[A] private (private val root: Ref.View[Impl.SetNode[A]]) extends TSetViaClone[A] {

  //// construction

  def this() = this(Ref(Impl.emptySetNode[A]).single)
  def this(xs: TraversableOnce[A]) = this(Ref(Impl.buildSet(xs)).single)
  override def empty: TSet.View[A] = new HashTrieTSet[A]()
  override def clone(): HashTrieTSet[A] = new HashTrieTSet(Impl.clone(root))

  //// TSet.View aggregates

  override def isEmpty: Boolean = !Impl.sizeGE(root, 1)
  override def size: Int = Impl.size(root)
  override def iterator: Iterator[A] = Impl.setIterator(root)
  override def foreach[U](f: A => U) { Impl.setForeach(root, f) }
  override def clear() { root() = Impl.emptySetNode[A] }

  //// TSet.View per-element

  def contains(elem: A): Boolean = Impl.contains(root, elem)

  override def add(elem: A): Boolean = Impl.put(root, elem, null).isEmpty
  override def += (elem: A): this.type = { Impl.put(root, elem, null) ; this }

  override def remove(elem: A): Boolean = !Impl.remove(root, elem).isEmpty
  override def -= (elem: A): this.type = { Impl.remove(root, elem) ; this }

  //// optimized TSet versions

  override def foreach[U](f: A => U)(implicit txn: InTxn) { Impl.setForeach(root.ref, f) }

  override def contains(elem: A)(implicit txn: InTxn): Boolean = Impl.contains(root.ref, elem)

  override def add(elem: A)(implicit txn: InTxn): Boolean = Impl.put(root.ref, elem, null ).isEmpty
  override def += (elem: A)(implicit txn: InTxn): this.type = { Impl.put(root.ref, elem, null) ; this }
  
  override def remove(elem: A)(implicit txn: InTxn): Boolean = !Impl.remove(root.ref, elem).isEmpty
  override def -= (elem: A)(implicit txn: InTxn): this.type = { Impl.remove(root.ref, elem) ; this }
}
