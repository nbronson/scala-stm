/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm.ccstm


/** A `Handle` defines the operations that must be available for a particular
 *  memory location in order for it to be managed by CCSTM.  The identity of
 *  the location is determined by `ref` and `offset`, with `ref` be used only
 *  in comparisons using `eq` or `ne` (no methods of `ref` will ever be
 *  invoked).  Metadata may be shared between multiple locations.
 */
abstract class Handle[T] {
  def meta: Long
  def meta_=(v: Long)
  def metaCAS(before: Long, after: Long): Boolean
  def ref: AnyRef
  def offset: Int
  def metaOffset: Int
  def data: T
  def data_=(v: T)
}
