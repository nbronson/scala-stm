/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

object MaybeTxn {
  implicit val unknown = TxnUnknown
}

/** `MaybeTxn` allows lookup of the implicit `InTxn` instance without failing
 *  if the `InTxn` is not known at compile time.  `implicitly[MaybeTxn]` will
 *  bind to an implicit `InTxn` if one is available, otherwise it will bind to
 *  the object `TxnUnkown`.  A `MaybeTxn` of `TxnUnknown` should trigger a
 *  dynamically-scoped `InTxn` search using `Txn.current`.
 */
trait MaybeTxn
