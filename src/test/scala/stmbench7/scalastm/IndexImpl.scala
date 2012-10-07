/* scala-stm - (c) 2009-2012, Stanford University, PPL */

package stmbench7.scalastm

import scala.annotation._
import scala.collection.immutable.{RedBlack, TreeMap}
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions
import scala.concurrent.stm._
import stmbench7.backend.Index

object IndexImpl {
  class BoxedImmutable[A <: Comparable[A], B] extends Index[A,B] {
    // needed for 2.8, harmless in 2.9
    implicit val order = new Ordering[A] {
      def compare(lhs: A, rhs: A) = lhs compareTo rhs
    }

    val underlying = Ref(TreeMap.empty[A,B]).single

    def get(key: A) = underlying().getOrElse(key, null.asInstanceOf[B])

    def put(key: A, value: B) { underlying.transform(_ + (key -> value)) }

    def putIfAbsent(key: A, value: B): B = {
      underlying.getAndTransform({ m => if (m.contains(key)) m else m + (key -> value) }).getOrElse(key, null.asInstanceOf[B])
    }

    def remove(key: A) = underlying transformIfDefined {
      case m if m.contains(key) => m - key
    }

    def iterator = makeValuesIterator(underlying())

    def getKeys = JavaConversions.asJavaSet(underlying().keySet)

    def getRange(minKey: A, maxKey: A) = new java.lang.Iterable[B] {
      val range = underlying().range(minKey, maxKey)
      def iterator = makeValuesIterator(range)
    }

    // <hack>We implement our own iterator because the Scala one generates
    // a large quantity of garbage.</hack>
    private def makeValuesIterator(m: TreeMap[A, B]) = {
      val root = treeMapTreeField.get(m).asInstanceOf[RedBlack[A]#Tree[B]]
      new java.util.Iterator[B] {
        val avail = new ArrayBuffer[RedBlack[A]#NonEmpty[B]] // values ready to return
        pushAll(root)

        @tailrec final def pushAll(n: RedBlack[A]#Tree[B]) {
          n match {
            case ne: RedBlack[A]#NonEmpty[B] => {
              avail += ne
              pushAll(ne.left)
            }
            case _ =>
          }
        }

        def hasNext = !avail.isEmpty

        def next(): B = {
          val n = avail.remove(avail.size - 1)
          pushAll(n.right)
          n.value
        }

        def remove() = throw new UnsupportedOperationException
      }
    }
  }

  private val treeMapTreeField = {
    val f = classOf[TreeMap[String, String]].getDeclaredField("tree")
    f.setAccessible(true)
    f
  }
}
