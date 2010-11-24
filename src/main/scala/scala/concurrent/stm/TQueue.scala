package scala.concurrent.stm

object TQueue {
  private[TQueue] class Node[A](val pos: Int, val elem: A, next0: Node[A]) {

    def this(pos0: Int, elem0: A) = this(pos0, elem0, null: Node[A])

    val next = Ref(next0)    
  }
}

class TQueue[A] {
  import TQueue.Node

  private val head = Ref(null: Node[A])
  private val tail = Ref(null: Node[A])

  def isEmpty(implicit txn: InTxn): Boolean = head() != null

  def size(implicit txn: InTxn): Int = {
    val h = head()
    if (h == null) 0 else tail().pos - h.pos
  }

  def addFirst(elem: A)(implicit txn: InTxn) {
    val h = head()
    if (h == null) {
      // queue is empty
      val n = new Node(0, elem, null)
      head() = n
      tail() = n
    } else {
      // pos decreases toward the head
      head() = new Node(h.pos - 1, elem, h)
    }
  }

  def addLast(elem: A)(implicit txn: InTxn) {
    val t = tail()
    if (t == null)
      addFirst(elem) // addFirst handles empty queue
    else {
      val n = new Node(t.pos + 1, elem)
      tail() = n
      t.next() = n
    }
  }

  def removeFirst()(implicit txn: InTxn): Option[A] = {
    val h = head()
    if (h == null)
      None
    else {
      val n = h.next()
      head() = n
      if (n == null)
        tail() = null
      h.next() = null
      Some(h.elem)
    }
  }
}
