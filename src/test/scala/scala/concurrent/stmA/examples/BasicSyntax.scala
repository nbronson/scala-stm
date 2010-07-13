/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stmA
package examples

object BasicSyntax {
  case class Point(x: Int, y: Int)
  val origin = Point(0, 0)
  
  val top = Ref(origin)
  val bottom = Ref(origin)
  val left = Ref(origin)
  val right = Ref(origin)

  def updateExtremes(p: Point) {
    atomic { implicit txn =>
      if (p.x < left().x) left() = p
      if (p.x > right().x) right() = p
      if (p.y < top().y) top() = p
      if (p.y > bottom().y) bottom() = p
    }
  }

  def snapshot = atomic.withHint("readOnly" -> true) { implicit txn =>
    List(top(), bottom(), left(), right())
  }

  def overspecifiedSnapshotAttempt = atomic.
          withConfig("maxRetries" -> 1).
          withHint(("readOnly" -> true), ("readSetSize" -> 4), ("nestingDepth" -> 1)) {
      implicit txn =>
    List(top(), bottom(), left(), right())
  }

  val customAtomic = atomic.withConfig("irrevocable" -> true)

  def fireMissileAt(p: Point) { println("launch " + p) }

  def removeCorners = customAtomic { implicit txn =>
    // fire a missile at any point that is on the corner of the bounding box
    val pts = snapshot
    for (p <- Set.empty ++ pts; if pts.count(_ == p) > 1)
      fireMissileAt(p)
  }
}