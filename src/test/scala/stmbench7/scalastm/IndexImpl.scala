/* scala-stm - (c) 2009-2011, Stanford University, PPL */

package stmbench7.scalastm

import scala.collection.immutable.TreeMap
import scala.collection.JavaConversions
import scala.concurrent.stm._
import stmbench7.backend.Index

object IndexImpl {
  class BoxedImmutable[A <: Comparable[A], B] extends Index[A,B] {
    val underlying = Ref(TreeMap.empty[A,B]).single

    def get(key: A) = underlying().getOrElse(key, null.asInstanceOf[B])

    def put(key: A, value: B) { underlying.transform(_ + (key -> value)) }

    def putIfAbsent(key: A, value: B): B = {
      underlying.getAndTransform({ m => if (m.contains(key)) m else m + (key -> value) }).getOrElse(key, null.asInstanceOf[B])
    }

    def remove(key: A) = underlying transformIfDefined {
      case m if m.contains(key) => m - key
    }

    def iterator = JavaConversions.asJavaIterator(underlying().valuesIterator)

    def getKeys = JavaConversions.asJavaSet(underlying().keySet)

    def getRange(minKey: A, maxKey: A) = JavaConversions.asJavaIterable(underlying().range(minKey, maxKey).values)
  }
}