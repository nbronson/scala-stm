/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// RedBlackTreeMap

package scala.concurrent.stm.experimental.impl


import scala.concurrent.stm._
import scala.concurrent.stm.experimental.TMap
import scala.concurrent.stm.experimental.TMap.Bound
import scala.concurrent.stm.TxnFieldUpdater.Base


class RedBlackTreeMap[A,B] extends TMap[A,B] {

  //////////////// TMap stuff

  def isEmpty(implicit txn: Txn): Boolean = (null != root)

  def size(implicit txn: Txn): Int = {
    var s = 0
    val iter = bind.iterator
    while (iter.hasNext) { s += 1; iter.next() }
    s
  }

  protected def transformIfDefined(key: A,
                                   pfOrNull: PartialFunction[Option[B],Option[B]],
                                   f: Option[B] => Option[B])(implicit txn: Txn): Boolean = {
    val v0 = get(key)
    if (null != pfOrNull && !pfOrNull.isDefinedAt(v0)) {
      false
    } else {
      f(v0) match {
        case Some(v) => put(key, v)
        case None => remove(key)
      }
      false
    }
  }


  def bind(implicit txn0: Txn): Bound[A, B] = new TMap.AbstractTxnBound[A,B,RedBlackTreeMap[A,B]](txn0, this) {
    override def subRange(begin: A, end: A): Iterator[(A,B)] = new Iterator[(A,B)] {
      val endCmp = end.asInstanceOf[Comparable[A]]
      
      var avail = ceilingNode(begin.asInstanceOf[Comparable[A]], root)
      if (avail != null && endCmp.compareTo(avail.key) <= 0) avail = null

      def hasNext = null != avail
      def next() = {
        val z = (avail.key, avail.value)
        avail = successor(avail)
        if (avail != null && endCmp.compareTo(avail.key) <= 0) avail = null
        z
      }
    }

    def iterator: Iterator[(A,B)] = new Iterator[(A,B)] {
      var avail = firstNode

      def hasNext = null != avail
      def next() = {
        val z = (avail.key, avail.value)
        avail = successor(avail)
        z
      }
    }

    override def higher(key: A): Option[(A,B)] = unbind.higher(key)(txn)
  }

  def escaped: Bound[A,B] = new TMap.AbstractNonTxnBound[A,B,RedBlackTreeMap[A,B]](this) {

    override def isEmpty = null != rootRef.escaped.get
    override def size = STM.atomic(unbind.size(_))

    def get(key: A): Option[B] = STM.atomic(unbind.get(key)(_))
    override def higher(key: A): Option[(A,B)] = STM.atomic(unbind.higher(key)(_))
    override def put(key: A, value: B): Option[B] = STM.atomic(unbind.put(key, value)(_))
    override def remove(key: A): Option[B] = STM.atomic(unbind.remove(key)(_))

    protected def transformIfDefined(key: A,
                                     pfOrNull: PartialFunction[Option[B],Option[B]],
                                     f: Option[B] => Option[B]): Boolean = {
      STM.atomic(unbind.transformIfDefined(key, pfOrNull, f)(_))
    }

    override def subRange(begin: A, end: A): Iterator[Tuple2[A,B]] = {
      STM.atomic(unbind.bind(_).subRange(begin, end).toSeq).iterator
    }

    def iterator: Iterator[Tuple2[A,B]] = {
      STM.atomic(unbind.bind(_).toSeq).iterator
    }
  }



  //////////////// internal state

  private def BLACK = true
  private def RED = false

  private def root(implicit txn: Txn): RBNode[A,B] = rootRef.get
  private def root_=(v: RBNode[A,B])(implicit txn: Txn) { rootRef.set(v) }
  private val rootRef = Ref[RBNode[A,B]](null)

  //////////////// core public interface

  def clear()(implicit txn: Txn) {
    root = null
    // TODO: size = 0
  }

  def containsKey(key: A)(implicit txn: Txn) = null != getNode(key)

  def get(key: A)(implicit txn: Txn): Option[B] = {
    getNode(key) match {
      case null => None
      case n => Some(n.value)
    }
  }

  def put(key: A, value: B)(implicit txn: Txn): Option[B] = {
    if (null == key) throw new NullPointerException

    var t = root
    if (null == t) {
      // easy
      root = new RBNode(key, value, null)
      //TODO: size = 1
      return None
    }

    val k = key.asInstanceOf[Comparable[A]]
    var cmp = 0
    var parent: RBNode[A,B] = null
    do {
      parent = t
      cmp = k.compareTo(t.key)
      if (cmp < 0) {
        t = t.left
      } else if (cmp > 0) {
        t = t.right
      } else {
        return t.setValue(value)
      }
    } while (null != t)

    val n = new RBNode(key, value, parent)
    if (cmp < 0) {
      parent.left = n
    } else {
      parent.right = n
    }
    fixAfterInsertion(n)
    //TODO: size += 1
    return None
  }

  def remove(key: A)(implicit txn: Txn): Option[B] = {
    val x = getNode(key)
    if (null == x) {
      None
    } else {
      val z = x.value
      deleteNode(x)
      Some(z)
    }
  }

  def higher(key: A)(implicit txn: Txn): Option[(A,B)] = {
    val k = key.asInstanceOf[Comparable[A]]
    val x = higherNode(k, root)
    if (null == x) None else Some((x.key, x.value))
  }

  //////////////// internal implementation

  private def getNode(key: A)(implicit txn: Txn): RBNode[A,B] = {
    val k = key.asInstanceOf[Comparable[A]]
    var p = root
    while (null != p) {
      val cmp = k.compareTo(p.key)
      if (cmp < 0) {
        p = p.left
      } else if (cmp > 0) {
        p = p.right
      } else {
        return p
      }
    }
    return null
  }

  private def firstNode(implicit txn: Txn) = {
    var x = root
    if (null != x) {
      var xl = x.left
      while (null != xl) {
        x = xl
        xl = x.left
      }
    }
    x
  }

  private def successor(x: RBNode[A,B])(implicit txn: Txn) = {
    if (null == x) {
      null
    } else {
      var tmp = x.right
      if (null != tmp) {
        var p = tmp
        tmp = p.left
        while (null != tmp) {
          p = tmp
          tmp = p.left
        }
        p
      } else {
        var p = x.parent
        var ch = x
        while (null != p && ch == p.right) {
          ch = p
          p = p.parent
        }
        p
      }
    }
  }

  private def predecessor(x: RBNode[A,B])(implicit txn: Txn) = {
    if (null == x) {
      null
    } else {
      var tmp = x.left
      if (null != tmp) {
        var p = tmp
        tmp = p.right
        while (null != tmp) {
          p = tmp
          tmp = p.right
        }
        p
      } else {
        var p = x.parent
        var ch = x
        while (null != p && ch == p.left) {
          ch = p
          p = p.parent
        }
        p
      }
    }
  }

  private def higherNode(k: Comparable[A], p: RBNode[A,B])(implicit txn: Txn): RBNode[A,B] = {
    if (null == p) {
      null
    } else {
      val cmp = k.compareTo(p.key)
      if (cmp >= 0) {
        // k is here or on the right, so higher is on the right, if it exists
        higherNode(k, p.right)
      } else {
        // k is on the left, so higher can be on left or the current value
        val z = higherNode(k, p.left)
        if (null == z) p else z
      }
    }
  }

  private def ceilingNode(k: Comparable[A], p: RBNode[A,B])(implicit txn: Txn): RBNode[A,B] = {
    if (null == p) {
      null
    } else {
      val cmp = k.compareTo(p.key)
      if (cmp > 0) {
        // ceiling is on the right, if it exists
        ceilingNode(k, p.right)
      } else {
        // ceiling can be on left or the current value
        val z = ceilingNode(k, p.left)
        if (null == z) p else z
      }
    }
  }


  private def colorOf(p: RBNode[A,B])(implicit txn: Txn) = {
    if (null == p) BLACK else p.color
  }

  private def parentOf(p: RBNode[A,B])(implicit txn: Txn) = {
    if (null == p) null else p.parent
  }

  private def setColor(p: RBNode[A,B], c: Boolean)(implicit txn: Txn) {
    if (null != p && p.color != c) p.color = c
  }

  private def leftOf(p: RBNode[A,B])(implicit txn: Txn) = {
    if (null == p) null else p.left
  }

  private def rightOf(p: RBNode[A,B])(implicit txn: Txn) = {
    if (null == p) null else p.right
  }

  private def rotateLeft(p: RBNode[A,B])(implicit txn: Txn) {
    if (null != p) {
      val r = p.right
      val rl = r.left
      p.right = rl
      if (null != rl) {
        rl.parent = p
      }
      val par = p.parent
      r.parent = par
      if (null == par) {
        root = r
      } else if (par.left == p) {
        par.left = r
      } else {
        par.right = r
      }
      r.left = p
      p.parent = r
    }
  }

  private def rotateRight(p: RBNode[A,B])(implicit txn: Txn) {
    if (null != p) {
      val l = p.left
      val lr = l.right
      p.left = lr
      if (null != lr) {
        lr.parent = p
      }
      val par = p.parent
      l.parent = par
      if (null == par) {
        root = l
      } else if (par.right == p) {
        par.right = l
      } else {
        par.left = l
      }
      l.right = p
      p.parent = l
    }
  }

  private def fixAfterInsertion(x0: RBNode[A,B])(implicit txn: Txn) {
    var x = x0
    x.color = RED

    while (null != x) {
      var xp = x.parent
      if (null == xp || xp.color != RED) {
        // done
        x = null
      } else {
        var xpp = parentOf(xp)
        val xppl = leftOf(xpp)
        val xppr = rightOf(xpp)
        if (xp == xppl) {
          if (colorOf(xppr) == RED) {
            setColor(xp, BLACK)
            setColor(xppr, BLACK)
            setColor(xpp, RED)
            x = xpp
          } else {
            if (x == rightOf(xp)) {
              x = xp
              rotateLeft(x)
              xp = parentOf(x)
              xpp = parentOf(xp)
            }
            setColor(xp, BLACK)
            setColor(xpp, RED)
            rotateRight(xpp)
          }
        } else {
          if (colorOf(xppl) == RED) {
            setColor(xp, BLACK)
            setColor(xppl, BLACK)
            setColor(xpp, RED)
            x = xpp
          } else {
            if (x == leftOf(xp)) {
              x = xp
              rotateRight(x)
              xp = parentOf(x)
              xpp = parentOf(xp)
            }
            setColor(xp, BLACK)
            setColor(xpp, RED)
            rotateLeft(xpp)
          }
        }
      }
    }
    setColor(root, BLACK)
  }

  private def deleteNode(x: RBNode[A,B])(implicit txn: Txn) {
    // TODO: size -= 1

    val xp = x.parent
    val xl = x.left
    val xr = x.right

    if (null != xl && null != xr) {
      val s = successor(x)
      val repl = new RBNode(x.color, s.key, s.value, xp, xl, xr)
      if (null != xp) {
        if (x == xp.left) {
          xp.left = repl
        } else {
          assert(x == xp.right)
          xp.right = repl
        }
      } else {
        root = repl
      }
      if (null != xl) {
        xl.parent = repl
      }
      if (null != xr) {
        xr.parent = repl
      }
      deleteNode(s)
      return
    }

    val repl = if (null != xl) xl else xr

    if (null != repl) {
      repl.parent = xp
      if (null == xp) {
        root = repl
      } else if (x == xp.left) {
        xp.left = repl
      } else {
        xp.right = repl
      }

      x.left = null
      x.right = null
      x.parent = null

      if (x.color == BLACK) {
        fixAfterDeletion(repl)
      }
    } else if (null == xp) {
      root = null
    } else {
      if (x.color == BLACK) {
        fixAfterDeletion(x)
      }

      if (null != xp) {
        if (x == xp.left) {
          xp.left = null
        } else {
          assert(x == xp.right)
          xp.right = null
        }
        x.parent = null
      }
    }
  }

  private def fixAfterDeletion(x0: RBNode[A,B])(implicit txn: Txn) {
    var x = x0
    assert(x != null || root == null)
    while (x != root && colorOf(x) == BLACK) {
      var xp = parentOf(x)
      if (x == leftOf(xp)) {
        var sib = rightOf(xp)

        if (colorOf(sib) == RED) {
          sib.color = BLACK
          setColor(xp, RED)
          rotateLeft(xp)
          xp = parentOf(x)
          sib = rightOf(xp)
        }

        val sl = leftOf(sib)
        var sr = rightOf(sib)
        val src = colorOf(sr)
        if (colorOf(sl) == BLACK && src == BLACK) {
          setColor(sib, RED)
          x = xp
          assert(x != null || root == null)
        } else {
          if (src == BLACK) {
            setColor(sl, BLACK)
            setColor(sib, RED)
            rotateRight(sib)
            xp = parentOf(x)
            sib = rightOf(xp)
            sr = rightOf(sib)
          }
          setColor(sib, colorOf(xp))
          setColor(xp, BLACK)
          setColor(sr, BLACK)
          rotateLeft(xp)
          x = root
          assert(x != null || root == null)
        }
      } else {
        var sib = leftOf(xp)

        if (colorOf(sib) == RED) {
          sib.color = BLACK
          setColor(xp, RED)
          rotateRight(xp)
          xp = parentOf(x)
          sib = leftOf(xp)
        }

        val sr = rightOf(sib)
        var sl = leftOf(sib)
        val slc = colorOf(sl)
        if (colorOf(sr) == BLACK && slc == BLACK) {
          setColor(sib, RED)
          x = xp
          assert(x != null || root == null)
        } else {
          if (slc == BLACK) {
            setColor(sr, BLACK)
            setColor(sib, RED)
            rotateLeft(sib)
            xp = parentOf(x)
            sib = leftOf(xp)
            sl = leftOf(sib)
          }
          setColor(sib, colorOf(xp))
          setColor(xp, BLACK)
          setColor(sl, BLACK)
          rotateRight(xp)
          x = root
          assert(x != null || root == null)
        }
      }
    }

    setColor(x, BLACK)
  }
}


private class RBNode[A,B](color0: Boolean, val key: A, value0: B, parent0: RBNode[A,B], left0: RBNode[A,B], right0: RBNode[A,B]) extends Base {
  import RBNode._

  def this(key0: A, value0: B, parent0: RBNode[A,B]) = this(true, key0, value0, parent0, null, null)

  @volatile private var _color: Boolean = color0
  @volatile private var _value: B = value0
  @volatile private var _parent: RBNode[A,B] = parent0
  @volatile private var _left: RBNode[A,B] = left0
  @volatile private var _right: RBNode[A,B] = right0

  def color(implicit txn: Txn): Boolean = colorRef.get
  def color_=(v: Boolean)(implicit txn: Txn) { colorRef.set(v) }
  def colorRef = Color(this)
  
  def value(implicit txn: Txn): B = valueRef.get
  def value_=(v: B)(implicit txn: Txn) { valueRef.set(v) }
  def valueRef = Value(this)

  def setValue(v: B)(implicit txn: Txn): Option[B] = {
    val prev = value
    value = v
    Some(prev)
  }

  def parent(implicit txn: Txn): RBNode[A,B] = parentRef.get
  def parent_=(v: RBNode[A,B])(implicit txn: Txn) { parentRef.set(v) }
  def parentRef = Parent[A,B](this)

  def left(implicit txn: Txn): RBNode[A,B] = leftRef.get
  def left_=(v: RBNode[A,B])(implicit txn: Txn) { leftRef.set(v) }
  def leftRef = Left[A,B](this)

  def right(implicit txn: Txn): RBNode[A,B] = rightRef.get
  def right_=(v: RBNode[A,B])(implicit txn: Txn) { rightRef.set(v) }
  def rightRef = Right[A,B](this)
}

private object RBNode {
  val Color = new TxnFieldUpdater[RBNode[_,_],Boolean]("color") {
    protected def getField(instance: RBNode[_,_]): Boolean = instance._color
    protected def setField(instance: RBNode[_,_], v: Boolean) { instance._color = v }
  }

  val Value = new TxnFieldUpdater.Generic[RBNode[_,_]]("value") {
    type Instance[X] = RBNode[_,X]
    type Value[X] = X
    protected def getField[V](instance: RBNode[_,V]): V = instance._value
    protected def setField[V](instance: RBNode[_,V], v: V) { instance._value = v }
  }

  val Parent = new TxnFieldUpdater.Generic2[RBNode[_,_]]("parent") {
    type Instance[X,Y] = RBNode[X,Y]
    type Value[X,Y] = RBNode[X,Y]
    protected def getField[A,B](instance: RBNode[A,B]): RBNode[A,B] = instance._parent
    protected def setField[A,B](instance: RBNode[A,B], v: RBNode[A,B]) { instance._parent = v }
  }

  val Left = new TxnFieldUpdater.Generic2[RBNode[_,_]]("left") {
    type Instance[X,Y] = RBNode[X,Y]
    type Value[X,Y] = RBNode[X,Y]
    protected def getField[A,B](instance: RBNode[A,B]): RBNode[A,B] = instance._left
    protected def setField[A,B](instance: RBNode[A,B], v: RBNode[A,B]) { instance._left = v }
  }

  val Right = new TxnFieldUpdater.Generic2[RBNode[_,_]]("right") {
    type Instance[X,Y] = RBNode[X,Y]
    type Value[X,Y] = RBNode[X,Y]
    protected def getField[A,B](instance: RBNode[A,B]): RBNode[A,B] = instance._right
    protected def setField[A,B](instance: RBNode[A,B], v: RBNode[A,B]) { instance._right = v }
  }
}
