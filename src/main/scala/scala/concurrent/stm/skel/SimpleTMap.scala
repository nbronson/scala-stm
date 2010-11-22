/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package skel

import collection._

class SimpleTMap[A, B](m0: immutable.Map[A, B]) extends TMapViaSnapshot[A, B] {

  private val contents = Ref(m0).single

  override def empty: TMap.View[A, B] = new SimpleTMap(immutable.Map.empty[A, B])

  override def clone(): TMap.View[A, B] = new SimpleTMap(snapshot)

  override def snapshot: immutable.Map[A, B] = contents()

  // this is an optimization to avoid boxing
  override def updated [B1 >: B](key: A, value: B1): TMap.View[A, B1] = new SimpleTMap(snapshot.updated(key, value))

  //////////// mutation

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
