/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package impl
 
/** `TxnContext` captures the implementation-specific functionality of locating
 *  the `InTxn` dynamically bound to the current `Thread`.  Users should use the
 *  lookup methods provided by `object Txn`.
 */
trait TxnContext {

  /** Returns `Some(txn)` if `txn` is the `InTxn` active on the current thread,
   *  `None` otherwise.
   */
  def current(implicit mt: MaybeTxn): Option[InTxn]
}
