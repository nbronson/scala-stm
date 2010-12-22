/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// NullValue

package scala.concurrent.stm.experimental.impl


private[impl] object NullValue {
  def encode[B](value: B): AnyRef = {
    val v = value.asInstanceOf[AnyRef]
    if (null eq v) this else v
  }

  def encodeOption[B](vOpt: Option[B]): AnyRef = {
    vOpt match {
      case Some(v) => encode(v)
      case None => null
    }
  }

  def decode[B](packed: AnyRef): B = {
    (if (packed eq this) null else packed).asInstanceOf[B]
  }

  def decodeOption[B](packed: AnyRef): Option[B] = {
    if (null eq packed) None else Some(decode(packed))
  }

  def decodeEntrySetSnapshot[A,B](map: java.util.Map[A,AnyRef]): Iterator[(A,B)] = {
    val a = new Array[(A,B)](map.size())
    var i = 0
    val iter = map.entrySet().iterator()
    while (iter.hasNext()) {
      val e = iter.next()
      a(i) = (e.getKey(), NullValue.decode[B](e.getValue()))
      i += 1
    }
    a
    return new Iterator[(A,B)] {
      private var pos = 0

      def hasNext: Boolean = pos < a.length

      def next: (A,B) = {
        val z = a(pos)
        pos += 1
        z
      }
    }    
  }

  def equal(lhs: AnyRef, rhs: AnyRef): Boolean = {
    (lhs eq rhs) || ((lhs ne null) && (lhs ne NullValue) && (rhs ne null) && (rhs ne NullValue) && (lhs == rhs))
  }
}
