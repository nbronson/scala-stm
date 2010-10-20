/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package skel

class FailureCallbackList[A](level: AbstractNestingLevel) extends CallbackList[A] {
  def fire(arg: A) {
    freeze()
    if (handlers != null) {
      // fire in reverse order
      var i = handlers.size - 1
      while (i >= 0) {
        val h = handlers(i)
        try {
          h(arg)
        } catch {
          // after-rollback exceptions go to the generic handler
          case x => {
            try {
              level.txn.executor.postDecisionFailureHandler(level.status, x)
            } catch {
              case xx => xx.printStackTrace
            }
          }
        }
        i -= 1
      }
    }
  }
}
