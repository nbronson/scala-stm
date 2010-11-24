/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package skel

import scala.collection.generic.Growable

class HashTrieTMap[A, B] private (private val root: Ref.View[TxnHashTrie.Node[A, B]]) extends TMapViaClone[A, B] {

  def this() = this(Ref(TxnHashTrie.emptyMapNode[A, B]).single)

  def this(kvs: TraversableOnce[(A, B)]) = { this() ; (this: Growable[(A, B)]) ++= kvs }

  override def empty: TMap.View[A, B] = new HashTrieTMap[A, B]()

  override def clone(): HashTrieTMap[A, B] = new HashTrieTMap(TxnHashTrie.clone(root))

  override def iterator: Iterator[(A, B)] = TxnHashTrie.mapIterator(root)

  override def foreach[U](f: ((A, B)) => U) { TxnHashTrie.mapForeach(root, f) }

  def get(key: A): Option[B] = TxnHashTrie.get(root, key)

  override def put(key: A, value: B): Option[B] = TxnHashTrie.put(root, key, value)

  override def update(key: A, value: B) { put(key, value) }

  override def += (kv: (A, B)): this.type = { update(kv._1, kv._2) ; this }

  override def remove(key: A): Option[B] = TxnHashTrie.remove(root, key)

  override def -= (key: A): this.type = { remove(key) ; this }

  override def clear() { root() = TxnHashTrie.emptyMapNode[A, B] }
}
