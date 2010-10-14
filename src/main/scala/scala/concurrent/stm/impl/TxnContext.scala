/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package impl
 
/** `TxnContext` captures the implementation-specific functionality of locating
 *  the `Txn` dynamically bound to the current `Thread`.  Users should use the
 *  lookup methods provided by `object Txn`.
 */
trait TxnContext {

  /** Returns the `Txn` active on the current thread, or null if none, possibly
   *  using the statically-bound `MaybeTxn` to reduce the amount of work
   *  required.
   */
  def current(implicit mt: MaybeTxn): Option[Txn]
}
