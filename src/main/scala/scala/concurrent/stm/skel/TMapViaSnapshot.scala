/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm
package skel

import scala.collection.{immutable, mutable}

/** Provides an implementation for the bulk of the functionality of `TMap` and
 *  `TMap.View` by making extensive use of `snapshot`.  Assumes that the
 *  underlying implementation of `snapshot` is O(1).
 *
 *  @author Nathan Bronson
 */
private[stm] trait TMapViaSnapshot[A, B] extends TMap.View[A, B] with TMap[A, B] {

  def tmap: TMap[A, B] = this
  def single: TMap.View[A, B] = this

  //////////// read-only

  // We get atomicity for the read-only operations by returning a consistent
  // iterator that visits a snapshot.

  override def size: Int = snapshot.size

  def get(key: A): Option[B] = snapshot.get(key)

  def iterator: Iterator[(A, B)] = snapshot.iterator

  // TODO: should TMap.foreach start an atomic block?
  override def foreach[U](f: ((A, B)) => U) { snapshot.foreach(f) }  


  //////////// builder functionality (from mutable.MapLike via TMap.View)

  override protected[this] def newBuilder: TMap.View[A, B] = empty

  override def result: TMap.View[A, B] = this


  //////////// construction of new TMaps

  // A cheap clone() means that mutable.MapLike's implementations of +, ++,
  // -, and -- are all pretty reasonable.

  override def clone(): TMap.View[A, B]
}
