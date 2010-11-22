package scala.concurrent.stm.skel

import annotation.tailrec

object ConcurrentHashTrieMap {

  def LogBF = 5
  def BF = 32
  def MaxLeafCapacity = 8

  def keyHash[A](key: A): Int = if (key == null) 0 else rehash(key.##)

  private def rehash(h: Int) = {
    // make sure any bit change results in a change in the bottom LogBF bits
    val x = h ^ (h >>> 15)
    x ^ (x >>> 5) ^ (x >>> 10)
  }

  def indexFor(shift: Int, hash: Int) = (hash >> shift) & (BF - 1)

  object Leaf {
    private val emptyLeaf = new Leaf[Any, Any](Array[Int](), Array[AnyRef]())

    def empty[A, B] = emptyLeaf.asInstanceOf[Leaf[A, B]]

    def apply[A, B](hash: Int, key: A, value: B): Leaf[A, B] = {
      new Leaf[A, B](Array(hash), Array(key.asInstanceOf[AnyRef], value.asInstanceOf[AnyRef]))
    }

    def apply[A, B](leaves: Array[Leaf[A, B]]): Leaf[A, B] = {
      val ss = (for (leaf <- leaves; if leaf != null; i <- 0 until leaf.hashes.length) yield {
        val h = leaf.hashes(i)
        val k = leaf.keys(i).asInstanceOf[AnyRef]
        val v = leaf.values(i).asInstanceOf[AnyRef]
        (h -> Array(k, v))
      }).sortBy { _._1 }
      if (ss.isEmpty) empty[A, B] else new Leaf[A, B](ss map { _._1 }, ss flatMap { _._2 })
    }    
  }

  // this is immutable
  class Leaf[A, B](val hashes: Array[Int], private val kvs: Array[AnyRef]) {
    def keys(i: Int): A = kvs(2 * i).asInstanceOf[A]
    def values(i: Int): B = kvs(2 * i + 1).asInstanceOf[B]

    def get(hash: Int, key: A): Option[B] = {
      val i = find(hash, key)
      if (i >= 0) Some(values(i)) else None       
    }

    private def find(hash: Int, key: A): Int = {
      var i = 0
      while (i < hashes.length && hashes(i) < hash)
        i += 1
      while (i < hashes.length && hashes(i) == hash) {
        if (key == keys(i))
          return i
      }
      return -(i + 1)
    }

    def withPut(hash: Int, key: A, value: B): Leaf[A, B] = {
      val i = find(hash, key)
      if (i >= 0) withUpdate(i, value) else withInsert(-(i + 1), hash, key, value)
    }

    private def withUpdate(i: Int, value: B): Leaf[A, B] = {
      // reuse hashes
      val nkvs = kvs.clone
      nkvs(2 * i + 1) = value.asInstanceOf[AnyRef]
      new Leaf[A, B](hashes, nkvs)
    }

    private def withInsert(i: Int, hash: Int, key: A, value: B): Leaf[A, B] = {
      val nhashes = new Array[Int](hashes.length + 1)
      System.arraycopy(hashes, 0, nhashes, 0, i)
      System.arraycopy(hashes, i, nhashes, i + 1, hashes.length - i)
      nhashes(i) = hash

      val nkvs = new Array[AnyRef](kvs.length + 2)
      System.arraycopy(kvs, 0, nkvs, 0, 2 * i)
      System.arraycopy(kvs, 2 * i, nkvs, 2 * i + 2, kvs.length - 2 * i)
      nkvs(2 * i) = key.asInstanceOf[AnyRef]
      nkvs(2 * i + 1) = value.asInstanceOf[AnyRef]

      new Leaf[A, B](nhashes, nkvs)
    }

    def withRemove(hash: Int, key: A): Leaf[A, B] = {
      val i = find(hash, key)
      if (i >= 0) withRemove(i) else this
    }

    private def withRemove(i: Int): Leaf[A, B] = {
      val nhashes = new Array[Int](hashes.length - 1)
      System.arraycopy(hashes, 0, nhashes, 0, i)
      System.arraycopy(hashes, i + 1, nhashes, i, nhashes.length - i)

      val nkvs = new Array[AnyRef](kvs.length - 2)
      System.arraycopy(kvs, 0, nkvs, 0, 2 * i)
      System.arraycopy(kvs, 2 * i + 2, nkvs, 2 * i, nkvs.length - 2 * i)

      new Leaf[A, B](nhashes, nkvs)
    }

    def shouldSplit: Boolean = {
      // if the hash function is bad we might be oversize but unsplittable
      hashes.length > MaxLeafCapacity && hashes(hashes.length - 1) != hashes(0)
    }

    def split(shift: Int): Array[MVar[Either[Leaf[A, B], Branch[A, B]]]] = {
      val sizes = new Array[Int](BF)
      var i = 0
      while (i < hashes.length) {
        sizes(indexFor(shift, hashes(i))) += 1
        i += 1
      }
      val result = new Array[MVar[Either[Leaf[A, B], Branch[A, B]]]](BF)
      i = 0
      while (i < BF) {
        val n = sizes(i)
        val t = if (n == 0) Leaf.empty[A, B] else new Leaf[A, B](new Array[Int](n), new Array[AnyRef](2 * n))
        result(i) = new MVar[Either[Leaf[A, B], Branch[A, B]]](Left(t))
        i += 1
      }
      i = hashes.length - 1
      while (i >= 0) {
        val slot = indexFor(shift, hashes(i))
        sizes(slot) -= 1
        val pos = sizes(slot)
        val dst = result(slot).value.left.get
        dst.hashes(pos) = hashes(i)
        dst.kvs(2 * pos) = kvs(2 * i)
        dst.kvs(2 * pos + 1) = kvs(2 * i + 1)
        i -= 1
      }
      result
    }
  }

  class MVar[A <: AnyRef](@volatile var value: A) {
    def casi(v0: A, v1: A): Boolean = synchronized { (value eq v0) && { value = v1; true } }
    def casi(v0: A, extra: => Boolean, v1: A): Boolean = synchronized { (value eq v0) && extra && { value = v1 ; true } }
  }

  // The rule is that every MVar either directly holds a terminal (Leaf), or it
  // holds a forwarder that is valid forever.  This means that each MVar only
  // needs to be read once when performing a map read. 

  class Branch[A, B](val gen: Int, val children: Array[MVar[Either[Leaf[A, B], Branch[A, B]]]]) {
    def clone(newGen: Int): Branch[A, B] = new Branch[A, B](newGen, children map { m => new MVar(m.value) })
  }



//  class Branch[A, B] {
//    val
//  }
}

import ConcurrentHashTrieMap._

class ConcurrentHashTrieMap[A, B] private (private val root: MVar[(Int, MVar[Either[Leaf[A, B], Branch[A, B]]])]) {

  def this() = this(new MVar((0, new MVar[Either[Leaf[A, B], Branch[A, B]]](Left(Leaf.empty[A, B])))))

  def get(key: A): Option[B] = get(root.value._2, 0, keyHash(key), key)

  @tailrec private def get(n: MVar[Either[Leaf[A, B], Branch[A, B]]], shift: Int, hash: Int, key: A): Option[B] = {
    n.value match {
      case Left(leaf) => leaf.get(hash, key)
      case Right(branch) => get(branch.children(indexFor(shift, hash)), shift + LogBF, hash, key)
    }
  }

  def put(key: A, value: B): Option[B] = {
    val r = root.value
    put(r._1, r._2, 0, keyHash(key), key, value)
  }

  @tailrec private def put(gen: Int, n: MVar[Either[Leaf[A, B], Branch[A, B]]], shift: Int, hash: Int, key: A, value: B): Option[B] = {
    n.value match {
      case v0 @ Left(leaf) => {
        var after: Either[Leaf[A, B], Branch[A, B]] = Left(leaf.withPut(hash, key, value))
        if (after.left.get.shouldSplit)
          after = Right(new Branch[A, B](gen, after.left.get.split(shift)))
        if (n.casi(v0, { root.value._1 == gen }, after))
          leaf.get(hash, key)
        else if (root.value._1 == gen)
          put(gen, n, shift, hash, key, value) // retry locally
        else {
          val r = root.value
          put(r._1, r._2, 0, hash, key, value) // retry completely
        }
      }
      case v0 @ Right(branch) => {
        if (branch.gen == gen)
          put(gen, branch.children(indexFor(shift, hash)), shift + LogBF, hash, key, value)
        else {
          n.casi(v0, Right(branch.clone(gen)))
          // try again, either picking up our improvement or someone else's
          put(gen, n, shift, hash, key, value)
        }
      }
    }
  }

  override def clone(): ConcurrentHashTrieMap[A, B] = {
    // we need to bump the gen of this map's root,
    val v0 = root.value
    root.casi(v0, (v0._1 + 1, new MVar(v0._2.value)))
    new ConcurrentHashTrieMap(new MVar((v0._1 + 1, new MVar(v0._2.value))))
  }
}
