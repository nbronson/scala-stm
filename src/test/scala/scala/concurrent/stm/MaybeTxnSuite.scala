/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

import org.scalatest.FunSuite

class MaybeTxnSuite extends FunSuite {
  test("implicit Txn match") {
    implicit val txn: Txn = new ri.StubTxn

    assert(implicitly[MaybeTxn] eq txn)
  }

  test("implicit TxnUnknown match") {
    assert(implicitly[MaybeTxn] eq TxnUnknown)
  }
}
