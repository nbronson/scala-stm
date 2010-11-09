/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

import scala.collection.{immutable, mutable}


object TMap {

  /** A `Map` that provides atomic execution of all of its methods. */
  trait View[A, B] extends mutable.Map[A, B] with mutable.MapLike[A, B, View[A, B]] {

    /** Returns the `TMap` perspective on this transactional map, which
     *  provides map functionality only inside atomic blocks.
     */
    def tmap: TMap[A, B]

    /** Takes an atomic snapshot of this transactional map. */
    def snapshot: immutable.Map[A, B]

    override def empty: View[A, B] = throw new AbstractMethodError
  }


  /** Constructs and returns a new empty `TMap`. */
  def empty[A, B]: TMap[A, B] = impl.STMImpl.instance.newTMap[A, B]()

  /** Constructs and returns a new `TMap` that will contain the key/value pairs
   *  from `data`.
   */
  def apply[A, B](data: TraversableOnce[(A, B)]): TMap[A, B] = impl.STMImpl.instance.newTMap[A, B](data)

  
  /** Allows a `TMap` in a transactional context to be used as a `Map`. */
  implicit def asMap[A, B](m: TMap[A, B])(implicit txn: InTxn): View[A, B] = m.single
}


/** A transactional map implementation that requires that all of its map-like
 *  operations be called from inside an atomic block.  Rather than extending
 *  `Map`, an implicit conversion is provided from `TMap` to `Map` if the
 *  current scope is part of an atomic block (see `TMap.asMap`).
 */
trait TMap[A, B] {

  /** Returns an instance that provides transactional map functionality without
   *  requiring that operations be performed inside the static scope of an
   *  atomic block.
   */
  def single: TMap.View[A, B]
}
