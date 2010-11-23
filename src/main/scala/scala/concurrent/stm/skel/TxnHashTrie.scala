package scala.concurrent.stm
package skel

import annotation.tailrec

object TxnHashTrie {

  private def LogBF = 5
  private def BF = 32
  private def MaxLeafCapacity = 8

  private def keyHash[A](key: A): Int = if (key == null) 0 else rehash(key.##)

  private def rehash(h: Int) = {
    // make sure any bit change results in a change in the bottom LogBF bits
    val x = h ^ (h >>> 15)
    x ^ (x >>> 5) ^ (x >>> 10)
  }

  private def indexFor(shift: Int, hash: Int) = (hash >> shift) & (BF - 1)

  //////// shared instances
  
  private val someNull = Some(null)
  private val emptySetValue = new Leaf[Any, Unit](Array.empty[Int], Array.empty[AnyRef], null)
  private val emptyMapValue = new Leaf[Any, Unit](Array.empty[Int], Array.empty[AnyRef], Array.empty[AnyRef])

  //////// publicly-visible stuff

  sealed abstract class Node[A, B] {
    def setForeach(block: A => Unit)
    def mapForeach(block: ((A, B)) => Unit)
    def setIterator: Iterator[A]
    def mapIterator: Iterator[(A, B)]
  }

  type SetNode[A] = Node[A, AnyRef]

  def emptySetNode[A]: SetNode[A] = emptySetValue.asInstanceOf[SetNode[A]]
  def emptyMapNode[A, B]: Node[A, B] = emptyMapValue.asInstanceOf[Node[A, B]]

  /** If used by a Set, values will be null. */
  class Leaf[A, B](val hashes: Array[Int],
                   val keys: Array[AnyRef],
                   val values: Array[AnyRef]) extends Node[A, B] {

    def contains(hash: Int, key: A): Boolean = find(hash, key) >= 0

    def get(hash: Int, key: A): Option[B] = {
      val i = find(hash, key)
      if (i < 0)
        None
      else if (values != null)
        Some(values(i).asInstanceOf[B])
      else
        someNull.asInstanceOf[Option[B]]
    }

    private def find(hash: Int, key: A): Int = {
      var i = 0
      val hh = hashes
      while (i < hh.length && hh(i) <= hash) {
        if (hh(i) == hash && key == keys(i))
          return i
        i += 1
      }
      return -(i + 1)
    }

    def withPut(hash: Int, key: A, value: B): Leaf[A, B] = {
      val i = find(hash, key)
      if (i < 0)
        withInsert(-(i + 1), hash, key, value)
      else if (values != null)
        withUpdate(i, value)
      else
        this
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
        (if (values == null) emptySetValue else emptyMapValue).asInstanceOf[Leaf[A, B]]
      } else {
        val nvalues = if (values == null) null else new Array[AnyRef](n)
        new Leaf[A, B](new Array[Int](n), new Array[AnyRef](n), nvalues)
      }
    }

    def setForeach(block: A => Unit) {
      var i = 0
      while (i < keys.length) {
        block(keys(i).asInstanceOf[A])
        i += 1
      }
    }

    def mapForeach(block: ((A, B)) => Unit) {
      var i = 0
      while (i < keys.length) {
        block((keys(i).asInstanceOf[A], values(i).asInstanceOf[B]))
        i += 1
      }
    }

    def setIterator: Iterator[A] = new Iterator[A] {
      var pos = 0
      def hasNext = pos < keys.length
      def next: A = { val z = keys(pos).asInstanceOf[A] ; pos += 1 ; z }
    }

    def mapIterator: Iterator[(A, B)] = new Iterator[(A,B)] {
      var pos = 0
      def hasNext = pos < keys.length
      def next: (A, B) = { val z = (keys(pos).asInstanceOf[A], values(pos).asInstanceOf[B]) ; pos += 1 ; z }
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

    def setForeach(block: A => Unit) {
      var i = 0
      while (i < BF) {
        children(i)().setForeach(block)
        i += 1
      }
    }

    def mapForeach(block: ((A, B)) => Unit) {
      var i = 0
      while (i < BF) {
        children(i)().mapForeach(block)
        i += 1
      }
    }

    private abstract class Iter[Z] extends Iterator[Z] {

      def childIter(c: Node[A, B]): Iterator[Z]

      private var pos = -1
      private var iter: Iterator[Z] = null
      advance()

      @tailrec private def advance(): Boolean = {
        if (pos == BF - 1) {
          iter = null
          false
        } else {
          pos += 1
          val c = children(pos)()
          if ((c eq emptySetValue) || (c eq emptyMapValue))
            advance() // keep looking, nothing is here
          else {
            iter = childIter(c)
            iter.hasNext || advance() // keep looking if we got a dud
          }
        }
      }

      def hasNext = iter != null && iter.hasNext

      def next: Z = {
        val z = iter.next
        if (!iter.hasNext)
          advance()
        z
      }
    }

    def setIterator: Iterator[A] = new Iter[A] {
      def childIter(c: Node[A, B]) = c.setIterator
    }

    def mapIterator: Iterator[(A, B)] = new Iter[(A,B)] {
      def childIter(c: Node[A, B]) = c.mapIterator
    }
  }

  //////////////// hash trie operations

  def frozenRoot[A, B](root: Ref.View[Node[A, B]]): Node[A, B] = {
    root() match {
      case leaf: Leaf[A, B] => leaf // leaf is already immutable
      case branch: Branch[A, B] => {
        // If this CAS fails it means someone else already bumped the
        // generation number on our behalf.  If that happens then b is still
        // ours and we don't need to make another copy.
        val b = branch.clone(branch.gen + 1)
        if (!root.compareAndSet(branch, b)) b else branch.clone(branch.gen + 1)
      }
    }
  }

  def clone[A, B](root: Ref.View[Node[A, B]]): Ref.View[Node[A, B]] = Ref(frozenRoot(root)).single

  def contains[A, B](root: Ref.View[Node[A, B]], key: A): Boolean = contains(root, 0, keyHash(key), key)

  @tailrec private def contains[A, B](n: Ref.View[Node[A, B]], shift: Int, hash: Int, key: A): Boolean = {
    n() match {
      case leaf: Leaf[A, B] => leaf.contains(hash, key)
      case branch: Branch[A, B] => contains(branch.children(indexFor(shift, hash)), shift + LogBF, hash, key)
    }
  }

  def get[A, B](root: Ref.View[Node[A, B]], key: A): Option[B] = get(root, 0, keyHash(key), key)

  @tailrec private def get[A, B](n: Ref.View[Node[A, B]], shift: Int, hash: Int, key: A): Option[B] = {
    n() match {
      case leaf: Leaf[A, B] => leaf.get(hash, key)
      case branch: Branch[A, B] => get(branch.children(indexFor(shift, hash)), shift + LogBF, hash, key)
    }
  }

  def put[A, B](root: Ref.View[Node[A, B]], key: A, value: B): Option[B] = {
    put(root, -1L, root, 0, keyHash(key), key, value)
  }

  @tailrec private def put[A, B](root: Ref.View[Node[A, B]], gen: Long, n: Ref.View[Node[A, B]], shift: Int, hash: Int, key: A, value: B): Option[B] = {
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
          case 0 => put(root, gen, n, shift, hash, key, value)
          case 1 => put(root, -1L, root, 0, hash, key, value)
          case 2 => leaf.get(hash, key)
        }
      }
      case branch: Branch[A, B] => {
        if (gen == -1L || branch.gen == gen)
          put(root, branch.gen, branch.children(indexFor(shift, hash)), shift + LogBF, hash, key, value)
        else {
          n.compareAndSetIdentity(branch, branch.clone(gen))
          // try again, either picking up our improvement or someone else's
          put(root, gen, n, shift, hash, key, value)
        }
      }
    }
  }

  def remove[A, B](root: Ref.View[Node[A, B]], key: A): Option[B] = {
    remove(root, -1L, root, 0, keyHash(key), key, false)
  }

  @tailrec private def remove[A, B](root: Ref.View[Node[A, B]], gen: Long, n: Ref.View[Node[A, B]], shift: Int, hash: Int, key: A, checked: Boolean): Option[B] = {
    n() match {
      case leaf: Leaf[A, B] => {
        val after = leaf.withRemove(hash, key)
        if (after eq leaf)
          None // no change, key must not have been present
        else {
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
            case 0 => remove(root, gen, n, shift, hash, key, true)
            case 1 => remove(root, -1L, root, 0, hash, key, false)
            case 2 => leaf.get(hash, key)
          }
        }
      }
      case branch: Branch[A, B] => {
        if (branch.gen == gen)
          remove(root, gen, branch.children(indexFor(shift, hash)), shift + LogBF, hash, key, checked)
        else {
          // no use in cloning paths if the key isn't actually present
          if (!checked && !contains(branch.children(indexFor(shift, hash)), shift + LogBF, hash, key))
            None
          else {
            n.compareAndSetIdentity(branch, branch.clone(gen))
            // try again, either picking up our improvement or someone else's
            remove(root, gen, n, shift, hash, key, true)
          }
        }
      }
    }
  }

  def setForeach[A, B](root: Ref.View[Node[A, B]], block: A => Unit) { frozenRoot(root).setForeach(block) }

  def mapForeach[A, B](root: Ref.View[Node[A, B]], block: ((A, B)) => Unit) { frozenRoot(root).mapForeach(block) }

  def setIterator[A, B](root: Ref.View[Node[A, B]]): Iterator[A] = frozenRoot(root).setIterator

  def mapIterator[A, B](root: Ref.View[Node[A, B]]): Iterator[(A, B)] = frozenRoot(root).mapIterator
}
