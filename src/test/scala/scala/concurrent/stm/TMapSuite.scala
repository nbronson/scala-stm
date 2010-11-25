/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

import org.scalatest.FunSuite
import scala.util.Random
import scala.collection.mutable

class TMapSuite extends FunSuite {

  private def value(k: Int) = "x" + k
  private def kvRange(b: Int, e: Int) = (b until e) map { i => (i -> value(i)) }

  test("number equality trickiness") {
    assert(TMap(10L -> "").single contains 10)
    //assert(TMap(10 -> "").single contains 10L)
    assert(TMap[Number, String]((10L: Number) -> "").single contains 10)
    assert(TMap[Number, String]((10: Number) -> "").single contains 10L)
    assert(TMap[Any, String](10L -> "").single contains 10)
    assert(TMap[Any, String](10 -> "").single contains 10L)
    assert(TMap[AnyRef, String](10L.asInstanceOf[AnyRef] -> "").single contains 10.asInstanceOf[AnyRef])
    assert(TMap[AnyRef, String](10.asInstanceOf[AnyRef] -> "").single contains 10L.asInstanceOf[AnyRef])
  }

  test("character equality trickiness") {
    assert(TMap('*' -> "").single contains 42)
    assert(TMap((42: Byte) -> "").single contains '*')
    assert(TMap[Any, String]('*' -> "").single contains (42: Short))
    assert(TMap[Any, String](42L -> "").single contains '*')
    assert(TMap[AnyRef, String]('*'.asInstanceOf[AnyRef] -> "").single contains 42.0.asInstanceOf[AnyRef])
    assert(TMap[AnyRef, String](42.0f.asInstanceOf[AnyRef] -> "").single contains '*'.asInstanceOf[AnyRef])
  }

  case class BadHash(k: Int) {
    override def hashCode = if (k > 500) k / 5 else 0
  }

  test("correct despite poor hash function") {
    val mut = TMap(((0 until 1000) map { i => (BadHash(i) -> i) }): _*).single
    for (i <- -500 until 1500)
      assert(mut.get(BadHash(i)) === (if (i >= 0 && i < 1000) Some(i) else None))
  }

  test("clone captures correct atomic writes") {
    val mut = TMap(kvRange(0, 100): _*)
    val z = atomic { implicit txn =>
      mut ++= kvRange(100, 200)
      val z = mut.clone.single
      mut ++= kvRange(200, 300)
      z
    }
    assert(z.size === 200)
    for (i <- 0 until 200)
      assert(z(i) === value(i))
  }

  test("clone doesn't include discarded writes") {
    val mut = TMap(kvRange(0, 100): _*)
    val z = atomic { implicit txn =>
      atomic { implicit txn =>
        mut ++= kvRange(100, 200)
        if ("likely".## != 0)
          retry
      } orAtomic { implicit txn =>
        mut ++= kvRange(200, 300)
      }
      val z = mut.clone.single
      atomic { implicit txn =>
        mut ++= kvRange(300, 400)
        if ("likely".## != 0)
          retry
      } orAtomic { implicit txn =>
        mut ++= kvRange(400, 500)
      }
      z
    }
    assert(z.size === 200)
    for (i <- 0 until 100)
      assert(z(i) === value(i))
    for (i <- 200 until 300)
      assert(z(i) === value(i))
  }

  test("clone is transactional") {
    val mut = TMap(kvRange(0, 100): _*)
    val z = atomic { implicit txn =>
      atomic { implicit txn =>
        mut ++= kvRange(100, 105)
        if ("likely".## != 0)
          retry
      } orAtomic { implicit txn =>
        mut ++= kvRange(200, 205)
      }
      val z = mut.clone.single
      atomic { implicit txn =>
        z ++= kvRange(300, 305)
        if ("likely".## != 0)
          retry
      } orAtomic { implicit txn =>
        z ++= kvRange(400, 405)
      }
      z
    }
    assert(z.size === 110)
    for (i <- 0 until 100)
      assert(z(i) === value(i))
    for (i <- 200 until 205)
      assert(z(i) === value(i))
    for (i <- 400 until 405)
      assert(z(i) === value(i))
  }

  test("random sequential") {
    val rand = new Random()

    def nextKey(): String = "key" + (rand.nextInt() >>> rand.nextInt())
    def nextValue(): Int = rand.nextInt()

    var mut = TMap.empty[String, Int].single
    val base = mutable.Map.empty[String, Int]

    val total = 20000
    for (i <- 0 until total) {
      val pct = rand.nextInt(100)
      val k = nextKey
      val v = nextValue
      if (pct < 15) {
        assert(base.get(k) === mut.get(k))
      } else if (pct < 20) {
        val a = try { Some(base(k)) } catch { case _ => None }
        val b = try { Some(mut(k)) } catch { case _ => None }
        assert(a === b)
      } else if (pct < 35) {
        assert(base.put(k, v) === mut.put(k, v))
      } else if (pct < 40) {
        base(k) = v
        mut(k) = v
      } else if (pct < 45) {
        assert(base.contains(k) === mut.contains(k))
      } else if (pct < 55) {
        assert(base.remove(k) === mut.remove(k))
      } else if (pct < 60) {
        for (j <- 0 until (i / (total / 20))) {
          if (!base.isEmpty) {
            val k1 = base.iterator.next._1
            assert(base.remove(k1) === mut.remove(k1))
          }
        }
      } else if (pct < 63) {
        mut = mut.clone
      } else if (pct < 66) {
        assert(base.toMap === mut.snapshot)
      } else if (pct < 69) {
        assert(base.isEmpty === mut.isEmpty)
      } else if (pct < 72) {
        assert(base.size === mut.size)
      } else if (pct < 77) {
        assert(base eq (base += (k -> v)))
        assert(mut eq (mut += (k -> v)))
      } else if (pct < 80) {
        val kv2 = (nextKey -> nextValue)
        val kv3 = (nextKey -> nextValue)
        assert(base eq (base += ((k -> v), kv2, kv3)))
        assert(mut eq (mut += ((k -> v), kv2, kv3)))
      } else if (pct < 83) {
        val kv2 = (nextKey -> nextValue)
        val kv3 = (nextKey -> nextValue)
        assert(base eq (base ++= Array((k -> v), kv2, kv3)))
        assert(mut eq (mut ++= Array((k -> v), kv2, kv3)))
      } else if (pct < 88) {
        assert(base eq (base -= k))
        assert(mut eq (mut -= k))
      } else if (pct < 91) {
        val k2 = nextKey
        val k3 = nextKey
        assert(base eq (base -= (k, k2, k3)))
        assert(mut eq (mut -= (k, k2, k3)))
      } else if (pct < 94) {
        val k2 = nextKey
        val k3 = nextKey
        assert(base eq (base --= Array(k, k2, k3)))
        assert(mut eq (mut --= Array(k, k2, k3)))
      } else if (pct < 95) {
        mut = TMap(mut.toArray: _*).single
      } else if (pct < 96) {
        mut = TMap.empty[String, Int].single ++= mut
      } else if (pct < 97) {
        val m2 = mutable.Map.empty[String, Int]
        for (kv <- mut) { m2 += kv }
        assert(base === m2)
      } else if (pct < 98) {
        val m2 = mutable.Map.empty[String, Int]
        for (kv <- mut.iterator) { m2 += kv }
        assert(base === m2)
      }
    }
  }

  private def now = System.currentTimeMillis

  test("sequential non-txn read performance") {
    for (pass <- 0 until 10) {
      for (size <- List(10, 100, 1000, 100000)) {
        val m = TMap(kvRange(0, size): _*).single
        val t0 = now
        var i = 0
        var k = 0
        while (i < 1000000) {
          assert(m.contains(k) == (k < size))
          i += 1
          k = if (k == 2 * size - 1) 0 else k + 1
        }
        val elapsed = now - t0
        print(size + " keys/map -> " + elapsed + " nanos/contain,  ")
      }
      println
    }
  }

  test("sequential build performance") {
    for (pass <- 0 until 10) {
      for (size <- List(10, 100, 1000, 100000)) {
        val src = kvRange(0, size).toArray
        val t0 = now
        var outer = 0
        while (outer < 1000000) {
          TMap(src: _*)
          outer += size
        }
        val elapsed = now - t0
        print(size + " keys/map -> " + elapsed + " nanos/init/key,  ")
      }
      println
    }
  }

  test("sequential non-txn update performance") {
    for (pass <- 0 until 10) {
      for (size <- List(10, 100, 1000, 100000)) {
        val m = TMap(kvRange(0, size): _*).single
        val t0 = now
        var i = 0
        var k = 0
        while (i < 1000000) {
          val prev = m.put(k, "foobar")
          assert(!prev.isEmpty)
          i += 1
          k = if (k == size - 1) 0 else k + 1
        }
        val elapsed = now - t0
        print(size + " keys/map -> " + elapsed + " nanos/put,  ")
      }
      println
    }
  }

}
