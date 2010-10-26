/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm.ccstm


private[ccstm] object Handle {

  /** A `Handle.Provider` has a single associated handle, and performs equality
   *  and hashing based on the handle's `ref` and `offset`.
   */
  trait Provider[T] {
    def handle: Handle[T]

    override def equals(o: AnyRef): Boolean = o match {
      case rhs: Handle.Provider[_] => {
        val h0 = handle
        val h1 = rhs.handle
        (h0 eq h1) || ((h0.ref eq h1.ref) && (h0.offset == h1.offset))
      }
      case _ => false
    }

    override def hashCode: Int = {
      val h = handle
      CCSTM.hash(h.ref, h.offset)
    }
  }
}

/** A `Handle` defines the operations that must be available for a particular
 *  memory location in order for it to be managed by CCSTM.  The identity of
 *  the location is determined by `ref` and `offset`, with `ref` be used only
 *  in comparisons using `eq` or `ne` (no methods of `ref` will ever be
 *  invoked).  Metadata is identified by `ref` and `metaOffset` (the assumption
 *  made during blocking is that a write to a handle's `meta` may affect the
 *  `meta` read by any handle with the same `ref` and `metaOffset`). 
 */
private[ccstm] abstract class Handle[T] {
  def meta: Long
  def meta_=(v: Long)
  def metaCAS(before: Long, after: Long): Boolean
  def ref: AnyRef
  def offset: Int
  def metaOffset: Int
  def data: T
  def data_=(v: T)
}
