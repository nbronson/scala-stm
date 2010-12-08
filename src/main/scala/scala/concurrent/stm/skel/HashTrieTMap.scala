/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm
package skel

import skel.{TxnHashTrie => Impl}
import scala.collection.mutable.Builder

object HashTrieTMap {
  
  def empty[A, B]: TMap[A, B] = new HashTrieTMap(Ref(Impl.emptyMapNode[A, B]).single)

  def newBuilder[A, B] = new Builder[(A, B), TMap[A, B]] {
    var root = Impl.emptyMapBuildingNode[A, B]

    def clear() { root = Impl.emptyMapBuildingNode[A, B] }

    def += (kv: (A, B)): this.type = { root = Impl.buildingPut(root, kv._1, kv._2) ; this }

    def result(): TMap[A, B] = {
      val r = root
      root = null
      new HashTrieTMap(Ref(r.endBuild).single)
    }
  }
}

private[skel] class HashTrieTMap[A, B] private (private val root: Ref.View[Impl.Node[A, B]]) extends TMapViaClone[A, B] {

  //// construction

  override def empty: TMap.View[A, B] = new HashTrieTMap(Ref(Impl.emptyMapNode[A, B]).single)
  override def clone(): HashTrieTMap[A, B] = new HashTrieTMap(Impl.clone(root))

  //// TMap.View aggregates

  override def isEmpty: Boolean = !Impl.sizeGE(root, 1)
  override def size: Int = Impl.size(root)
  override def iterator: Iterator[(A, B)] = Impl.mapIterator(root)
  override def foreach[U](f: ((A, B)) => U) { Impl.mapForeach(root, f) }

  override def clear() { root() = Impl.emptyMapNode[A, B] }

  //// TMap.View per-element

  override def contains(key: A): Boolean = Impl.contains(root, key)
  override def apply(key: A): B = Impl.getOrThrow(root, key)
  def get(key: A): Option[B] = Impl.get(root, key)

  override def put(key: A, value: B): Option[B] = Impl.put(root, key, value)
  override def update(key: A, value: B) { Impl.put(root, key, value) }
  override def += (kv: (A, B)): this.type = { Impl.put(root, kv._1, kv._2) ; this }

  override def remove(key: A): Option[B] = Impl.remove(root, key)
  override def -= (key: A): this.type = { Impl.remove(root, key) ; this }

  //// optimized TMap versions

  override def foreach[U](f: ((A, B)) => U)(implicit txn: InTxn) = Impl.mapForeach(root.ref, f)

  override def contains(key: A)(implicit txn: InTxn): Boolean = Impl.contains(root.ref, key)
  override def apply(key: A)(implicit txn: InTxn): B = Impl.getOrThrow(root.ref, key)
  override def get(key: A)(implicit txn: InTxn): Option[B] = Impl.get(root.ref, key)

  override def put(key: A, value: B)(implicit txn: InTxn): Option[B] = Impl.put(root.ref, key, value)
  override def update(key: A, value: B)(implicit txn: InTxn) { Impl.put(root.ref, key, value) }
  override def += (kv: (A, B))(implicit txn: InTxn): this.type = { Impl.put(root.ref, kv._1, kv._2) ; this }

  override def remove(key: A)(implicit txn: InTxn): Option[B] = Impl.remove(root.ref, key)
  override def -= (key: A)(implicit txn: InTxn): this.type = { Impl.remove(root.ref, key) ; this }
}
