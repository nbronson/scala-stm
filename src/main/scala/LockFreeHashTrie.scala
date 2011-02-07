import scala.annotation.tailrec
import LockFreeHashTrie._

class Ref[A](init: A) {
  private var value = init
  def get: A = synchronized { value }
  def set(v: A) { synchronized { value } }
  def cas(v0: A, v1: A): Boolean = synchronized { (value == v0) && { value = v1 ; true } }
}

object Ref {
  def rdcss[A, B](a: Ref[A], a0: A, b: Ref[B], b0: B, b1: B): Boolean = {
    // the R in RDCSS means we can always locks a then b
    a.synchronized {
      (a.get == a0) && b.cas(b0, b1)
    }
  }
}

class LockFreeHashTrie[A, B](rootRef: Ref[Node[A, B]]) {

  def get(key: A): Option[B] = get(rootRef.get, rootRef, 0, key, key.##)

  @tailrec private def get(root: Node[A, B],
                           nodeRef: Ref[Node[A, B]],
                           shift: Int,
                           key: A,
                           hash: Int): Option[B] = {
    (if (shift == 0) root else nodeRef.get) match {
      case leaf: Leaf[A, B] => {
        if (shift != 0 && (rootRef.get != root))
          get(rootRef.get, rootRef, 0, key, hash) // retry from root
        else
          leaf.get(key, hash)
      }
      case branch: Branch[A, B] => get(
          root, branch.childRefs(chIndex(shift, hash)), shift + LogBF, key, hash)
    }
  }

  def put(key: A, value: B): Option[B] =
      put(rootRef.get, rootRef, 0, key, key.##, value)

  @tailrec private def put(root: Node[A, B],
                           nodeRef: Ref[Node[A, B]],
                           shift: Int = 0,
                           key: A,
                           hash: Int,
                           value: B): Option[B] = {
    (if (shift == 0) root else nodeRef.get) match {
      case leaf: Leaf[A, B] => {
        val after = leaf.withPut(root.gen, key, hash, value)
        if (leaf == after || Ref.rdcss(rootRef, root, nodeRef, leaf, after))
          leaf.get(key, hash) // no change or successful RDCSS
        else
          put(rootRef.get, rootRef, 0, key, hash, value) // retry from root
      }
      case branch: Branch[A, B] => {
        val b = unfreeze(root.gen, nodeRef, branch)
        put(root, b.childRefs(chIndex(shift, hash)), shift + LogBF, key, hash, value)
      }
    }
  }

  private def unfreeze(rootGen: Long, nodeRef: Ref[Node[A, B]], branch: Branch[A, B]): Branch[A, B] = {
    if (branch.gen == rootGen)
      branch
    else {
      nodeRef.cas(branch, branch.copy(rootGen))
      nodeRef.get.asInstanceOf[Branch[A, B]]
    }
  }

}

object LockFreeHashTrie {
  val LogBF = 4
  val BF = 1 << LogBF
  val LC = 14

  def chIndex(shift: Int, hash: Int) = (hash >> shift) & (BF - 1)

  abstract class Node[A, B] {
    def gen: Long
  }

  class Leaf[A, B](val hashes: Array[Int],
                   val keys: Array[A],
                   val values: Array[B]) extends Node[A, B] {
    def gen = 0L

    def get(key: A, hash: Int): Option[B] = {
      val i = find(key, hash)
      if (i >= 0) Some(values(i)) else None
    }

    /** Returns matching index, or ~insertionPoint */
    def find(key: A, hash: Int): Int = {
      var i = hashes.length
      while (i > 0) {
        i -= 1
        val h = hashes(i)
        if (h == hash && key == keys(i))
          return i
        if (h < hash)
          return ~(i + 1)
      }
      return ~0
    }

    def withPut(gen: Long, key: A, hash: Int, value: B): Node[A, B] = {
      null
    }
  }

  class Branch[A, B](val gen: Long,
                     val childRefs: Array[Ref[Node[A, B]]]) extends Node[A, B] {
    def copy(newGen: Long): Branch[A, B] = null
  }
}

