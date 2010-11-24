/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm.skel

import annotation.tailrec

object ImmutableHashTree {

  // TODO: why 14?
  def MaxLeafSize = 14

  val emptyLeaf = new Leaf[AnyRef, AnyRef](0)

  def empty[A, B] = emptyLeaf.asInstanceOf[Node[A, B]]

  sealed abstract class Node[A, B] {
    def height: Int
    def withInsert(h: Int, k: A, v: B): Node[A, B]
    def withUpdate(h: Int, k: A, v: B): Node[A, B]
    def withRemove(h: Int, k: A): Node[A, B]
  }

  class Leaf[A, B](val hashes: Array[Int], val kvs: Array[AnyRef]) extends Node[A, B] {
    
    def this(n: Int) = this(new Array[Int](n), new Array[AnyRef](2 * n))

    def height = 1
    
    def size = hashes.length
    def keys(i: Int) = kvs(2 * i).asInstanceOf[A]
    def values(i: Int) = kvs(2 * i + 1).asInstanceOf[B]

    def contains(hash: Int, key: A): Boolean = find(hash, key) >= 0

    def get(hash: Int, key: A): Option[B] = {
      val i = find(hash, key)
      if (i < 0)
        None
      else
        Some(values(i))
    }

    private def find(hash: Int, key: A): Int = {
      val hh = hashes
      var i = hh.length - 1
      while (i >= 0 && hh(i) >= hash) {
        if (hh(i) == hash && key == keys(i))
          return i
        i -= 1
      }
      return ~(i + 1)

//      var i = 0
//      val hh = hashes
//      while (i < hh.length && hh(i) <= hash) {
//        if (hh(i) == hash && key == keys(i))
//          return i
//        i += 1
//      }
//      return ~i
    }

    def withUpdate(hash: Int, key: A, value: B): Leaf[A, B] = {
      val i = find(hash, key)

      // reuse hashes
      val nkvs = kvs.clone
      nkvs(2 * i + 1) = value.asInstanceOf[AnyRef]
      new Leaf[A, B](hashes, nkvs)
    }

    def withInsert(hash: Int, key: A, value: B): Node[A, B] = {
      val i = ~find(hash, key)
      
      val z = new Leaf[A, B](size + 1)
      val j = size - i

      System.arraycopy(hashes, 0, z.hashes, 0, i)
      System.arraycopy(hashes, i, z.hashes, i + 1, j)
      z.hashes(i) = hash

      System.arraycopy(kvs, 0, z.kvs, 0, 2 * i)
      System.arraycopy(kvs, 2 * i, z.kvs, 2 * i + 2, 2 * j)
      z.kvs(2 * i) = key.asInstanceOf[AnyRef]
      z.kvs(2 * i + 1) = value.asInstanceOf[AnyRef]

      if (!z.shouldSplit) z else z.split
    }

    def withRemove(hash: Int, key: A): Leaf[A, B] = {
      val i = find(hash, key)
      if (i < 0) this else withRemove(i)
    }

    private def withRemove(i: Int): Leaf[A, B] = {
      if (size == 1)
        null
      else {
        val z = new Leaf[A, B](size - 1)
        if (z.size > 0) {
          val j = z.size - i

          System.arraycopy(hashes, 0, z.hashes, 0, i)
          System.arraycopy(hashes, i + 1, z.hashes, i, j)

          System.arraycopy(kvs, 0, z.kvs, 0, 2 * i)
          System.arraycopy(kvs, 2 * i + 2, z.kvs, 2 * i, 2 * j)
        }
        z
      }
    }

    def shouldSplit: Boolean = size > MaxLeafSize && hashes(0) != hashes(size - 1)

    def split: Branch[A, B] = {
      // the pivot value is the right side of a discontinuity
      val mid = size / 2
      var offset = 0
      while (!isPivot(mid + offset))
        offset = ~offset + (offset >>> 31)
      val i = mid + offset
      val j = size - i

      val lhs = new Leaf[A, B](i)
      System.arraycopy(hashes, 0, lhs.hashes, 0, i)
      System.arraycopy(kvs, 0, lhs.kvs, 0, 2 * i)

      val rhs = new Leaf[A, B](j)
      System.arraycopy(hashes, i, rhs.hashes, 0, j)
      System.arraycopy(kvs, 2 * i, rhs.kvs, 0, 2 * j)

      new Branch(1, rhs.hashes(0), lhs, rhs)
    }

    def isPivot(i: Int): Boolean = hashes(i - 1) != hashes(i)

    def visit(f: (Int, A, B) => Unit) {
      var i = 0
      while (i < size) {
        f(hashes(i), keys(i), values(i))
        i += 1
      }
    }

    def foreachKey[U](f: A => U) {
      var i = 0
      while (i < size) {
        f(keys(i).asInstanceOf[A])
        i += 1
      }
    }

    def foreachEntry[U](f: ((A, B)) => U) {
      var i = 0
      while (i < size) {
        f((keys(i), values(i)))
        i += 1
      }
    }
  }

  class Branch[A, B](val height: Int, val hash: Int, val left: Node[A, B], val right: Node[A, B]) extends Node[A, B] {
    def merge: Leaf[A, B] = {
      val lhs = left.asInstanceOf[Leaf[A, B]]
      val rhs = right.asInstanceOf[Leaf[A, B]]
      val i = lhs.size
      val j = rhs.size

      val z = new Leaf[A, B](i + j)

      System.arraycopy(lhs.hashes, 0, z.hashes, 0, i)
      System.arraycopy(lhs.kvs, 0, z.kvs, 0, 2 * i)

      System.arraycopy(rhs.hashes, i, z.hashes, 0, j)
      System.arraycopy(rhs.kvs, 2 * i, z.kvs, 0, 2 * j)

      z
    }


    def withInsert(h: Int, k: A, v: B): Node[A, B] = null

    def withRemove(h: Int, k: A): Node[A, B] = null

    def withUpdate(h: Int, k: A, v: B): Node[A, B] = null
  }


}

class ImmutableHashTree[A, B](val size: Int, val root: ImmutableHashTree.Node[A, B]) {
  import ImmutableHashTree._

  def this() = this(0, ImmutableHashTree.empty[A, B])

  def contains(hash: Int, key: A): Boolean = contains(root, hash, key)

  @tailrec private def contains(n: Node[A, B], hash: Int, key: A): Boolean = n match {
    case t: Leaf[A, B] => t.contains(hash, key)
    case b: Branch[A, B] => contains(if (hash < b.hash) b.left else b.right, hash, key)
  }

  def get(hash: Int, key: A): Option[B] = get(root, hash, key)
  
  @tailrec private def get(n: Node[A, B], hash: Int, key: A): Option[B] = n match {
    case t: Leaf[A, B] => t.get(hash, key)
    case b: Branch[A, B] => get(if (hash < b.hash) b.left else b.right, hash, key)
  }

  def withInsert(hash: Int, key: A, value: B): ImmutableHashTree[A, B] =
      new ImmutableHashTree(size + 1, root.withInsert(hash, key, value))

}
