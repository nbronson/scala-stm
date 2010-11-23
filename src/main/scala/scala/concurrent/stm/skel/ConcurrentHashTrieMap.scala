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

  sealed abstract class Node[A, B]

  object Leaf {
    private val emptySetValue = new Leaf[Any, Unit](Array.empty[Int], Array.empty[AnyRef], null)
    private val emptyMapValue = new Leaf[Any, Unit](Array.empty[Int], Array.empty[AnyRef], Array.empty[AnyRef])

    def emptySet[A, B]: Leaf[A, B] = emptySetValue.asInstanceOf[Leaf[A, B]]
    def emptyMap[A, B]: Leaf[A, B] = emptyMapValue.asInstanceOf[Leaf[A, B]]
  }

  /** If used by a Set, values will be null. */
  class Leaf[A, B](val hashes: Array[Int],
                   val keys: Array[AnyRef],
                   val values: Array[AnyRef]) extends Node[A, B] {

    def contains(hash: Int, key: A): Boolean = find(hash, key) >= 0

    def get(hash: Int, key: A): Option[B] = {
      val i = find(hash, key)
      if (i >= 0) Some(values(i).asInstanceOf[B]) else None
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

    def withAdd(hash: Int, key: A): Leaf[A, B] = {
      val i = find(hash, key)
      if (i >= 0) this else withInsert(-(i + 1), hash, key, null.asInstanceOf[B])
    }

    def withPut(hash: Int, key: A, value: B): Leaf[A, B] = {
      val i = find(hash, key)
      if (i >= 0) withUpdate(i, value) else withInsert(-(i + 1), hash, key, value)
    }

    private def withUpdate(i: Int, value: B): Leaf[A, B] = {
      // reuse hashes and keys
      val nvalues = values.clone
      nvalues(i) = value.asInstanceOf[AnyRef]
      new Leaf[A, B](hashes, keys, nvalues)
    }

    private def withInsert(i: Int, hash: Int, key: A, value: B): Leaf[A, B] = {
      val nhashes = arrayInsert(hashes, i, hash)
      val nkeys = arrayInsert(keys, i, key.asInstanceOf[AnyRef])
      val nvalues = if (values == null) null else arrayInsert(values, i, value.asInstanceOf[AnyRef])
      new Leaf[A, B](nhashes, nkeys, nvalues)
    }

    private def arrayInsert[@specialized A : ClassManifest](src: Array[A], i: Int, v: A): Array[A] = {
      val dst = new Array[A](src.length + 1)
      System.arraycopy(src, 0, dst, 0, i)
      System.arraycopy(src, i, dst, i + 1, src.length - i)
      dst(i) = v
      dst
    }

    def withRemove(hash: Int, key: A): Leaf[A, B] = {
      val i = find(hash, key)
      if (i >= 0) withRemove(i) else this
    }

    private def withRemove(i: Int): Leaf[A, B] = {
      if (hashes.length == 1) {
        null
      } else {
        val nhashes = arrayInsert(hashes, i)
        val nkeys = arrayInsert(keys, i)
        val nvalues = if (values == null) null else arrayInsert(values, i)
        new Leaf[A, B](nhashes, nkeys, nvalues)
      }
    }

    private def arrayInsert[@specialized A : ClassManifest](src: Array[A], i: Int): Array[A] = {
      val dst = new Array[A](src.length - 1)
      System.arraycopy(src, 0, dst, 0, i)
      System.arraycopy(src, i + 1, dst, i, dst.length - i)
      dst
    }

    def shouldSplit: Boolean = {
      // if the hash function is bad we might be oversize but unsplittable
      hashes.length > MaxLeafCapacity && hashes(hashes.length - 1) != hashes(0)
    }

    def split(gen: Long, shift: Int): Branch[A, B] = {
      val sizes = new Array[Int](BF)
      var i = 0
      while (i < hashes.length) {
        sizes(indexFor(shift, hashes(i))) += 1
        i += 1
      }
      val children = new Array[MVar[Node[A, B]]](BF)
      i = 0
      while (i < BF) {
        children(i) = new MVar[Node[A, B]](newLeaf(sizes(i)))
        i += 1
      }
      i = hashes.length - 1
      while (i >= 0) {
        val slot = indexFor(shift, hashes(i))
        sizes(slot) -= 1
        val pos = sizes(slot)
        val dst = children(slot).value.asInstanceOf[Leaf[A, B]]
        dst.hashes(pos) = hashes(i)
        dst.keys(pos) = keys(i)
        if (values != null)
          dst.values(pos) = values(i)
        if (pos == 0 && dst.shouldSplit)
          children(slot).value = dst.split(gen, shift + LogBF)
        i -= 1
      }
      new Branch[A, B](gen, children)
    }

    private def newLeaf(n: Int): Leaf[A, B] = {
      if (n == 0) {
        if (values == null) Leaf.emptySet[A, B] else Leaf.emptyMap[A, B]
      } else {
        val nvalues = if (values == null) null else new Array[AnyRef](n)
        new Leaf[A, B](new Array[Int](n), new Array[AnyRef](n), nvalues)
      }
    }
  }

  class MVar[A <: AnyRef](@volatile var value: A) {
    def casi(v0: A, v1: A): Boolean = synchronized { (value eq v0) && { value = v1; true } }
    def casi(v0: A, extra: => Boolean, v1: A): Boolean = synchronized { (value eq v0) && extra && { value = v1 ; true } }
  }

  // The rule is that every MVar either directly holds a terminal (Leaf), or it
  // holds a forwarder that is valid forever.  This means that each MVar only
  // needs to be read once when performing a map read. 

  class Branch[A, B](val gen: Long, val children: Array[MVar[Node[A, B]]]) extends Node[A, B] {
    def clone(newGen: Long): Branch[A, B] = new Branch[A, B](newGen, children map { m => new MVar(m.value) })
  }

  
//  class Branch[A, B] {
//    val
//  }
}

import ConcurrentHashTrieMap._

class ConcurrentHashTrieMap[A, B] private (private val root: MVar[(Long, MVar[Node[A, B]])]) {

  def this() = this(new MVar((0L, new MVar[Node[A, B]](Leaf.emptyMap[A, B]))))

  override def clone(): ConcurrentHashTrieMap[A, B] = {
    // if we fail to bump the gen, someone else must have succeeded
    val v0 = root.value
    root.casi(v0, (v0._1 + 1, new MVar(v0._2.value)))
    new ConcurrentHashTrieMap(new MVar((v0._1 + 1, new MVar(v0._2.value))))
  }

  def contains(key: A): Boolean = contains(root.value._2, 0, keyHash(key), key)

  @tailrec private def contains(n: MVar[Node[A, B]], shift: Int, hash: Int, key: A): Boolean = {
    n.value match {
      case leaf: Leaf[A, B] => leaf.contains(hash, key)
      case branch: Branch[A, B] => contains(branch.children(indexFor(shift, hash)), shift + LogBF, hash, key)
    }
  }

  def get(key: A): Option[B] = get(root.value._2, 0, keyHash(key), key)

  @tailrec private def get(n: MVar[Node[A, B]], shift: Int, hash: Int, key: A): Option[B] = {
    n.value match {
      case null => None
      case leaf: Leaf[A, B] => leaf.get(hash, key)
      case branch: Branch[A, B] => get(branch.children(indexFor(shift, hash)), shift + LogBF, hash, key)
    }
  }

  def put(key: A, value: B): Option[B] = {
    val r = root.value
    put(r._1, r._2, 0, keyHash(key), key, value)
  }

  @tailrec private def put(gen: Long, n: MVar[Node[A, B]], shift: Int, hash: Int, key: A, value: B): Option[B] = {
    n.value match {
      case leaf: Leaf[A, B] => {
        val p = leaf.withPut(hash, key, value)
        val after = if (!p.shouldSplit) p else p.split(gen, shift)
        if (n.casi(leaf, { root.value._1 == gen }, after))
          leaf.get(hash, key)          
        else if (root.value._1 == gen)
          put(gen, n, shift, hash, key, value) // retry locally
        else {
          val r = root.value
          put(r._1, r._2, 0, hash, key, value) // retry completely
        }
      }
      case branch: Branch[A, B] => {
        if (branch.gen == gen)
          put(gen, branch.children(indexFor(shift, hash)), shift + LogBF, hash, key, value)
        else {
          n.casi(branch, branch.clone(gen))
          // try again, either picking up our improvement or someone else's
          put(gen, n, shift, hash, key, value)
        }
      }
    }
  }

  def remove(key: A): Option[B] = {
    val r = root.value
    remove(r._1, r._2, 0, keyHash(key), key, false)
  }

  @tailrec private def remove(gen: Long, n: MVar[Node[A, B]], shift: Int, hash: Int, key: A, checked: Boolean): Option[B] = {
    n.value match {
      case leaf: Leaf[A, B] => {
        val after = leaf.withRemove(hash, key)
        if (after eq leaf)
          None // no change, key must not have been present
        else if (n.casi(leaf, { root.value._1 == gen }, after))
          leaf.get(hash, key)
        else if (root.value._1 == gen)
          remove(gen, n, shift, hash, key, true) // retry locally
        else {
          val r = root.value
          remove(r._1, r._2, 0, hash, key, true) // retry completely
        }
      }
      case branch: Branch[A, B] => {
        if (branch.gen == gen)
          remove(gen, branch.children(indexFor(shift, hash)), shift + LogBF, hash, key, checked)
        else {
          // no use in cloning paths if the key isn't actually present
          if (!checked && !contains(branch.children(indexFor(shift, hash)), shift + LogBF, hash, key))
            None
          else {
            n.casi(branch, branch.clone(gen))
            // try again, either picking up our improvement or someone else's
            remove(gen, n, shift, hash, key, true)
          }
        }
      }
    }
  }

}
