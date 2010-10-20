/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package skel

import collection.mutable.ArrayBuffer

abstract class CallbackList[A] {
  private var frozen = false
  protected val handlers = new ArrayBuffer[A => Unit]

  def isEmpty: Boolean = handlers.isEmpty

  def += (handler: A => Unit) {
    if (frozen)
      throw new IllegalStateException
    handlers += handler
  }

  def ++= (rhs: CallbackList[A]) {
    rhs.freeze()
    if (!rhs.isEmpty)
      this += { arg => rhs.fire(arg) }
  }

  def fire(arg: A)

  def freeze() { frozen = true }
}
