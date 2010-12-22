/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// ConcHashMap

package scala.concurrent.stm.experimental.impl

import scala.concurrent.stm._
import experimental.TMap


class ConcHashMap[A,B] extends TMap.Bound[A,B] {
  private val underlying = new java.util.concurrent.ConcurrentHashMap[A,AnyRef]

  def unbind: TMap[A,B] = throw new UnsupportedOperationException
  def context: Option[Txn] = None

  override def isEmpty: Boolean = {
    underlying.isEmpty
  }

  override def size: Int = {
    underlying.size()
  }

  override def apply(key: A): B = {
    val v = underlying.get(key)
    if (null eq v) default(key) else NullValue.decode[B](v)
  }

  def get(key: A): Option[B] = {
    NullValue.decodeOption[B](underlying.get(key))
  }

  override def put(key: A, value: B): Option[B] = {
    NullValue.decodeOption[B](underlying.put(key, NullValue.encode(value)))
  }

  override def update(key: A, value: B) {
    underlying.put(key, NullValue.encode(value))
  }

  override def remove(key: A): Option[B] = {
    NullValue.decodeOption[B](underlying.remove(key))
  }

  def -= (key: A) = {
    underlying.remove(key)
    this
  }

  def transform(key: A, f: (Option[B]) => Option[B]) {
    while (true) {
      var before = get(key)
      f(before) match {
        case Some(v) => {
          if (underlying.replace(key, before, NullValue.encode(v))) return
        }
        case None => {
          if (underlying.remove(key, before)) return
        }
      }
      // retry
    }
  }

  def transformIfDefined(key: A, pf: PartialFunction[Option[B], Option[B]]): Boolean = {
    var before = get(key)
    while (pf.isDefinedAt(before)) {
      pf(before) match {
        case Some(v) => {
          if (underlying.replace(key, before, NullValue.encode(v))) return true
        }
        case None => {
          if (underlying.remove(key, before)) return true
        }
      }
      before = get(key)
      // retry
    }
    return false
  }

  def iterator: Iterator[(A,B)] = {
    return new Iterator[(A,B)] {
      private val iter = underlying.entrySet().iterator()

      def hasNext: Boolean = iter.hasNext

      def next: (A,B) = {
        val e = iter.next()
        (e.getKey(), NullValue.decode[B](e.getValue()))
      }
    }
  }
}
