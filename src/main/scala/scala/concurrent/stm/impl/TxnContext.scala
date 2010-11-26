/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm
package impl
 
/** `TxnContext` captures the implementation-specific functionality of locating
 *  the `InTxn` dynamically bound to the current `Thread`.  Users should use the
 *  lookup methods provided by `object Txn`.
 *
 *  @author Nathan Bronson
 */
trait TxnContext {

  /** Returns `Some(txn)` if `txn` is the `InTxn` active or in the process of
   *  committing on the current thread, `None` otherwise.
   */
  def findCurrent(implicit mt: MaybeTxn): Option[InTxn]
}
