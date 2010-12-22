/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// BoostedMergedHashMap_Basic

package scala.concurrent.stm.experimental.impl

import scala.concurrent.stm.experimental.TMap
import scala.concurrent.stm.experimental.TMap.Bound
import scala.concurrent.stm.experimental.impl.BoostedHashMap.{OnDemandMap,LockHolder}
import java.util.concurrent.locks.{Lock, ReadWriteLock, ReentrantLock}
import java.util.concurrent.{TimeUnit, ConcurrentHashMap}
import scala.concurrent.stm.{RollbackError, TxnLocal, Txn}
import java.util.IdentityHashMap

object BoostedMergedHashMap_Basic {
  val LockTimeoutMillis = 10
  val UndoAndUnlockPriority = -10

  class Entry extends ReentrantLock {
    var value: AnyRef = null
  }

  /** A single mutex for reads and writes. */
  class BasicMergedLockHolder[A] extends OnDemandMap[A,Entry] with LockHolder[A] {
    def newValue(key: A) = new Entry

    override def existingReadLock(key: A) = existing(key)
    override def existingWriteLock(key: A) = existing(key)
    def readLock(key: A) = this(key)
    def writeLock(key: A) = this(key)
  }

  trait MergedTxnContext[A] {
    def lockForRead(key: A): Lock
    def lockForWrite(key: A): Lock
    def lockForEnumeration()
    def lockForMembershipChange()
    def undo(e: Entry, v: AnyRef)
  }

  class MergedMapBooster[A](lockHolder: LockHolder[A], enumLock: Lock, mcLock: Lock) {

    /** Holds the current context. */
    private val currentContext = new TxnLocal[MergedTxnContext[A]] {
      override def initialValue(txn: Txn): MergedTxnContext[A] = {
        val result = new MergedTxnContext[A] with (Txn => Unit) with Txn.WriteResource {
          // per-txn state
          private val owned = new IdentityHashMap[Lock,Lock]
          private var undoCount = 0
          private var undoData: Array[AnyRef] = null

          def lockForRead(key: A) = {
            val m = lockHolder.readLock(key)
            acquire(m, key)
            m
          }

          def lockForWrite(key: A) = {
            val m = lockHolder.writeLock(key)
            acquire(m, key)
            m
          }

          def lockForEnumeration() {
            if (null == enumLock) throw new UnsupportedOperationException("no enumeration lock present")
            acquire(enumLock, "enum")
          }

          def lockForMembershipChange() {
            if (null != mcLock) acquire(mcLock, "member-change")
          }

          private def acquire(lock: Lock, info: Any) {
            if (null == owned.put(lock, lock)) {
              // wasn't previously present, acquire it
              if (!lock.tryLock(LockTimeoutMillis, TimeUnit.MILLISECONDS)) {
                // acquisition failed, deadlock?  remove before afterCompletion
                owned.remove(lock)
                txn.forceRollback(Txn.WriteConflictCause(info, "tryLock timeout"))
                throw RollbackError
              }
            }
          }

          def undo(e: Entry, v: AnyRef) {
            if (undoCount == 0) {
              undoData = new Array[AnyRef](16)
            } else if (undoCount * 2 == undoData.length) {
              undoData = java.util.Arrays.copyOf(undoData, undoData.length * 2)
            }
            undoData(2 * undoCount + 0) = e
            undoData(2 * undoCount + 1) = v
            undoCount += 1
          }

          def apply(t: Txn) {
            if (t.status != Txn.Committed) {
              // apply the undo log in reverse order
              var i = undoCount
              while (i > 0) {
                i -= 1
                val e = undoData(2 * i + 0).asInstanceOf[Entry]
                val v = undoData(2 * i + 1)
                e.value = v
              }
            }

            // release the locks
            var iter = owned.keySet().iterator
            while (iter.hasNext) iter.next.unlock()
          }

          // dummy write resource
          def prepare(txn: Txn): Boolean = true
          def performCommit(txn: Txn) {}
          def performRollback(txn: Txn) {}
        }

        // We add ourself as a dummy write resource to prevent the read-only
        // commit optimization.  This is because the linearization point must
        // occur at some point while we have all of the locks held, which does
        // not include the virtual readVersion snapshot.
        txn.addWriteResource(result)

        txn.afterCompletion(result, UndoAndUnlockPriority)
        result
      }
    }

    def context(implicit txn: Txn): MergedTxnContext[A] = currentContext.get
  }
}


class BoostedMergedHashMap_Basic[A,B] extends TMap[A,B] {
  import BoostedMergedHashMap_Basic._

  private val lockHolder = new BasicMergedLockHolder[A]
  private val booster = new MergedMapBooster[A](lockHolder, null, null)

  val escaped: TMap.Bound[A,B] = new TMap.AbstractNonTxnBound[A,B,BoostedMergedHashMap_Basic[A,B]](this) {

    def get(key: A): Option[B] = {
      val lock = lockHolder.existingReadLock(key)
      if (null == lock) {
        None
      } else {
        lock.lock()
        try {
          NullValue.decodeOption(lock.asInstanceOf[Entry].value)
        } finally {
          lock.unlock()
        }
      }
    }

    override def put(key: A, value: B): Option[B] = {
      val lock = lockHolder.writeLock(key)
      lock.lock()
      val e = lock.asInstanceOf[Entry]
      val prev = e.value
      e.value = NullValue.encode(value)
      lock.unlock()
      NullValue.decodeOption(prev)
    }

    override def remove(key: A): Option[B] = {
      val lock = lockHolder.existingWriteLock(key)
      if (null == lock) return None
      
      lock.lock()
      val e = lock.asInstanceOf[Entry]
      val prev = e.value
      e.value = null
      lock.unlock()
      NullValue.decodeOption(prev)
    }

    def iterator: Iterator[(A,B)] = new Iterator[(A,B)] {
      val iter = lockHolder.underlying.keySet().iterator
      var avail: (A,B) = null
      advance()

      private def advance() {
        while (iter.hasNext) {
          val k = iter.next()
          get(k) match {
            case Some(v) => {
              avail = (k,v)
              return
            }
            case None => // keep looking
          }
        }
        avail = null
      }

      def hasNext: Boolean = null != avail
      def next(): (A,B) = {
        val z = avail
        advance()
        z
      }
    }
  }

  def bind(implicit txn0: Txn): TMap.Bound[A,B] = new TMap.AbstractTxnBound[A,B,BoostedMergedHashMap_Basic[A,B]](txn0, this) {
    def iterator: Iterator[(A,B)] = throw new UnsupportedOperationException
  }

  def isEmpty(implicit txn: Txn): Boolean = throw new UnsupportedOperationException

  def size(implicit txn: Txn): Int = throw new UnsupportedOperationException

  def get(key: A)(implicit txn: Txn): Option[B] = {
    val ctx = booster.context
    val e = ctx.lockForRead(key).asInstanceOf[Entry]
    NullValue.decodeOption(e.value)
  }

  override def put(key: A, value: B)(implicit txn: Txn): Option[B] = {
    val ctx = booster.context
    val e = ctx.lockForWrite(key).asInstanceOf[Entry]
    val prev = e.value
    e.value = NullValue.encode(value)
    ctx.undo(e, prev)
    NullValue.decodeOption(prev)
  }

  override def remove(key: A)(implicit txn: Txn): Option[B] = {
    val ctx = booster.context
    val e = ctx.lockForWrite(key).asInstanceOf[Entry]
    val prev = e.value
    e.value = null
    ctx.undo(e, prev)
    return NullValue.decodeOption(prev)
  }
}
