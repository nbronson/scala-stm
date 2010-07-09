/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA

/** An object that represents the absence of a statically-bound current
 *  transaction.
 *  @see scala.concurrent.stmA.MaybeTxn
 */
object TxnUnknown extends MaybeTxn
