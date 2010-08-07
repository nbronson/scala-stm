/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA

import org.scalatest.FunSuite

class MaybeTxnSuite extends FunSuite {
  test("implicit Txn match") {
    implicit val txn = new Txn {
      def retry(): Nothing = throw new AbstractMethodError
    }

    assert(implicitly[MaybeTxn] eq txn)
  }

  test("implicit TxnUnknown match") {
    assert(implicitly[MaybeTxn] eq TxnUnknown)
  }
}
