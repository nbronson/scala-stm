/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package impl
 
/** `TxnContext` captures the implementation-specific functionality of locating
 *  the `Txn` dynamically bound to the current `Thread`.  Users should use the
 *  lookup methods provided by `object Txn`.
 */
trait TxnContext {

  // If an STM implementation can locate a dynamically scoped Txn directly,
  // then it should override currentOrNull.  If the dynamic lookup is slower
  // than an instanceof check, it should override only dynCurrentOrNull.

  /** Returns the `Txn` active on the current thread, or null if none, possibly
   *  using the statically-bound `MaybeTxn` to reduce the amount of work
   *  required.
   */
  def currentOrNull(implicit mt: MaybeTxn): Txn = mt match {
    case t: Txn => t
    case _ => dynCurrentOrNull
  }

  /** Returns the `Txn` active on the current thread, or null if none, always
   *  performing a full dynamic lookup.
   */
  def dynCurrentOrNull: Txn
}
