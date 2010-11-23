/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package skel

import collection._
import annotation.tailrec

object HashTrieTMap {
  private def BF = 32
  private def LogBF = 5
  private def MaxLeafCapacity = 16

  private def indexFor(hash: Int, shift: Int): Int = (hash >> shift) & (BF - 1)

  private def computeHash[A](v: A): Int = if (v == null) 0 else mix(v.##)
  private def mix(x: Int): Int = {
    // make sure that no matter what bits are varying, we get
    // selectivity in the bottom-most LogBF bits
    val y = x ^ (x >>> 5) ^ (x >>> 10)
    y ^ (y >>> 15) ^ (y >>> 30)
  }

  sealed abstract class Node[A, B](val gen: Int) {
    def clone(newGen: Int): Node[A, B]
    def get(hash: Int, key: A): Option[B]
    def put(hash: Int, key: A, value: B): Option[B]
    def shouldSplit(hash: Int): Boolean
    def withSplit(newGen: Int, shift: Int): Node[A, B]
    def remove(hash: Int, key: A): Option[B]

    /** Caller must freeze the node before constructing the iterator. */
    def iterator: Iterator[(A, B)]

    def draw(buf: StringBuilder, prefix: String)
  }

  class Branch[A, B](gen0: Int, val shift: Int, val children: TArray.View[Node[A, B]]) extends Node[A, B](gen0) {

    def clone(newGen: Int): Node[A, B] = {
      if (gen == newGen) this else new Branch(newGen, shift, TArray(children).single)
    }

    private def indexFor(hash: Int): Int = HashTrieTMap.indexFor(hash, shift)

    def get(hash: Int, key: A): Option[B] = {
      val c = children(indexFor(hash))
      if (c == null) None else c.get(hash, key)
    }

    def put(hash: Int, key: A, value: B): Option[B] = childForPut(hash, indexFor(hash)).put(hash, key, value)

    def shouldSplit(hash: Int): Boolean = false

    def withSplit(newGen: Int, shift: Int): Node[A, B] = throw new Error("withSplit without shouldSplit")

    def remove(hash: Int, key: A): Option[B] = {
      val c = childForRemove(hash, indexFor(hash))
      if (c == null) None else c.remove(hash, key)
    }

    private def childForPut(hash: Int, i: Int): Node[A, B] = {
      val c = children(i)
      if (c != null && c.gen == gen && !c.shouldSplit(hash)) c else fixChild(hash, i)
    }

    private def childForRemove(hash: Int, i: Int): Node[A, B] = {
      val c = children(i)
      if (c == null || c.gen == gen) c else fixChild(hash, i)
    }

    private def fixChild(hash: Int, i: Int): Node[A, B] = {
      children.refViews(i).transformAndGet { c =>
	if (c == null) {
	  // new empty leaf in this slot
	  new Leaf[A, B](gen)
	} else if (c.shouldSplit(hash)) {
	  // split also performs lazyClone, if necessary
	  c.withSplit(gen, shift + LogBF)
	} else if (c.gen != gen) {
	  c.clone(gen)
	} else {
	  // someone else did any required changes for us
	  c
	}
      }
    }

    def iterator: Iterator[(A, B)] = {
      var result: Iterator[(A, B)] = Iterator.empty
      var i = 0
      while (i < BF) {
	val n = children(i)
	if (n != null)
	  result ++= n.iterator
	i += 1
      }
      result
    }

    def draw(buf: StringBuilder, prefix: String) {
      buf ++= "Branch: gen=" + gen + ", shift=" + shift + ",\n"
      for (i <- 0 until children.length ; val c = children(i) if c != null) {
	buf ++= prefix + "" + i + " -> "
	c.draw(buf, prefix + "    ")
      }
    }
  }

  class Leaf[A, B](gen0: Int, c0: Contents[A, B]) extends Node[A, B](gen0) {
    private val contents = Ref(c0).single
  
    def this(gen0: Int) = this(gen0, emptyContents.asInstanceOf[Contents[A, B]])

    def clone(newGen: Int): Node[A, B] = {
      if (gen == newGen) this else new Leaf[A, B](newGen, contents())
    }
			     
    def get(hash: Int, key: A): Option[B] = contents().get(hash, key)

    def put(hash: Int, key: A, value: B): Option[B] = {
      (contents.getAndTransform { _.withPut(hash, key, value) }).get(hash, key)
    }

    def shouldSplit(hash: Int): Boolean = contents().shouldSplit(hash)

    def withSplit(newGen: Int, shift: Int): Node[A, B] = new Branch[A, B](newGen, shift, TArray(contents().split(newGen, shift)).single)
			     
    def remove(hash: Int, key: A): Option[B] = {
      (contents.getAndTransform { _.withRemove(hash, key) }).get(hash, key)
    }

    def iterator: Iterator[(A, B)] = contents().iterator

    def draw(buf: StringBuilder, prefix: String) {
      buf ++= "Leaf: gen=" + gen + "\n" + prefix
      contents().draw(buf, prefix + "    ")
    }
  }

  val emptyContents = new Contents[Any, Any](new Array[Int](0), new Array[AnyRef](0))

  class Contents[A, B](private val hashes: Array[Int], private val kvs: Array[AnyRef]) {
    def get(hash: Int, key: A): Option[B] = {
      val i = find(hash, key)
      if (i >= 0) Some(kvs(2 * i + 1).asInstanceOf[B]) else None
    }

    private def find(hash: Int, key: A): Int = {
      // There is only 64 bytes of hashes (unless the hash function is bad), so
      // a linear scan is faster than a binary search plus the logic to handle
      // duplicates.  We do take advantage of the sortedness, though.
      var i = 0
      while (i < hashes.length && hashes(i) < hash)
        i += 1
      while (i < hashes.length && hashes(i) == hash) {
        if (key == kvs(2 * i))
          return i
        i += 1
      }
      return -(i + 1)
    }

    def shouldSplit(hash: Int): Boolean = {
      // The only time we can get over the max capacity is if
      // splitting won't produce any benefit after inserting hash.
      // That can only happen if all of the hash values (including the
      // new one) are the same.
      hashes.length >= MaxLeafCapacity && !(hash == hashes(0) && hash == hashes(hashes.length))
    }

    def withPut(hash: Int, key: A, value: B): Contents[A, B] = {
      val i = find(hash, key)
      if (i >= 0) {
        // update, reuse hashes array
        val nkvs = kvs.clone
        nkvs(2 * i + 1) = value.asInstanceOf[AnyRef]
        new Contents[A, B](hashes, nkvs)
      } else {
        // insert
	val ii = -(i + 1)
        val nhashes = new Array[Int](hashes.length + 1)
        System.arraycopy(hashes, 0, nhashes, 0, ii)
        nhashes(ii) = hash
        System.arraycopy(hashes, ii, nhashes, ii + 1, hashes.length - ii)

        val nkvs = new Array[AnyRef](kvs.length + 2)
        System.arraycopy(kvs, 0, nkvs, 0, 2 * ii)
        nkvs(2 * ii) = key.asInstanceOf[AnyRef]
        nkvs(2 * ii + 1) = value.asInstanceOf[AnyRef]
        System.arraycopy(kvs, 2 * ii, nkvs, 2 * ii + 2, kvs.length - 2 * ii)

	new Contents[A, B](nhashes, nkvs)
      }
    }

    def withRemove(hash: Int, key: A): Contents[A, B] = {
      val i = find(hash, key)
      if (i < 0) {
	// not found
	this
      } else {
        // delete
	val ii = -(i + 1)
        val nhashes = new Array[Int](hashes.length - 1)
        System.arraycopy(hashes, 0, nhashes, 0, ii)
        System.arraycopy(hashes, ii + 1, nhashes, ii, nhashes.length - ii)

        val nkvs = new Array[AnyRef](kvs.length - 2)
        System.arraycopy(kvs, 0, nkvs, 0, 2 * ii)
        System.arraycopy(kvs, 2 * ii + 2, nkvs, 2 * ii, nkvs.length - 2 * ii)

	new Contents[A, B](nhashes, nkvs)
      }
    }

    def split(newGen: Int, shift: Int): Array[Node[A, B]] = {
      // compute bucket sizes
      val sizes = new Array[Int](BF)
      var i = 0
      while (i < hashes.length) {
	sizes(indexFor(hashes(i), shift)) += 1
	i += 1
      }

      // allocate buckets
      val cc = new Array[Contents[A, B]](BF)
      val result = new Array[Node[A, B]](BF)
      i = 0
      while (i < BF) {
	val n = sizes(i)
	if (n > 0) {
	  cc(i) = new Contents[A, B](new Array[Int](n), new Array[AnyRef](2 * n))
	  result(i) = new Leaf[A, B](newGen, cc(i))
	}
	i += 1
      }

      // do the split
      i = hashes.length - 1
      while (i >= 0) {
	val h = hashes(i)
	val j = indexFor(h, shift)
	val k = sizes(j) - 1
	sizes(j) = k
	cc(j).hashes(k) = h
	cc(j).kvs(2 * k) = kvs(2 * i)
	cc(j).kvs(2 * k + 1) = kvs(2 * i + 1)
	i -= 1
      }
      result
    }

    def iterator: Iterator[(A, B)] = new Iterator[(A, B)] {
      var cp = 0
      def hasNext: Boolean = 2 * cp + 1 < kvs.length
      def next: (A, B) = { cp += 1 ; (kvs(2 * cp - 2).asInstanceOf[A] -> kvs(2 * cp - 1).asInstanceOf[B]) }
    }

    def draw(buf: StringBuilder, prefix: String) {
      buf ++= "Contents: size=" + hashes.length
      for (i <- 0 until hashes.length)
	buf ++= prefix + "  " + ": hash=0x" + hashes(i).toHexString + ", key=" + kvs(2 * i) + ", value=" + kvs(2 * i + 1) + "\n"
    }
  }
}

class HashTrieTMap[A, B] private (gen0: Int, root0: HashTrieTMap.Node[A, B]) extends TMapViaClone[A, B] {
  import HashTrieTMap._

  def this() = this(0, new HashTrieTMap.Leaf[A, B](0))
  def this(root0: HashTrieTMap.Node[A, B]) = this(root0.gen + 1, root0)

  private val rootHolder: Ref.View[(Int, Node[A, B])] = Ref(gen0 -> root0).single

  private def rootForShare: Node[A, B] = (rootHolder.getAndTransform { gr => (gr._2.gen + 1) -> gr._2 })._2

  private def rootForRead = rootHolder()._2

  private def rootForWrite: Node[A, B] = {
    rootHolder() match {
      case (g, r) if g == r.gen => r
      case _ => (rootHolder.transformAndGet { case (g, r) => (g -> r.clone(g)) })._2
    }
  }

  override def empty: TMap.View[A, B] = new HashTrieTMap[A, B]()

  override def clone(): HashTrieTMap[A, B] = new HashTrieTMap(rootForShare)

  override def iterator: Iterator[(A, B)] = rootForShare.iterator

  def get(key: A): Option[B] = rootForRead.get(computeHash(key), key)

  override def put(key: A, value: B): Option[B] = rootForWrite.put(computeHash(key), key, value)

  override def update(key: A, value: B) { put(key, value) }

  override def += (kv: (A, B)): this.type = { update(kv._1, kv._2) ; this }

  override def remove(key: A): Option[B] = rootForWrite.remove(computeHash(key), key)

  override def -= (key: A): this.type = { remove(key) ; this }

  override def clear() { rootHolder() = (0 -> new Leaf[A, B](0)) }

  override def getOrElseUpdate(key: A, op: => B): B = {
    val rr = rootForRead
    val hash = computeHash(key)
    rr.get(hash, key) getOrElse {
      atomic { implicit txn =>
	rr.get(hash, key) getOrElse {
	  val v = op
	  rootForWrite.put(hash, key, v)
	  v
	}
      }
    }
  }

  // TODO
  //override def transform(f: (A, B) => B): this.type = { atomic { _ => super.transform(f) } ; this }

  // TODO
  //override def retain(p: (A, B) => Boolean): this.type = { atomic { _ => super.retain(p) } ; this }

  def draw: String = {
    val (g, r) = rootHolder()
    val buf = new StringBuilder
    buf ++= "HashTrieTMap: gen=" + g + "\n  "
    r.draw(buf, "  ")
    buf.toString
  }
}
