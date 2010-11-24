/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm.skel

import annotation.tailrec

object SmallHashAVL {

  @tailrec def contains[A, B](n: Node[A, B], h: Int, k: A): Boolean = {
    if (n == null)
      false
    else if (h == n.hash)
      n.containsHere(k)
    else
      contains(if (h < n.hash) n.left else n.right, h, k)
  }

  @tailrec def get[A, B](n: Node[A, B], h: Int, k: A): Option[B] = {
    if (n == null)
      None
    else if (h == n.hash)
      n.getHere(k)
    else
      get(if (h < n.hash) n.left else n.right, h, k)
  }

  def withPut[A, B](n: Node[A, B], h: Int, k: A, v: B): Node[A, B] = {
    if (n == null)
      new Single(1, 1, h, null, null, k, v)
    else
      n.withPut(h, k, v)
  }

  def withRemove[A, B](n: Node[A, B], h: Int, k: A): Node[A, B] = {
    if (n == null)
      null
    else
      n.withRemove(h, k)
  }

  abstract class Node[A, B](size0: Int,
                            height0: Int,
                            val hash: Int,
                            val left: Node[A, B],
                            val right: Node[A, B]) {
    private val packed = (size0 << 8) | height0

    def size: Int = packed >>> 8
    def height: Int = packed & 0xff

    override def toString: String = draw("", new StringBuilder).toString

    def containsHere(k: A): Boolean
    def getHere(k: A): Option[B]
    def relink(childSize: Int, nheight: Int, nleft: Node[A, B], nright: Node[A, B]): Node[A, B]
    def withPutHere(k: A, v: B): Node[A, B]
    def withRemoveHere(k: A): Node[A, B]
    def drawHere(buf: StringBuilder)

    def size(n: Node[A, B]): Int = if (n == null) 0 else n.size
    def height(n: Node[A, B]): Int = if (n == null) 0 else n.height

    def balance: Int = height(left) - height(right)

    def withPut(h: Int, k: A, v: B): Node[A, B] = {
      if (h == hash) {
        // update or enchain
        withPutHere(k, v)
      } else if (h < hash) {
        val nleft = if (left == null) new Single(1, 1, h, null, null, k, v) else left.withPut(h, k, v)
        this.withFix(nleft, right)
      } else {
        val nright = if (right == null) new Single(1, 1, h, null, null, k, v) else right.withPut(h, k, v)
        this.withFix(left, nright)
      }
    }

    def withRemove(h: Int, k: A): Node[A, B] = {
      if (h == hash)
        withRemoveHere(k)
      else if (h < hash) {
        val nleft = if (left == null) null else left.withRemove(h, k)
        if (nleft eq left)
          this
        else
          withFix(nleft, right)
      } else {
        val nright = if (right == null) null else right.withRemove(h, k)
        if (nright eq right)
          this
        else
          withFix(left, nright)
      } 
    }

    def withFix(nleft: Node[A, B], nright: Node[A, B]): Node[A, B] = {
      val hL = height(nleft)
      val hR = height(nright)
      val bal = hL - hR
      if (bal == 2) {
        if (nleft.balance == -1)
          nleft.right.relink(nleft.withRight(nleft.right.left), this.relink(nleft.right.right, nright))
        else
          nleft.withRight(this.relink(nleft.right, nright))
      } else if (bal == -2) {
        if (nright.balance == -1)
          nright.left.relink(this.relink(nleft, nright.left.left), nright.withLeft(nright.left.right))
        else
          nright.withLeft(this.relink(nleft, nright.left))
      } else {
        this.relink(1 + math.max(hL, hR), nleft, nright)
      }
    }

    def withLeft(nleft: Node[A, B]) = relink(nleft, right)
    def withRight(nright: Node[A, B]) = relink(left, nright)
    def relink(nleft: Node[A, B], nright: Node[A, B]): Node[A, B] =
        relink(1 + math.max(height(nleft), height(nright)), nleft, nright)
    def relink(nheight: Int, nleft: Node[A, B], nright: Node[A, B]): Node[A, B] =
        relink(size(nleft) + size(nright), nheight, nleft, nright)

    def draw(prefix: String, buf: StringBuilder): StringBuilder = {
      buf ++= prefix + "hash=" + hash + ", size=" + size + ", height=" + height + ", "
      drawHere(buf)
      buf ++= "\n"
      if (left != null || right != null) {
        if (left == null)
          buf ++= prefix + "  null\n"
        else
          left.draw(prefix + "  ", buf)
        if (right == null)
          buf ++= prefix + "  null\n"
        else
          right.draw(prefix + "  ", buf)
      }
      buf
    }
  }

  private class Routing[A, B](size0: Int, height0: Int, hash0: Int, left0: Node[A, B], right0: Node[A, B]
          ) extends Node[A, B](size0, height0, hash0, left0, right0) {

    def containsHere(k: A): Boolean = false

    def getHere(k: A): Option[B] = None

    def relink(childSize: Int, nheight: Int, nleft: Node[A, B], nright: Node[A, B]) = {
      if (nleft == null)
        nright
      else if (nright == null)
        nleft
      else
        new Routing(childSize, nheight, hash, nleft, nright)
    }

    def withPutHere(k: A, v: B): Node[A, B] = {
      new Single(size + 1, height, hash, left, right, k, v)
    }

    def withRemoveHere(k: A): Node[A, B] = this

    def drawHere(buf: StringBuilder) {
      buf ++= "routing node"
    }
  }

  private class Single[A, B](size0: Int, height0: Int, hash0: Int, left0: Node[A, B], right0: Node[A, B],
                             val key: A, val value: B
          ) extends Node[A, B](size0, height0, hash0, left0, right0) {

    def containsHere(k: A): Boolean = k == key

    def getHere(k: A): Option[B] = if (k == key) Some(value) else None

    def relink(childSize: Int, nheight: Int, nleft: Node[A, B], nright: Node[A, B]) = {
      new Single(1 + childSize, nheight, hash, nleft, nright, key, value)
    }

    def withPutHere(k: A, v: B): Node[A, B] = {
      if (k == key)
        new Single(size, height, hash, left, right, key, v)
      else
        new Collision(size + 1, height, hash, left, right, List((key -> value), (k -> v)))
    }

    def withRemoveHere(k: A): Node[A, B] = {
      if (k == key)
        new Routing(size - 1, height, hash, left, right)
      else
        this
    }

    def drawHere(buf: StringBuilder) {
      buf ++= "(" + key + " -> " + value + ")"
    }
  }

  private class Collision[A, B](size0: Int, height0: Int, hash0: Int, left0: Node[A, B], right0: Node[A, B],
                                val kvs: List[(A, B)]
          ) extends Node[A, B](size0, height0, hash0, left0, right0) {

    def containsHere(k: A): Boolean = kvs exists { k == _._1 }

    def getHere(k: A): Option[B] = (kvs find { k == _._1 }) map { _._2 }

    def relink(childSize: Int, nheight: Int, nleft: Node[A, B], nright: Node[A, B]) = {
      new Collision(childSize + kvs.size, nheight, hash, nleft, nright, kvs)
    }

    def withPutHere(k: A, v: B): Node[A, B] = {
      val trimmed = remove(kvs, k)
      val nsize = if (trimmed eq kvs) size + 1 else size
      new Collision(size, height, hash, left, right, (k -> v) :: trimmed)
    }

    @tailrec private def findSuffix(list: List[(A, B)], k: A): List[(A, B)] = list match {
      case head :: tail if k == head._1 => tail
      case head :: tail => findSuffix(tail, k)
      case _ => null
    }

    @tailrec private def accumPrefix(list: List[(A, B)], k: A, accum: List[(A, B)]): List[(A, B)] = list match {
      case head :: tail if k == head._1 => accum
      case head :: tail => accumPrefix(tail, k, head :: accum)
      case _ => throw new Error
    }

    private def remove(list: List[(A, B)], k: A): List[(A, B)] = {
      val s = findSuffix(list, k)
      if (s == null)
        list // not found
      else
        accumPrefix(list, k, s)
    }

    def withRemoveHere(k: A): Node[A, B] = {
      val nkvs = remove(kvs, k)
      if (nkvs eq kvs)
        this
      else if (nkvs.tail.isEmpty)
        new Single(size - 1, height, hash, left, right, nkvs.head._1, nkvs.head._2)
      else
        new Collision(size - 1, height, hash, left, right, nkvs)
    }

    def drawHere(buf: StringBuilder) {
      buf ++= kvs.toString
    }
  }
}
