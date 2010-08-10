/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

/** An object that represents the absence of a statically-bound current
 *  transaction.
 *  @see scala.concurrent.stm.MaybeTxn
 */
object TxnUnknown extends MaybeTxn
