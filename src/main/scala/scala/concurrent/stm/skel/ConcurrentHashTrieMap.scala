package scala.concurrent.stm
package skel

import annotation.tailrec

object ConcurrentHashTrieMap {
  def main(args: Array[String]) {
    for (q <- 0 until 8) {
      val m = new ConcurrentHashTrieMap[Int, String]
      //val m = new SimpleTMap(Map.empty[Int, String])
      for (i <- 0 until 1000)
        m.put(i, "foo")
      val t0 = System.currentTimeMillis
      for (p <- 0 until 1000) {
        var i = 0
        while (i < 10000) {
          m.get(-(i % 1000))
          i += 1
        }
      }
      println((System.currentTimeMillis - t0) / 10)
    }    
  }


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
        i += 1
      }
      return -(i + 1)
    }

    def withAdd(hash: Int, key: A): Leaf[A, B] = {
      val i = find(hash, key)
      if (i >= 0)
        this
      else
        withInsert(-(i + 1), hash, key, null.asInstanceOf[B])
    }

    def withPut(hash: Int, key: A, value: B): Leaf[A, B] = {
      val i = find(hash, key)
      if (i >= 0)
        withUpdate(i, value)
      else
        withInsert(-(i + 1), hash, key, value)
    }

    private def withUpdate(i: Int, value: B): Leaf[A, B] = {
      // reuse hashes and keys
      val nvalues = values.clone
      nvalues(i) = value.asInstanceOf[AnyRef]
      new Leaf[A, B](hashes, keys, nvalues)
    }

    private def withInsert(i: Int, hash: Int, key: A, value: B): Leaf[A, B] = {
      val z = newLeaf(hashes.length + 1)
      val j = hashes.length - i
      
      System.arraycopy(hashes, 0, z.hashes, 0, i)
      System.arraycopy(hashes, i, z.hashes, i + 1, j)
      z.hashes(i) = hash

      System.arraycopy(keys, 0, z.keys, 0, i)
      System.arraycopy(keys, i, z.keys, i + 1, j)
      z.keys(i) = key.asInstanceOf[AnyRef]

      if (values != null) {
        System.arraycopy(values, 0, z.values, 0, i)
        System.arraycopy(values, i, z.values, i + 1, j)
        z.values(i) = value.asInstanceOf[AnyRef]
      }

      z
    }

    def withRemove(hash: Int, key: A): Leaf[A, B] = {
      val i = find(hash, key)
      if (i < 0) this else withRemove(i)
    }

    private def withRemove(i: Int): Leaf[A, B] = {
      val z = newLeaf(hashes.length - 1)
      if (z.hashes.length > 0) {
        val j = z.hashes.length - i

        System.arraycopy(hashes, 0, z.hashes, 0, i)
        System.arraycopy(hashes, i + 1, z.hashes, i, j)

        System.arraycopy(keys, 0, z.keys, 0, i)
        System.arraycopy(keys, i + 1, z.keys, i, j)

        if (values != null) {
          System.arraycopy(values, 0, z.values, 0, i)
          System.arraycopy(values, i + 1, z.values, i, j)
        }
      }
      z
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
      val children = new Array[Ref.View[Node[A, B]]](BF)
      i = 0
      while (i < BF) {
        children(i) = Ref[Node[A, B]](newLeaf(sizes(i))).single
        i += 1
      }
      i = hashes.length - 1
      while (i >= 0) {
        val slot = indexFor(shift, hashes(i))
        sizes(slot) -= 1
        val pos = sizes(slot)
        val dst = children(slot)().asInstanceOf[Leaf[A, B]]
        dst.hashes(pos) = hashes(i)
        dst.keys(pos) = keys(i)
        if (values != null)
          dst.values(pos) = values(i)

        // If the hashes were very poorly distributed one leaf might get
        // everything.  We could resplit now, but it doesn't seem to be worth
        // it.  If we wait until the next insert we can never get more than
        // 32 / LogBF extra.
        //// if (pos == 0 && dst.shouldSplit)
        ////  children(slot).value = dst.split(gen, shift + LogBF)
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

  class Branch[A, B](val gen: Long, val children: Array[Ref.View[Node[A, B]]]) extends Node[A, B] {
    def clone(newGen: Long): Branch[A, B] = {
      val cc = children.clone
      var i = 0
      while (i < cc.length) {
        cc(i) = Ref(cc(i)()).single
        i += 1
      }
      new Branch[A, B](newGen, cc)
    }
  }
}

import ConcurrentHashTrieMap._

class ConcurrentHashTrieMap[A, B] private (root0: Node[A, B]) {

  def this() = this(Leaf.emptyMap[A, B])

  private val root = Ref(root0).single

  def contains(key: A): Boolean = contains(root, 0, keyHash(key), key)

  @tailrec private def contains(n: Ref.View[Node[A, B]], shift: Int, hash: Int, key: A): Boolean = {
    n() match {
      case leaf: Leaf[A, B] => leaf.contains(hash, key)
      case branch: Branch[A, B] => contains(branch.children(indexFor(shift, hash)), shift + LogBF, hash, key)
    }
  }

  def get(key: A): Option[B] = get(root, 0, keyHash(key), key)

  @tailrec private def get(n: Ref.View[Node[A, B]], shift: Int, hash: Int, key: A): Option[B] = {
    n() match {
      case leaf: Leaf[A, B] => leaf.get(hash, key)
      case branch: Branch[A, B] => get(branch.children(indexFor(shift, hash)), shift + LogBF, hash, key)
    }
  }

  def put(key: A, value: B): Option[B] = {
    put(-1L, root, 0, keyHash(key), key, value)
  }

  @tailrec private def put(gen: Long, n: Ref.View[Node[A, B]], shift: Int, hash: Int, key: A, value: B): Option[B] = {
    n() match {
      case leaf: Leaf[A, B] => {
        val p = leaf.withPut(hash, key, value)
        val after = if (!p.shouldSplit) p else p.split(gen, shift)
        atomic { implicit txn =>
          if (n.ref() ne leaf)
            0 // local retry
          else if (gen != -1L && root.ref().asInstanceOf[Branch[A, B]].gen != gen)
            1 // root retry
          else {
            n.ref() = after
            2 // success
          }
        } match {
          case 0 => put(gen, n, shift, hash, key, value)
          case 1 => put(-1L, root, 0, hash, key, value)
          case 2 => leaf.get(hash, key)
        }
      }
      case branch: Branch[A, B] => {
        if (gen == -1L || branch.gen == gen)
          put(branch.gen, branch.children(indexFor(shift, hash)), shift + LogBF, hash, key, value)
        else {
          n.compareAndSetIdentity(branch, branch.clone(gen))
          // try again, either picking up our improvement or someone else's
          put(gen, n, shift, hash, key, value)
        }
      }
    }
  }
//
//  def remove(key: A): Option[B] = {
//    val r = root.value
//    remove(r._1, r._2, 0, 0, keyHash(key), key, false)
//  }
//
//  @tailrec private def remove(gen: Long, a: Array[Node[A, B]], i: Int, shift: Int, hash: Int, key: A, checked: Boolean): Option[B] = {
//    a(i) match {
//      case leaf: Leaf[A, B] => {
//        val after = leaf.withRemove(hash, key)
//        if (after eq leaf)
//          None // no change, key must not have been present
//        else if (n.casi(leaf, { root.value._1 == gen }, after))
//          leaf.get(hash, key)
//        else if (root.value._1 == gen)
//          remove(gen, a, i, shift, hash, key, true) // retry locally
//        else {
//          val r = root.value
//          remove(r._1, r._2, 0, 0, hash, key, true) // retry completely
//        }
//      }
//      case branch: Branch[A, B] => {
//        if (branch.gen == gen)
//          remove(gen, branch.children, indexFor(shift, hash), shift + LogBF, hash, key, checked)
//        else {
//          // no use in cloning paths if the key isn't actually present
//          if (!checked && !contains(branch.children, indexFor(shift, hash), shift + LogBF, hash, key))
//            None
//          else {
//            n.casi(branch, branch.clone(gen))
//            // try again, either picking up our improvement or someone else's
//            remove(gen, a, i, shift, hash, key, true)
//          }
//        }
//      }
//    }
//  }

}
