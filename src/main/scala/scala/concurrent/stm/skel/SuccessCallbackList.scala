/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package skel

class SuccessCallbackList[A](level: AbstractNestingLevel) extends CallbackList[A] {
  def fire(arg: A) {
    if (handlers != null) {
      // our looping style tolerates += during firing
      var i = 0
      while (i < handlers.size && level.status == Txn.Active) {
        val h = handlers(i)
        try {
          h(arg)
        } catch {
          // no exceptions from a handler can be control flow
          case x => {
            val s = level.requestRollback(Txn.UncaughtExceptionCause(x))
            if (s == Txn.Committing || s == Txn.Committed) {
              try {
                level.txn.executor.postDecisionFailureHandler(level.status, x)
              } catch {
                case xx => xx.printStackTrace
              }
            }
          }
        }
        i += 1
      }
    }
    freeze()
  }
}
