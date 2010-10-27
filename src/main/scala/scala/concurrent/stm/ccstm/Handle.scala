/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm.ccstm


private[ccstm] object Handle {

  /** A `Handle.Provider` has a single associated handle, and performs equality
   *  and hashing based on the handle's `base` and `offset`.
   */
  trait Provider[T] {
    def handle: Handle[T]

    override def equals(o: AnyRef): Boolean = o match {
      case rhs: Handle.Provider[_] => {
        val h0 = handle
        val h1 = rhs.handle
        (h0 eq h1) || ((h0.base eq h1.base) && (h0.offset == h1.offset))
      }
      case _ => false
    }

    override def hashCode: Int = {
      val h = handle
      CCSTM.hash(h.base, h.offset)
    }
  }
}

/** A `Handle` defines the operations that must be available for a particular
 *  memory location in order for it to be managed by CCSTM.  The identity of
 *  the location is determined by `base` and `offset`, with `base` be used only
 *  in comparisons using `eq` or `ne` (no methods of `base` will ever be
 *  invoked).  Metadata is identified by `base` and `metaOffset` (the assumption
 *  made during blocking is that a write to a handle's `meta` may affect the
 *  `meta` read by any handle with the same `base` and `metaOffset`).
 */
private[ccstm] abstract class Handle[T] {
  def meta: Long
  def meta_=(v: Long)
  def metaCAS(before: Long, after: Long): Boolean
  def base: AnyRef
  def offset: Int
  def metaOffset: Int
  def data: T
  def data_=(v: T)
}
