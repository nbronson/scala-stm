/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package skel

import collection._

class SimpleTMap[A, B](m0: immutable.Map[A, B]) extends TMap.View[A, B] with TMap[A, B] {

  private val contents = Ref(m0).single

  def tmap: TMap[A, B] = this
  def single: TMap.View[A, B] = this

  def snapshot: immutable.Map[A, B] = contents()

  //////////// read-only

  // We get atomicity for the read-only operations by returning a consistent
  // iterator that visits a snapshot.

  override def size: Int = snapshot.size

  def get(key: A): Option[B] = snapshot.get(key)

  def iterator: Iterator[(A, B)] = snapshot.iterator

  override def foreach[U](f: ((A, B)) => U) { snapshot.foreach(f) }  


  //////////// builder functionality (from mutable.MapLike via TMap.View)

  override protected[this] def newBuilder: TMap.View[A, B] = empty

  override def result: TMap.View[A, B] = this


  //////////// construction of new TMaps

  // Our cheap clone() means that mutable.MapLike's implementations of +, ++,
  // -, and -- are all pretty reasonable.

  override def clone() = new SimpleTMap[A, B](snapshot)

  override def empty = new SimpleTMap[A, B](immutable.Map.empty[A, B])

  // this is overridden here to avoid a bit of boxing 
  override def updated [B1 >: B](key: A, value: B1): TMap.View[A, B1] = new SimpleTMap[A, B1](snapshot.updated(key, value))


  //////////// mutation

  // We need our own implementations of mutation to get the appropriate
  // atomicity boundaries.

  override def put(key: A, value: B): Option[B] = (contents getAndTransform { _.updated(key, value) }).get(key)

  override def update(key: A, value: B) { contents transform { _.updated(key, value) } }

  override def += (kv: (A, B)): this.type = { update(kv._1, kv._2) ; this }

  override def += (elem1: (A, B), elem2: (A, B), elems: (A, B) *): this.type = { contents transform { _.+(elem1, elem2, elems: _*) } ; this }

  override def ++= (elems: TraversableOnce[(A, B)]): this.type = { contents transform { _ ++ elems } ; this }

  override def remove(key: A): Option[B] = (contents getAndTransform { _ - key }).get(key)

  override def -= (key: A): this.type = { contents transform { _ - key } ; this }

  override def -= (key1: A, key2: A, keys: A *): this.type = { contents transform { _.-(key1, key2, keys: _*) } ; this }

  override def --= (keys: TraversableOnce[A]): this.type = { contents transform { _ -- keys } ; this }

  override def clear() { contents() = immutable.Map.empty[A, B] }

  override def getOrElseUpdate(key: A, op: => B): B = {
    contents().get(key) getOrElse {
      atomic { implicit txn =>
        val m = contents()
        m.get(key) getOrElse {
          val v = op
          contents() = m.updated(key, v)
          v
        }
      }
    }
  }

  override def transform(f: (A, B) => B): this.type = {
    atomic { implicit txn => contents() = contents() transform f }
    this
  }

  override def retain(p: (A, B) => Boolean): this.type = {
    atomic { implicit txn => contents() = contents() filterNot { kv => p(kv._1, kv._2) } }
    this
  }
}
