/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package skel

import scala.collection.generic.Growable

class HashTrieTSet[A] private (private val root: Ref.View[TxnHashTrie.SetNode[A]]) extends TSetViaClone[A] {

  def this() = this(Ref(TxnHashTrie.emptySetNode[A]).single)

  def this(xs: TraversableOnce[A]) = { this() ; (this: Growable[A]) ++= xs }

  override def empty: TSet.View[A] = new HashTrieTSet[A]()

  override def clone(): HashTrieTSet[A] = new HashTrieTSet(TxnHashTrie.clone(root))

  override def iterator: Iterator[A] = TxnHashTrie.setIterator(root)

  override def foreach[U](f: A => U) { TxnHashTrie.setForeach(root, f) }

  def contains(elem: A): Boolean = TxnHashTrie.contains(root, elem)

  override def add(elem: A): Boolean = TxnHashTrie.put(root, elem, null).isEmpty

  override def += (elem: A): this.type = { TxnHashTrie.put(root, elem, null) ; this }

  override def remove(elem: A): Boolean = !TxnHashTrie.remove(root, elem).isEmpty

  override def -= (elem: A): this.type = { TxnHashTrie.remove(root, elem) ; this }

  override def clear() { root() = TxnHashTrie.emptySetNode[A] }
}
