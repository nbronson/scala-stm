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
}