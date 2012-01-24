package scala.concurrent.stm.skel

import java.util.concurrent.atomic.AtomicReference
import annotation.tailrec
import collection.TraversableOnce

private[skel] object RealTimeQueue {

  class PendingRot[A](left: Node[A], right: Node[A], accum: Node[A]) {
    def apply(): Node[A] = rot(left, right, accum)
  }

  class Node[A](var head: A, tail0: AnyRef) extends AtomicReference(tail0) {

    def clone(): Node[A] = new Node(head, get())

    def tail: Node[A] = {
      var t = get()
      if (t != null && t.isInstanceOf[PendingRot[_]]) {
        t = t.asInstanceOf[PendingRot[_]]()
        lazySet(t)
      }
      t.asInstanceOf[Node[A]]
    }

    @tailrec final def apply(i: Int): A = if (i == 0) head else tail(i - 1)
  }

  def rot[A](left: Node[A], right: Node[A], accum: Node[A]): Node[A] = {
    if (left == null) {
      assert(right.tail == null)
      new Node(right.head, accum)
    } else {
      new Node(left.head, new PendingRot(left.tail, right.tail, new Node(right.head, accum)))
    }
  }

  def makeAndForce[A](left: Node[A], leftSize: Int, right: Node[A], rightSize: Int, forcing: Node[A]
                       ): Queue[A] = {
    if (rightSize == leftSize + 1) {
      val newLeft: Node[A] = rot(left, right, null)
      new Queue[A](newLeft, leftSize + rightSize, null, 0, newLeft)
    }
    else {
      assert(rightSize <= leftSize)
      new Queue[A](left, leftSize, right, rightSize, if (forcing == null) null else forcing.tail)
    }
  }

  class QueueIterator[A](var size: Int, var left: Node[A], var right: Node[A]) extends Iterator[A] {
    var accum: Node[A] = null

    def hasNext: Boolean = left != null

    def next: A = {
      if (!hasNext) {
        throw new NoSuchElementException
      }
      val result: A = left.head
      left = left.tail
      if (right != null) {
        accum = new Node[A](right.head, accum)
        right = right.tail
      }
      if (left == null) {
        assert(right == null)
        left = accum
        accum = null
      }
      result
    }
  }

  class Queue[A](var left: Node[A],
                 var leftSize: Int,
                 var right: Node[A],
                 var rightSize: Int,
                 var forcing: Node[A]) extends collection.immutable.Seq[A] {

    def this(q: Queue[A]) = this(q.left, q.leftSize, q.right, q.rightSize, q.forcing)

    override protected[this] def newBuilder = new QueueBuilder[A]

    //////////// immutable methods

    private[RealTimeQueue] def cloneQueue(): Queue[A] = new Queue(this)

    def length = size
    def size: Int = leftSize + rightSize

    def apply(i: Int): A = {
      if (i < 0 || i >= size) throw new IndexOutOfBoundsException

      if (i < leftSize) left(i) else right(size - 1 - i)
    }

    def update(index: Int, v: A): Queue[A] = {
      if (index < 0 || index >= size) throw new IndexOutOfBoundsException

      val builder = new QueueBuilder[A]
      val remaining = cloneQueue()
      var i = 0
      while (i < index) {
        i += 1
        builder += remaining.head
        remaining.removeHead()
      }
      builder += v
      builder ++= remaining.removeHead()

      builder.result
    }

    def head: A = left.head

    def tail: Queue[A] = cloneQueue().removeHead()

    def iterator: Iterator[A] = new Queue[A](this) with Iterator[A] {

      def hasNext: Boolean = left != null

      def next(): A = {
        val z = head
        removeHead()
        z
      }
    }

    //////////// mutating methods

    private[RealTimeQueue] def appendInPlace(v: A): this.type = {
      right = new Node(v, right)
      rightSize += 1
      fixAfterRightShift()
    }

    private[RealTimeQueue] def removeHead(): this.type = {
      left = left.tail
      leftSize -= 1
      fixAfterRightShift()
    }

    /** Restores the balance criteria after an increase in
     *  `rightSize - leftSize` */
    private def fixAfterRightShift(): this.type = {
      if (rightSize == leftSize + 1) {
        left = rot(left, right, null)
        leftSize += rightSize
        right = null
        rightSize = 0
        forcing = left
      } else {
        assert(rightSize <= leftSize)
        if (forcing != null)
          forcing = forcing.tail
      }
      this
    }
  }

  class QueueBuilder[A] extends collection.mutable.Builder[A, Queue[A]] {
    private var first: Node[A] = null
    private var last: Node[A] = null
    private var size = 0
    private var queue: Queue[A] = null

    def +=(elem: A): this.type = {
      if (queue != null)
        queue.appendInPlace(elem)
      else {
        val n = new Node(elem, null)
        if (last != null)
          last.lazySet(n)
        else
          first = n
        last = n
        size += 1
      }
      this
    }

    override def ++=(xs: TraversableOnce[A]): this.type = {
      if (queue == null && xs.isInstanceOf[Queue[_]]) {
        queue = xs.asInstanceOf[Queue[A]].cloneQueue()
        if (first != null) {
          last.lazySet(queue.left)
          queue.left = first
          queue.leftSize += size
        }
      } else {
        for (x <- xs) queue.appendInPlace(x)
      }
      this
    }

    def clear() {
      first = null
      last = null
      size = 0
      queue = null
    }

    def result: Queue[A] = {
      if (queue != null) queue else new Queue(first, size, null, 0, null)
    }
  }
}
