/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm.skel

import annotation.tailrec

/** An immutable hash treap that can hold up to 2^16-1 elements. */
/*private[skel]*/ abstract class SmallHashTreap[A, B](prio0: Int, size0: Int) {
  private val _priority = prio0.asInstanceOf[Short]
  private val _size = size0.asInstanceOf[Short]

  def priority: Int = _priority & 0xffff
  def size: Int = _size & 0xffff

  def contains(hash: Int, key: A): Boolean
  def get(hash: Int, key: A): Option[B]
  def withPut(hash: Int, key: A, value: B): SmallHashTreap[A, B]
  def withRemove(hash: Int, key: A): SmallHashTreap[A, B]

  def draw(prefix: String, buf: StringBuilder): StringBuilder

  override def toString: String = draw("", new StringBuilder).toString
}

/*private[skel]*/ object SmallHashTreap {

  def empty[A, B] = emptyValue.asInstanceOf[SmallHashTreap[A, B]]

  private val emptyValue = new Empty[AnyRef, AnyRef]

  private class Empty[A, B] extends SmallHashTreap[A, B](0, 0) {
    def contains(h: Int, k: A): Boolean = false
    def get(h: Int, k: A): Option[B] = None
    def hit(h: Int): Boolean = false
    def withPut(h: Int, k: A, v: B): SmallHashTreap[A, B] = new Node[A, B](newPriority, 1, h, null, null, k, v)
    def withRemove(h: Int, k: A): SmallHashTreap[A, B] = this
    def draw(prefix: String, buf: StringBuilder): StringBuilder = { buf ++= prefix + "Empty()" }
  }

  def newPriority: Int = {
    val p = FastSimpleRandom.nextInt >>> 16
    if (p == 0) 7 else p
  }

  private abstract class NonEmpty[A, B](prio0: Int,
                                        size0: Int,
                                        val hash: Int,
                                        val left: NonEmpty[A, B],
                                        val right: NonEmpty[A, B]) extends SmallHashTreap[A, B](prio0, size0) {

    def containsHere(k: A): Boolean
    def getHere(k: A): Option[B]
    def relink(nsize: Int, nleft: NonEmpty[A, B], nright: NonEmpty[A, B]): NonEmpty[A, B]
    def withPutHitHere(k: A, v: B): NonEmpty[A, B]
    def withRemoveHere(k: A): NonEmpty[A, B]
    def drawHere(buf: StringBuilder)

    @tailrec final def contains(h: Int, k: A): Boolean = {
      if (h == hash)
        containsHere(k)
      else {
        val next = if (h < hash) left else right
        (next != null) && next.contains(h, k)
      }
    }

    @tailrec final def get(h: Int, k: A): Option[B] = {
      if (h == hash)
        getHere(k)
      else {
        val next = if (h < hash) left else right
        if (next == null) None else next.get(h, k)
      }
    }

    @tailrec final def hit(h: Int): Boolean = {
      if (h == hash)
        true
      else {
        val next = if (h < hash) left else right
        (next != null) && next.hit(h)
      }
    }

    def withPut(hash: Int, key: A, value: B): SmallHashTreap[A, B] = {
      if (hit(hash))
        withPutHit(hash, key, value)
      else
        withPutMiss(SmallHashTreap.newPriority, hash, key, value)
    }

    def withPutMiss(prio: Int, h: Int, k: A, v: B): NonEmpty[A, B] = {
      if (h < hash) {
        // go left
        if (left == null) {
          if (prio > priority)
            new Node(prio, 1 + size, h, null, this, k, v)
          else
            this.withLeft(1 + size, new Node(prio, 1, h, null, null, k, v))
        } else {
          val nleft = left.withPutMiss(prio, h, k, v)
          if (prio > priority)
            nleft.withRight(1 + size, this.withLeft(nleft.right))
          else
            this.withLeft(1 + size, nleft)
        }
      } else {
        if (right == null) {
          if (prio > priority)
            new Node(prio, 1 + size, h, this, null, k, v)
          else
            this.withRight(1 + size, new Node(prio, 1, h, null, null, k, v))
        } else {
          val nright = right.withPutMiss(prio, h, k, v)
          if (prio > priority)
            nright.withLeft(1 + size, this.withRight(nright.left))
          else
            this.withRight(1 + size, nright)
        }
      }
    }

    def computeSize(nleft: NonEmpty[A, B], nright: NonEmpty[A, B]): Int = {
      1 + (if (nleft == null) 0 else nleft.size) + (if (nright == null) 0 else nright.size)
    }

    def withLeft(nleft: NonEmpty[A, B]): NonEmpty[A, B] = withLeft(computeSize(nleft, right), nleft)
    def withLeft(nsize: Int, nleft: NonEmpty[A, B]): NonEmpty[A, B] = relink(nsize, nleft, right)
    def withRight(nright: NonEmpty[A, B]): NonEmpty[A, B] = withRight(computeSize(left, nright), nright)
    def withRight(nsize: Int, nright: NonEmpty[A, B]): NonEmpty[A, B] = relink(nsize, left, nright)


    def withPutHit(h: Int, k: A, v: B): NonEmpty[A, B] = {
      if (h == hash)
        withPutHitHere(k, v)
      else if (h < hash) {
        val nleft = left.withPutHit(h, k, v)
        this.withLeft(size + nleft.size - left.size, nleft)
      } else {
        val nright = right.withPutHit(h, k, v)
        this.withRight(size + nright.size - right.size, nright)
      }
    }
    
    def withRemove(h: Int, k: A): NonEmpty[A, B] = {
      if (h == hash) {
        val nthis = withRemoveHere(k)
        if (nthis != null)
          nthis // either k was not a match, or this hash is still present
        else if (left == null) {
          right
        } else if (right == null) {
          left
        } else if (left.priority > right.priority) {
          // pull the left
          left.withRight(size - 1, withAdd(right, left.right))
        } else {
          right.withLeft(size - 1, withAdd(left, right.left))
        }
      } else if (h < hash) {
        val nleft = if (left == null) null else left.withRemove(h, k)
        if (nleft eq left)
          this
        else
          this.withLeft(size - 1, nleft)
      } else {
        val nright = if (right == null) null else right.withRemove(h, k)
        if (nright eq right)
          this
        else
          this.withRight(size - 1, nright)
      }
    }

    def draw(prefix: String, buf: StringBuilder): StringBuilder = {
      buf ++= prefix + "hash=" + hash + ", priority=" + priority + ", size=" + size + ", "
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


  private def withAdd[A, B](a: NonEmpty[A, B], b: NonEmpty[A, B]): NonEmpty[A, B] = {
    if (a == null)
      b
    else if (b == null)
      a
    else if (a.priority > b.priority)
      withAddImpl(a, b)
    else
      withAddImpl(b, a)
  }

  private def withAddImpl[A, B](hi: NonEmpty[A, B], lo: NonEmpty[A, B]): NonEmpty[A, B] = {
    if (lo.hash < hi.hash)
      hi.withLeft(withAdd(hi.left, lo))
    else
      hi.withRight(withAdd(hi.right, lo))
  }

  private class Node[A, B](p: Int, s: Int, h: Int, l: NonEmpty[A, B], r: NonEmpty[A, B],
                           val key: A, val value: B) extends NonEmpty[A, B](p, s, h, l, r) {

    def containsHere(k: A): Boolean = k == key

    def getHere(k: A): Option[B] = if (k == key) Some(value) else None

    def relink(nsize: Int, nleft: NonEmpty[A, B], nright: NonEmpty[A, B]) = {
      new Node(priority, nsize, hash, nleft, nright, key, value)
    }

    def withPutHitHere(k: A, v: B): NonEmpty[A, B] = {
      if (k == key)
        new Node(priority, size, hash, left, right, key, v)
      else
        new Collision(priority, size + 1, hash, left, right, List((key -> value), (k -> v)))
    }

    def withRemoveHere(k: A): NonEmpty[A, B] = {
      if (k == key)
        null
      else
        this
    }

    def drawHere(buf: StringBuilder) {
      buf ++= "(" + key + " -> " + value + ")"
    }
  }

  private class Collision[A, B](p: Int, s: Int, h: Int, l: NonEmpty[A, B], r: NonEmpty[A, B],
                                val kvs: List[(A, B)]) extends NonEmpty[A, B](p, s, h, l, r) {

    def containsHere(k: A): Boolean = kvs exists { k == _._1 }

    def getHere(k: A): Option[B] = (kvs find { k == _._1 }) map { _._2 }

    def relink(nsize: Int, nleft: NonEmpty[A, B], nright: NonEmpty[A, B]) = {
      new Collision(priority, nsize, hash, nleft, nright, kvs)
    }

    def withPutHitHere(k: A, v: B): NonEmpty[A, B] = {
      val trimmed = remove(kvs, k)
      val nsize = if (trimmed eq kvs) size + 1 else size
      new Collision(priority, nsize, hash, left, right, (k -> v) :: trimmed)
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

    def withRemoveHere(k: A): NonEmpty[A, B] = {
      val nkvs = remove(kvs, k)
      if (nkvs eq kvs)
        this
      else if (nkvs.tail.isEmpty)
        new Node(priority, size - 1, hash, left, right, nkvs.head._1, nkvs.head._2)
      else
        new Collision(priority, size - 1, hash, left, right, nkvs)
    }

    def drawHere(buf: StringBuilder) {
      buf ++= kvs.toString
    }
  }
}

