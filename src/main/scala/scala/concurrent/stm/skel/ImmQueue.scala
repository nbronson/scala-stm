package scala.concurrent.stm.skel

class ImmQueue[A](left: Stream[A], right: Stream[A], forcing: Stream[A]) {

  def append(v: A) = makeq(left, v #:: right, forcing)
  def :+(v: A) = append(v)

  def head = left.head

  def tail = makeq(left.tail, right, forcing)

  private def makeq(left: Stream[A], right: Stream[A], forcing: Stream[A]): ImmQueue[A] = {
    if (forcing.isEmpty) {
      val newLeft = rot(left, right, Stream.empty[A])
      new ImmQueue(newLeft, Stream.empty[A], newLeft)
    } else {
      new ImmQueue(left, right, forcing.tail)
    }
  }

  private def rot(left: Stream[A], right: Stream[A], accum: Stream[A]): Stream[A] = {
    if (left.isEmpty) {
      right.head #:: accum
    } else {
      left.head #:: rot(left.tail, right.tail, right.head #:: accum)
    }
  }
}

object ImmQueue {

}