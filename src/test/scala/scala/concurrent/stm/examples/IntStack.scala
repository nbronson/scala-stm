/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm.examples

import scala.concurrent.stm._

object IntStack {
  def selectPop(a: IntStack, b: IntStack): (IntStack, Int) = {
    atomic { implicit txn =>
      (a, a.blockingPop())
    } orAtomic { implicit txn =>
      (b, b.blockingPop())
    }
  }
}

class IntStack {
  private class Node(val elem: Int, next0: Node) {
    val next = Ref(next0)
  }

  private val top = Ref(null: Node) // or Ref[Node](null)

  def push(elem: Int) {
    atomic { implicit txn =>
      top() = new Node(elem, top())
    }    
  }

  private def _push(elem: Int) {
    top.single.transform { new Node(elem, _ ) }
  }

  def push(e1: Int, e2: Int, elems: Int*) {
    atomic { implicit txn =>
      push(e1)
      push(e2)
      elems foreach { push(_) }
    }
  }

  //def isEmpty = atomic { implicit t => top() == null }
  def isEmpty = top.single() == null

  // def clear() { atomic { implicit t => top() = null } }
  def clear() { top.single() = null }

  def blockingPop(): Int = atomic { implicit txn =>
    val t = top()
    if (t == null)
      retry
    top() = t.next()
    t.elem
  }

  def maybePop(): Option[Int] = {
    atomic { implicit txn =>
      Some(blockingPop())
    } orAtomic { implicit txn =>
      None
    }
  }

  def remove(elem: Int): Boolean = atomic { implicit txn =>
    def loop(ref: Ref[Node]): Boolean = {
      val n = ref()
      if (n == null)
        false
      else if (n.elem != elem)
        loop(n.next)
      else {
        ref() = n.next()
        true
      }
    }
    loop(top)
  }

  override def toString: String = {    
    atomic { implicit txn =>
      val buf = new StringBuilder
      buf ++= "IntStack("
      var n = top()
      while (n != null) {
        buf ++= n.elem.toString
        n = n.next()
        if (n != null) buf ++= ","
      }
      buf ++= ")"
      buf.toString
    }
  }
}
