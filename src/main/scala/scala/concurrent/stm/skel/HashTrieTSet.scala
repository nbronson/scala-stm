/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm
package skel

import scala.collection.mutable
import scala.concurrent.stm.skel.TxnHashTrie.SetBuildingNode

private[stm] object HashTrieTSet {

  def empty[A]: TSet[A] = new HashTrieTSet(Ref(TxnHashTrie.emptySetNode[A]).single)

  def newBuilder[A]: mutable.Builder[A, TSet[A]] = new mutable.Builder[A, TSet[A]] {
    var root: SetBuildingNode[A] = TxnHashTrie.emptySetBuildingNode[A]

    def clear(): Unit = { root = TxnHashTrie.emptySetBuildingNode[A] }

    def += (elem: A): this.type = { root = TxnHashTrie.buildingAdd(root, elem) ; this }

    def result(): TSet[A] = {
      val r = root
      root = null
      new HashTrieTSet(Ref(r.endBuild).single)
    }
  }
}

private[skel] class HashTrieTSet[A] private (root0: Ref.View[TxnHashTrie.SetNode[A]]
                                              ) extends TxnHashTrie[A, AnyRef](root0) with TSetViaClone[A] {

  //// construction

  override def empty: TSet.View[A] = new HashTrieTSet(Ref(TxnHashTrie.emptySetNode[A]).single)  
  override def clone: HashTrieTSet[A] = new HashTrieTSet(cloneRoot)

  //// TSet.View aggregates

  override def isEmpty: Boolean = singleIsEmpty
  override def size: Int = singleSize
  override def iterator: Iterator[A] = setIterator
  override def foreach[U](f: A => U): Unit = { singleSetForeach(f) }
  override def clear(): Unit = { root() = TxnHashTrie.emptySetNode[A] }

  //// TSet.View per-element

  def contains(elem: A): Boolean = singleContains(elem)

  override def add(elem: A): Boolean = singlePut(elem, null).isEmpty
  def += (elem: A): this.type = { singlePut(elem, null) ; this }

  override def remove(elem: A): Boolean = singleRemove(elem).isDefined
  def -= (elem: A): this.type = { singleRemove(elem) ; this }

  //// optimized TSet versions

  def isEmpty(implicit txn: InTxn): Boolean = txnIsEmpty
  def size(implicit txn: InTxn): Int = singleSize
  def foreach[U](f: A => U)(implicit txn: InTxn): Unit = txnSetForeach(f)

  def contains(elem: A)(implicit txn: InTxn): Boolean = txnContains(elem)
  def add(elem: A)(implicit txn: InTxn): Boolean = txnPut(elem, null ).isEmpty
  def remove(elem: A)(implicit txn: InTxn): Boolean = txnRemove(elem).isDefined

  def retain(p: (A) => Boolean)(implicit txn: InTxn): this.type = { single retain p ; this }
}
