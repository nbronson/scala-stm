package scala.concurrent.stm
package skel

import annotation.tailrec

/** `TxnHashTrie` implements a transactional mutable hash trie using Ref-s,
 *  with lazy cloning to allow efficient snapshots.  Fundamental operations are
 *  provided for hash tries representing either sets or maps, both of which are
 *  represented as a Ref.View[Node[A, B]].  If the initial empty leaf is
 *  `emptySetValue` then no values can be stored in the hash trie, and
 *  operations that take or return values will expect and produce null.
 */
private[skel] object TxnHashTrie {

  private def LogBF = 5
  private def BF = 32

  // It would seem that the leaf copying is inefficient when compared to a tree
  // that allows more sharing, but a back-of-the-envelope calculation indicates
  // that the crossover point for the total bytes allocates to construct an
  // immutable node holding N elements is about 12.  Even at N=32 the total
  // bytes required by this Leaf implementation is only about 2/3 more than an
  // ideal balanced tree, and those bytes are accessed in a more cache friendly
  // fashion.
  private def MaxLeafCapacity = 14

  private def keyHash[A](key: A): Int = if (key == null) 0 else mixBits(key.##)

  private def mixBits(h: Int) = {
    // make sure any bit change results in a change in the bottom LogBF bits
    val s = LogBF
    val x = h ^ (h >>> (s * 3)) ^ (h >>> (s * 6))
    x ^ (x >>> s) ^ (x >>> (s * 2))
  }

  private def indexFor(shift: Int, hash: Int) = (hash >>> shift) & (BF - 1)

  //////// shared instances
  
  private val someNull = Some(null)
  private val emptySetValue = new Leaf[Any, Unit](Array.empty[Int], Array.empty[AnyRef], null)
  private val emptyMapValue = new Leaf[Any, Unit](Array.empty[Int], Array.empty[AnyRef], Array.empty[AnyRef])

  //////// publicly-visible stuff

  sealed abstract class Node[A, B] {
    def cappedSize(cap: Int): Int
    def setForeach[U](f: A => U)
    def mapForeach[U](f: ((A, B)) => U)
    def setIterator: Iterator[A]
    def mapIterator: Iterator[(A, B)]
  }

  sealed trait BuildingNode[A, B] {
    def endBuild: Node[A, B]
  }

  type SetNode[A] = Node[A, AnyRef]

  def emptySetNode[A]: SetNode[A] = emptySetValue.asInstanceOf[SetNode[A]]
  def emptyMapNode[A, B]: Node[A, B] = emptyMapValue.asInstanceOf[Node[A, B]]

  /** If used by a Set, values will be null. */
  class Leaf[A, B](val hashes: Array[Int],
                   val keys: Array[AnyRef],
                   val values: Array[AnyRef]) extends Node[A, B] with BuildingNode[A, B] {

    def endBuild = this

    def cappedSize(cap: Int): Int = hashes.length

    def contains(hash: Int, key: A): Boolean = find(hash, key) >= 0

    def get(hash: Int, key: A): Option[B] = {
      val i = find(hash, key)
      if (i < 0)
        None
      else if (values != null)
        Some(values(i).asInstanceOf[B])
      else
        someNull.asInstanceOf[Option[B]] // if we don't handle sets here we need to versions of TxnHashTrie.remove
    }

    private def find(hash: Int, key: A): Int = {
      var i = 0
      val hh = hashes
      while (i < hh.length && hh(i) <= hash) {
        if (hh(i) == hash && key == keys(i))
          return i
        i += 1
      }
      return ~i
    }

    def withPut(hash: Int, key: A, value: B): Leaf[A, B] = {
      val i = find(hash, key)
      if (i < 0)
        withInsert(~i, hash, key, value)
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
      val children = new Array[Node[A, B]](BF)
      splitInto(shift, children)
      val refs = new Array[Ref.View[Node[A, B]]](BF)
      var i = 0
      while (i < BF) {
        refs(i) = Ref(children(i)).single
        i += 1
      }
      new Branch[A, B](gen, false, refs)      
    }

    def buildingSplit(shift: Int): BuildingBranch[A, B] = {
      val children = new Array[BuildingNode[A, B]](BF)
      splitInto(shift, children)
      new BuildingBranch[A, B](children)
    }

    private def splitInto[L >: Leaf[A, B]](shift: Int, children: Array[L]) {
      val sizes = new Array[Int](BF)
      var i = 0
      while (i < hashes.length) {
        sizes(indexFor(shift, hashes(i))) += 1
        i += 1
      }
      i = 0
      while (i < BF) {
        children(i) = newLeaf(sizes(i))
        i += 1
      }
      i = hashes.length - 1
      while (i >= 0) {
        val slot = indexFor(shift, hashes(i))
        sizes(slot) -= 1
        val pos = sizes(slot)
        val dst = children(slot).asInstanceOf[Leaf[A, B]]
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
    }

    private def newLeaf(n: Int): Leaf[A, B] = {
      if (n == 0) {
        (if (values == null) emptySetValue else emptyMapValue).asInstanceOf[Leaf[A, B]]
      } else {
        val nvalues = if (values == null) null else new Array[AnyRef](n)
        new Leaf[A, B](new Array[Int](n), new Array[AnyRef](n), nvalues)
      }
    }

    def setForeach[U](f: A => U) {
      var i = 0
      while (i < keys.length) {
        f(keys(i).asInstanceOf[A])
        i += 1
      }
    }

    def mapForeach[U](f: ((A, B)) => U) {
      var i = 0
      while (i < keys.length) {
        f((keys(i).asInstanceOf[A], values(i).asInstanceOf[B]))
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

  class BuildingBranch[A, B](val children: Array[BuildingNode[A, B]]) extends BuildingNode[A, B] {
    def endBuild: Node[A, B] = {
      val refs = new Array[Ref.View[Node[A, B]]](BF)
      var i = 0
      while (i < BF) {
        refs(i) = Ref(children(i).endBuild).single
        i += 1
      }
      new Branch(0L, false, refs)
    }
  }

  class Branch[A, B](val gen: Long, val frozen: Boolean, val children: Array[Ref.View[Node[A, B]]]) extends Node[A, B] {

    // size may only be called on a frozen branch, so we can cache the result
    private var _cachedSize = -1

    def cappedSize(cap: Int): Int = {
      val n0 = _cachedSize
      if (n0 >= 0) {
        n0
      } else {
        var n = 0
        var i = 0
        while (i < BF && n < cap) {
          n += children(i)().cappedSize(cap - n)
          i += 1
        }
        if (n < cap)
          _cachedSize = n // everybody tried their hardest
        n
      }
    }

    def withFreeze: Branch[A, B] = new Branch(gen, true, children)

    def clone(newGen: Long): Branch[A, B] = {
      val cc = children.clone
      var i = 0
      while (i < cc.length) {
        cc(i) = Ref(cc(i)()).single
        i += 1
      }
      new Branch[A, B](newGen, false, cc)
    }

    def setForeach[U](f: A => U) {
      var i = 0
      while (i < BF) {
        children(i)().setForeach(f)
        i += 1
      }
    }

    def mapForeach[U](f: ((A, B)) => U) {
      var i = 0
      while (i < BF) {
        children(i)().mapForeach(f)
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

  //////////////// construction

  def buildMap[A, B](kvs: TraversableOnce[(A, B)]): Node[A, B] = {
    var root = emptyMapValue.asInstanceOf[BuildingNode[A, B]]
    for (kv <- kvs)
      root = buildingPut(root, 0, keyHash(kv._1), kv._1, kv._2)
    root.endBuild
  }

  def buildSet[A](ks: TraversableOnce[A]): SetNode[A] = {
    var root = emptySetValue.asInstanceOf[BuildingNode[A, AnyRef]]
    for (k <- ks)
      root = buildingPut(root, 0, keyHash(k), k, null)
    root.endBuild
  }

  private def buildingPut[A, B](current: BuildingNode[A, B], shift: Int, hash: Int, key: A, value: B): BuildingNode[A, B] = {
    current match {
      case leaf: Leaf[A, B] => {
        val a = leaf.withPut(hash, key, value)
        if (!a.shouldSplit) a else a.buildingSplit(shift)
      }
      case branch: BuildingBranch[A, B] => {
        val i = indexFor(shift, hash)
        branch.children(i) = buildingPut(branch.children(i), shift + LogBF, hash, key, value)
        branch
      }
    }
  }

  //////////////// hash trie operations

  def frozenRoot[A, B](root: Ref.View[Node[A, B]]): Node[A, B] = {
    root() match {
      case leaf: Leaf[A, B] => leaf // leaf is already immutable
      case branch: Branch[A, B] if branch.frozen => branch
      case branch: Branch[A, B] => {
        // If this CAS fails it means someone else already installed a frozen
        // branch, and we can benefit from their work.
        val b = branch.withFreeze
        root.compareAndSetIdentity(branch, b)
        b
      }
    }
  }

  def clone[A, B](root: Ref.View[Node[A, B]]): Ref.View[Node[A, B]] = Ref(frozenRoot(root)).single

  def sizeGE[A, B](root: Ref.View[Node[A, B]], n: Int): Boolean = frozenRoot(root).cappedSize(n) >= n
  
  def size[A, B](root: Ref.View[Node[A, B]]): Int = frozenRoot(root).cappedSize(Int.MaxValue)

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

  def put[A, B](root: Ref.View[Node[A, B]], key: A, value: B): Option[B] = rootPut(root, keyHash(key), key, value, 0)

  @tailrec private def rootPut[A, B](root: Ref.View[Node[A, B]], hash: Int, key: A, value: B, failures: Int): Option[B] = {
    if (failures > 10)
      failingPut(root, hash, key, value)
    else {
      root() match {
        case leaf: Leaf[A, B] => {
          val a = leaf.withPut(hash, key, value)
          val after = if (!a.shouldSplit) a else a.split(0L, 0)
          if (root.compareAndSetIdentity(leaf, after))
            leaf.get(hash, key) // success, read from old leaf
          else
            rootPut(root, hash, key, value, failures + 1) // retry
        }
        case branch: Branch[A, B] => {
          var b = branch
          if (!b.frozen || { b = branch.clone(branch.gen + 1) ; root.compareAndSetIdentity(branch, b) })
            childPut(root, b, b.children(indexFor(0, hash)), LogBF, hash, key, value, failures)
          else
            rootPut(root, hash, key, value, failures + 1) // retry
        }
      }
    }
  }

  private def failingPut[A, B](root: Ref.View[Node[A, B]], hash: Int, key: A, value: B): Option[B] = atomic { _ =>
    // running in a transaction guarantees that CAS won't fail
    rootPut(root, hash, key, value, 0)
  }

  @tailrec private def childPut[A, B](root: Ref.View[Node[A, B]],
                                      rootNode: Branch[A, B],
                                      current: Ref.View[Node[A, B]],
                                      shift: Int,
                                      hash: Int,
                                      key: A,
                                      value: B,
                                      failures: Int): Option[B] = {
    current() match {
      case leaf: Leaf[A, B] => {
        val a = leaf.withPut(hash, key, value)
        if (a eq leaf) {
          // we must be set-like, and key was already present, success
          someNull.asInstanceOf[Option[B]]
        } else {
          val after = if (!a.shouldSplit) a else a.split(rootNode.gen, shift)
          if (atomic.compareAndSetIdentity(root.ref, rootNode, rootNode, current.ref, leaf, after))
            leaf.get(hash, key) // success
          else if (root() ne rootNode)
            rootPut(root, hash, key, value, failures + 1) // root retry
          else
            childPut(root, rootNode, current, shift, hash, key, value, failures + 1) // local retry
        }
      }
      case branch: Branch[A, B] => {
        var b = branch
        if (b.gen == rootNode.gen || { b = branch.clone(rootNode.gen) ; current.compareAndSetIdentity(branch, b) })
          childPut(root, rootNode, b.children(indexFor(shift, hash)), shift + LogBF, hash, key, value, failures)
        else
          childPut(root, rootNode, current, shift, hash, key, value, failures + 1) // failure, try again
      }
    }
  }

  def remove[A, B](root: Ref.View[Node[A, B]], key: A): Option[B] = rootRemove(root, keyHash(key), key, 0)

  @tailrec private def rootRemove[A, B](root: Ref.View[Node[A, B]], hash: Int, key: A, failures: Int): Option[B] = {
    if (failures > 10)
      failingRemove(root, hash, key)
    else {
      root() match {
        case leaf: Leaf[A, B] => {
          val after = leaf.withRemove(hash, key)
          if (after eq leaf)
            None // no change, key wasn't present
          else if (root.compareAndSetIdentity(leaf, after))
            leaf.get(hash, key) // success, read from old leaf
          else
            rootRemove(root, hash, key, failures + 1) // retry
        }
        case branch: Branch[A, B] => {
          val i = indexFor(0, hash)
          if (branch.frozen && !contains(branch.children(i), LogBF, hash, key))
            None // child is absent, no point in cloning
          else {
            var b = branch
            if (!branch.frozen || { b = b.clone(b.gen + 1) ; root.compareAndSetIdentity(branch, b) })
              childRemove(root, b, b.children(i), LogBF, hash, key, branch.frozen, failures)
            else
              rootRemove(root, hash, key, failures + 1) // retry
          }
        }
      }
    }
  }

  private def failingRemove[A, B](root: Ref.View[Node[A, B]], hash: Int, key: A): Option[B] = atomic { _ =>
    // running in a transaction guarantees that CAS won't fail
    rootRemove(root, hash, key, 0)
  }

  @tailrec private def childRemove[A, B](root: Ref.View[Node[A, B]],
                                         rootNode: Branch[A, B],
                                         current: Ref.View[Node[A, B]],
                                         shift: Int,
                                         hash: Int,
                                         key: A,
                                         checked: Boolean,
                                         failures: Int): Option[B] = {
    current() match {
      case leaf: Leaf[A, B] => {
        val after = leaf.withRemove(hash, key)
        if (after eq leaf)
          None // no change, key must not have been present
        else if (atomic.compareAndSetIdentity(root.ref, rootNode, rootNode, current.ref, leaf, after))
          leaf.get(hash, key) // success
        else if (root() ne rootNode)
          rootRemove(root, hash, key, failures + 1) // root retry
        else
          childRemove(root, rootNode, current, shift, hash, key, checked, failures + 1) // local retry
      }
      case branch: Branch[A, B] => {
        val i = indexFor(shift, hash)
        if (!checked && branch.gen != rootNode.gen && !contains(branch.children(i), shift + LogBF, hash, key))
          None // child is absent
        else {
          var b = branch
          if (branch.gen == rootNode.gen || { b = b.clone(rootNode.gen) ; current.compareAndSetIdentity(branch, b) })
            childRemove(root, rootNode, b.children(i), shift + LogBF, hash, key, checked || (b ne branch), failures)
          else
            childRemove(root, rootNode, current, shift, hash, key, checked, failures + 1)
        }
      }
    }
  }

  def setForeach[A, B, U](root: Ref.View[Node[A, B]], f: A => U) { frozenRoot(root).setForeach(f) }

  def mapForeach[A, B, U](root: Ref.View[Node[A, B]], f: ((A, B)) => U) { frozenRoot(root).mapForeach(f) }

  def setIterator[A, B](root: Ref.View[Node[A, B]]): Iterator[A] = frozenRoot(root).setIterator

  def mapIterator[A, B](root: Ref.View[Node[A, B]]): Iterator[(A, B)] = frozenRoot(root).mapIterator
}
