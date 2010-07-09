/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA

object MaybeTxn {
  implicit val unknown = TxnUnknown  
}

/** `MaybeTxn` allows lookup of the implicit `Txn` instance without failing if
 *  the `Txn` is not known at compile time.  `implicitly[MaybeTxn]` will bind
 *  to an implicit `Txn` if one is available, otherwise it will bind to the
 *  object `TxnUnkown`.  A `MaybeTxn` of `TxnUnknown` should trigger a
 *  dynamically-scoped `Txn` search using `Txn.current`.
 */
trait MaybeTxn
