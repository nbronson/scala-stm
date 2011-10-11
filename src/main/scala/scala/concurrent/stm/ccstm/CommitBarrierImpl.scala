/* scala-stm - (c) 2009-2011, Stanford University, PPL */

package scala.concurrent.stm
package ccstm

import actors.threadpool.TimeUnit

private[ccstm] object CommitBarrierImpl {
  object MemberCancelException extends Exception

  sealed abstract class State
  case object Active extends State
  case object MemberWaiting extends State
  case class Cancelled(cause: CommitBarrier.CancelCause) extends State
  case object Committed extends State
}

private[ccstm] class CommitBarrierImpl(timeoutNanos: Long) extends CommitBarrier {
  import CommitBarrier._
  import CommitBarrierImpl._

  private val lock = new Object

  /** The number of non-cancelled members. */
  private var activeCount = 0

  /** The number of members pending a commit decision. */
  private var waitingCount = 0

  /** If non-empty, all present and future members have been cancelled. */
  private var groupState: State = Active

  class MemberImpl(var executor: TxnExecutor) extends Member with Txn.ExternalDecider {

    @volatile private var state: State = Active

    def commitBarrier = CommitBarrierImpl.this

    def atomic[Z](body: InTxn => Z): Either[Z, CancelCause] = {
      try {
        executor { implicit txn =>
          if (state != Active) {
            Txn.rollback(Txn.UncaughtExceptionCause(MemberCancelException))
          }
          Txn.setExternalDecider(this)
          txn.asInstanceOf[InTxnImpl].commitBarrier = commitBarrier
          Left(body(txn))
        }
      } catch {
        case x => {
          state match {
            case Active => {
              // txn rolled back before involving external decider, all
              // members must be rolled back
              cancelAll(MemberUncaughtExceptionCause(x))
              throw x
            }
            case MemberWaiting => {
              // interrupt during ExternalDecider, all already cancelled
              assert(x.isInstanceOf[InterruptedException])
              assert(groupState.isInstanceOf[Cancelled])
              throw x
            }
            case Cancelled(cause) => {
              // barrier state already updated
              Right(cause)
            }
            case Committed => {
              // control flow exception as part of group commit, tricky!
              throw x
            }
          }
        }
      }
    }

    def cancel(cause: UserCancel) {
      cancelImpl(cause)
    }

    private[CommitBarrierImpl] def cancelImpl(cause: CancelCause) {
      lock.synchronized {
        val firstCause = groupState match {
          case c: Cancelled => c
          case _ => Cancelled(cause)
        }

        state match {
          case Active => {
            state = firstCause
            activeCount -= 1
            checkBarrierCommit_nl()
          }
          case MemberWaiting => {
            state = firstCause
            activeCount -= 1
            waitingCount -= 1
          }
          case Cancelled(_) => {}
          case Committed => {
            throw new IllegalStateException("can't cancel member after commit")
          }
        }
      }
    }

    def shouldCommit(implicit txn: InTxnEnd): Boolean = {
      var cause = lock.synchronized { shouldCommit_nl() }
      if (cause != null)
        txn.rollback(cause)
      true
    }

    private def shouldCommit_nl(): Txn.RollbackCause = {
      if (state == Active) {
        state = MemberWaiting
        waitingCount += 1
        checkBarrierCommit_nl()
      }

      var t0 = 0L

      (while (true) {
        // cancelImpl is a no-op if we're already cancelled
        groupState match {
          case Cancelled(cause) => cancelImpl(cause)
          case Committed if state == MemberWaiting => {
            state = Committed
            return null
          }
          case _ => {}
        }

        state match {
          case Cancelled(_) => return Txn.UncaughtExceptionCause(MemberCancelException)
          case MemberWaiting => {}
          case _ => throw new Error("impossible state " + state)
        }

        // wait
        try {
          if (timeoutNanos < Long.MaxValue) {
            val now = System.nanoTime()
            if (t0 == 0) {
              t0 = now
            }

            val remaining = (t0 + timeoutNanos) - now
            if (remaining <= 0) {
              cancelAll(Timeout)
              cancelImpl(Timeout)
              return Txn.UncaughtExceptionCause(MemberCancelException)
            } else {
              val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
              val nanos = remaining - TimeUnit.MILLISECONDS.toNanos(millis)
              lock.wait(millis, nanos.asInstanceOf[Int])
            }
          } else {
            lock.wait()
          }
        } catch {
          case x: InterruptedException => {
            // don't cancel ourself so we can throw InterruptedException
            cancelAll(MemberUncaughtExceptionCause(x))
            return Txn.UncaughtExceptionCause(x)
          }
        }
      }).asInstanceOf[Nothing]
    }
  }

  private def checkBarrierCommit_nl() {
    groupState match {
      case Active => {
        if (activeCount == waitingCount && activeCount > 0) {
          groupState = Committed
          lock.notifyAll()
        }
      }
      case _ => {}
    }
  }

  private[ccstm] def cancelAll(cause: CancelCause) {
    lock.synchronized {
      groupState match {
        case Active => {
          groupState = Cancelled(cause)
          lock.notifyAll()
        }
        case Cancelled(_) => {}
        case _ => throw new Error("impossible groupState " + groupState)
      }
    }
  }

  def addMember(cancelOnLocalRollback: Boolean)(implicit txn: MaybeTxn): Member = {
    lock.synchronized {
      groupState match {
        case Cancelled(_) => throw new IllegalStateException("commit barrier has already rolled back")
        case Committed => throw new IllegalStateException("commit barrier has already committed")
        case _ => activeCount += 1
      }
    }

    val m = new MemberImpl(atomic)

    if (cancelOnLocalRollback) {
      for (txn <- Txn.findCurrent)
        Txn.afterRollback({ _ => m.cancelImpl(CreatingTxnRolledBack) })(txn)
    }

    m
  }
}