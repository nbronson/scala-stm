/* scala-stm - (c) 2009-2011, Stanford University, PPL */

import scala.concurrent.stm._

/** Tests of the relaxed validation methods `getWith` and `relaxedGet` in
 *  multi-threaded contexts.  Single-threaded tests are found in
 *  `IsolatedRefSuite` and more multi-threaded tests are embedded in
 *  `FlipperSuite`.
 */
object Test {

  def test(name: String)(block: => Unit) {
    println("running relaxed_validation " + name)
    block
  }

  def main(args: Array[String]) {
    test("self-write vs getWith") {
      val x = Ref(0)
      atomic { implicit txn =>
        assert(x.getWith { _ & 1 } == 0)
        x() = 1
      }
      assert(x.single() == 1)
    }

    test("self-write vs getWith with interference") {
      val x = Ref(0)

      (new Thread { override def run { Thread.sleep(50) ; x.single() = 2 } }).start

      atomic { implicit txn =>
        assert(x.getWith { _ & 1 } == 0)
        Thread.sleep(100)
        assert(x.swap(1) == 2)
      }
      assert(x.single() == 1)
    }

    test("getWith multiple revalidations") {
      val x = Ref("abc")

      (new Thread {
        override def run {
          for (i <- 0 until 10) {
            Thread.sleep(10)
            x.single.transform { _ + "X" }
          }
          x.single() = ""
        }
      }).start

      assert(atomic { implicit txn =>
        for (i <- 0 until 10) {
          x.getWith { _.take(3) }
          Thread.sleep(15)
        }
        x.getWith { _.take(3) }
      } == "")
    }

    test("self-write vs failing transformIfDefined") {
      val x = Ref(0)
      atomic { implicit txn =>
        assert(!x.transformIfDefined {
          case 1 => 2
        })
        x() = 1
      }
      assert(x.single() == 1)
    }

    test("self-write vs failing transformIfDefined with interference") {
      val x = Ref(0)

      (new Thread { override def run { Thread.sleep(50) ; x.single() = 2 } }).start

      atomic { implicit txn =>
        assert(!x.transformIfDefined {
          case v if (v & 1) != 0 => v
        })
        Thread.sleep(100)
        assert(x.swap(1) == 2)
      }
      assert(x.single() == 1)
    }

    test("self-write vs relaxedGet") {
      val x = Ref(0)
      atomic { implicit txn =>
        assert(x.relaxedGet( _ == _ ) == 0)
        x() = 1
      }
      assert(x.single() == 1)
    }

    test("self-write vs relaxedGet with interference") {
      val x = Ref(0)

      (new Thread { override def run { Thread.sleep(50) ; x.single() = 2 } }).start

      atomic { implicit txn =>
        assert(x.relaxedGet({ (seen, correct) => (seen & 1) == (correct & 1) }) == 0)
        Thread.sleep(100)
        assert(x.swap(1) == 2)
      }
      assert(x.single() == 1)
    }

    test("relaxedGet multiple accepting revalidations") {
      val x = Ref("abc")

      (new Thread {
        override def run {
          for (i <- 0 until 10) {
            Thread.sleep(10)
            x.single.transform { _ + "X" }
          }
          x.single() = ""
        }
      }).start

      val (first, last) = atomic { implicit txn =>
        val first = x.relaxedGet( (_, _) => true )
        for (i <- 0 until 10) {
          x.relaxedGet( (_, _) => true )
          Thread.sleep(15)
        }
        (first, x.relaxedGet( (_, _) => true ))
      }
      assert(first == "abc")
      assert(last == "")
    }

    test("relaxedGet multiple ending with equality check") {
      val x = Ref("abc")

      (new Thread {
        override def run {
          for (i <- 0 until 10) {
            Thread.sleep(10)
            x.single.transform { _ + "X" }
          }
          x.single() = ""
        }
      }).start

      val (first, last) = atomic { implicit txn =>
        val first = x.relaxedGet( _.isEmpty == _.isEmpty )
        for (i <- 0 until 10) {
          x.relaxedGet( _.isEmpty == _.isEmpty )
          Thread.sleep(15)
        }
        (first, x.relaxedGet( _ == _ ))
      }
      assert(first == "")
      assert(last == "")
    }
  }
}