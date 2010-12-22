/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// CleanableRef

package scala.concurrent.stm.experimental.impl

import java.lang.ref._


private object CleanableRef {
  val CleaningThreads = {
    val min = System.getProperty("cleaning-threads", "1").toInt
    var i = 1
    while (i < min) i *= 2
    i
  }

  private val queues = Array.tabulate(CleaningThreads)({ newQueue(_) })
  private def newQueue(i: Int) = {
    val queue = new ReferenceQueue[AnyRef]

    new Thread("CleanableRef cleaner #" + i) {
      setDaemon(true)

      override def run() {
        while (true) {
          try {
            val ref = queue.remove().asInstanceOf[CleanableRef[_]]
            ref.cleanup()
          } catch {
            case x => x.printStackTrace
          }
        }
      }
    }.start()

    queue
  }

  def myQueue[T] = {
    (if (queues.length == 1) {
      queues(0)
    } else {
      queues(System.identityHashCode(Thread.currentThread) & (CleaningThreads - 1))
    }).asInstanceOf[ReferenceQueue[T]]
  }
}

abstract class CleanableRef[T](value: T) extends SoftReference[T](value, CleanableRef.myQueue[T]) {
  def cleanup()
}
