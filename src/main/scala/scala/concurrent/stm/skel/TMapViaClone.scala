/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package skel

import scala.collection.{immutable, mutable}

private[stm] object TMapViaClone {
  class FrozenMutableMap[A, B](self: mutable.Map[A, B]) extends immutable.Map[A, B] {
    override def size: Int = self.size
    def get(key: A): Option[B] = self.get(key)
    def iterator: Iterator[(A, B)] = self.iterator
    override def foreach[U](f: ((A, B)) => U) { self foreach f }
    def + [B1 >: B](kv: (A, B1)): immutable.Map[A, B1] =
        new FrozenMutableMap(self.clone().asInstanceOf[mutable.Map[A, B1]] += kv)
    def - (k: A): immutable.Map[A, B] = new FrozenMutableMap(self.clone() -= k)
    // TODO: more pass-throughs for efficiency
  }
}

/** Provides an implementation for the bulk of the functionality of `TMap` and
 *  `TMap.View` by making extensive use of `clone()`.  Assumes that the
 *  underlying implementation of `clone()` is O(1).
 */
private[stm] trait TMapViaClone[A, B] extends TMap.View[A, B] with TMap[A, B] {
  import TMapViaClone._

  // Implementations may be able to do better.
  override def snapshot: immutable.Map[A, B] = new FrozenMutableMap(clone())

  def tmap: TMap[A, B] = this
  def single: TMap.View[A, B] = this


  //////////// builder functionality (from mutable.MapLike via TMap.View)

  override protected[this] def newBuilder: TMap.View[A, B] = empty

  override def result: TMap.View[A, B] = this


  //////////// construction of new TMaps

  // A cheap clone() means that mutable.MapLike's implementations of +, ++,
  // -, and -- are all pretty reasonable.

  override def clone(): TMap.View[A, B]

  //////////// atomic compound ops

  override def getOrElseUpdate(key: A, op: => B): B = {
    get(key) getOrElse {
      atomic { implicit txn =>
        get(key) getOrElse { val v = op ; put (key, v) ; v }
      }
    }
  }

  override def transform(f: (A, B) => B): this.type = {
    atomic { implicit txn =>
      for (kv <- this)
        update(kv._1, f(kv._1, kv._2))
    }
    this
  }

  override def retain(p: (A, B) => Boolean): this.type = {
    atomic { implicit txn =>
      for (kv <- this)
        if (!p(kv._1, kv._2))
          (this: TMap[A, B]) -= kv._1
    }
    this
  }
}
