/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// BoostedHashMap

package scala.concurrent.stm
package experimental
package impl

import java.util.IdentityHashMap
import java.util.concurrent.locks._
import java.util.concurrent.{ConcurrentMap, TimeUnit, ConcurrentHashMap}
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import skel.{TMapViaClone, SimpleRandom}

/** An transactionally boosted `ConcurrentHashMap`, implemented
 *  directly from M. Herlify and E. Koskinen, <em>Transactional Boosting: A
 *  Methodology for Highly-Concurrent Transactional Objects</em>, PPoPP 2008.
 *  <p>
 *  The basic boosted map does not support transactional iteration or size,
 *  does not garbage collect keys, and uses mutexes to guard both read and
 *  write access (preventing concurrent reads of the same key).
 *  <p>
 *  The current implementation uses ReentrantLock-s internally, so it does not
 *  protect against concurrent access by multiple Txn (or escaped) that happen
 *  to share a thread.  Most STMs have an implicit association between the
 *  current transaction context and the current thread, but this is not present
 *  in CCSTM.
 *
 *  @author Nathan Bronson
 *
 *  @see scala.concurrent.stm.experimental.impl.BoostedHashMap_GC
 *  @see scala.concurrent.stm.experimental.impl.BoostedHashMap_GC_RW
 *  @see scala.concurrent.stm.experimental.impl.BoostedHashMap_GC_Enum
 *  @see scala.concurrent.stm.experimental.impl.BoostedHashMap_GC_Enum_RW
 */
class BoostedHashMap_Basic[A,B] extends BoostedHashMap[A,B](new BoostedHashMap.BasicLockHolder[A], null)

/** Adds an abstract lock to coordinate transactional enumeration to
 *  `BoostedHashMap_Basic`.  The best implementation of this lock
 *  would use a multi-mode lock where enumeration was mode S and size change IX
 *  (see Y. Ni, V. Menon, A. Adl-Tabatabai, A. Hosking, R. Hudson, J. Moss, B.
 *  Saha, and T. Shpeisman, <em>Open Nesting in Software Transactional
 *  Memory</em>, PPoPP 2007).  Since we don't have a multi-mode lock handy, we
 *  use a ReentrantReadWriteLock in read mode during membership changes and in
 *  write mode during enumeration.  This yields no concurrency during
 *  enumeration, so be careful not to benchmark that.
 */
class BoostedHashMap_Enum[A,B] extends BoostedHashMap[A,B](new BoostedHashMap.BasicLockHolder[A], new ReentrantReadWriteLock)

/** Adds garbage collection of unused abstract locks to
 *  `BoostedHashMap_Basic`.  Uses weak references.
 */
class BoostedHashMap_GC[A,B] extends BoostedHashMap[A,B](new BoostedHashMap.GCLockHolder[A], null)

/** Uses read/write locks to guard access to keys, rather than the mutexes of
 *  `BoostedHashMap_Basic`.  This potentially allows greater
 *  concurrency during reads, but may have higher overheads.
 */
class BoostedHashMap_RW[A,B] extends BoostedHashMap[A,B](new BoostedHashMap.RWLockHolder[A], null)

class BoostedHashMap_Enum_RW[A,B] extends BoostedHashMap[A,B](new BoostedHashMap.RWLockHolder[A], new ReentrantReadWriteLock)
class BoostedHashMap_GC_RW[A,B] extends BoostedHashMap[A,B](new BoostedHashMap.GCRWLockHolder[A], null)
class BoostedHashMap_GC_Enum[A,B] extends BoostedHashMap[A,B](new BoostedHashMap.GCLockHolder[A], new ReentrantReadWriteLock)
class BoostedHashMap_GC_Enum_RW[A,B] extends BoostedHashMap[A,B](new BoostedHashMap.GCRWLockHolder[A], new ReentrantReadWriteLock)

class BoostedHashMap_RC[A,B] extends BoostedHashMap[A,B](new BoostedHashMap.RCLockHolder[A], null)
class BoostedHashMap_RC_Enum[A,B] extends BoostedHashMap[A,B](new BoostedHashMap.RCLockHolder[A], new ReentrantReadWriteLock)
class BoostedHashMap_LazyGC[A,B] extends BoostedHashMap[A,B](new BoostedHashMap.LazyLockHolder[A], null)
class BoostedHashMap_LazyGC_Enum[A,B] extends BoostedHashMap[A,B](new BoostedHashMap.LazyLockHolder[A], new ReentrantReadWriteLock)

object BoostedHashMap {

  val UndoAndUnlockPriority = -10

  //////// on-demand management of keys, without and with GC

  abstract class OnDemandMap[A,B <: AnyRef] {
    val underlying = new ConcurrentHashMap[A,B]

    def newValue(key: A): B

    def existing(key: A): B = underlying.get(key)

    def apply(key: A): B = {
      val existing = underlying.get(key)
      if (null != existing) {
        existing
      } else {
        val fresh = newValue(key)
        val race = underlying.putIfAbsent(key, fresh)
        if (null != race) race else fresh
      }
    }
  }

  class SoftRef[A,B](key: A, owner: ConcurrentHashMap[A,SoftRef[A,B]], value: B) extends CleanableRef[B](value) {
    def cleanup() {
      owner.remove(key, this)
    }
  }

  abstract class WeakOnDemandMap[A,B <: AnyRef] {
    private val underlying = new ConcurrentHashMap[A,SoftRef[A,B]]

    def newValue(key: A): B

    def existing(key: A): B = {
      val r = underlying.get(key)
      if (null == r) null.asInstanceOf[B] else r.get
    }

    def apply(key: A): B = {
      var result: B = null.asInstanceOf[B]
      do {
        val ref = underlying.get(key)
        if (null != ref) {
          result = ref.get
          if (null == result) {
            val freshValue = newValue(key)
            val freshRef = new SoftRef(key, underlying, freshValue)
            if (underlying.replace(key, ref, freshRef)) {
              result = freshValue
            }
          }
        } else {
          val freshValue = newValue(key)
          val freshRef = new SoftRef(key, underlying, freshValue)
          val existing = underlying.putIfAbsent(key, freshRef)
          result = (if (null == existing) freshValue else existing.get)
        }
      } while (null == result)
      result
    }
  }

  //////// per-key lock management strategies

  trait LockHolder[A] {
    // for a lock holder that performs GC, existingReadLock and
    // existingWriteLock must forward to readLock and writeLock 
    def existingReadLock(key: A): Lock = readLock(key)
    def existingWriteLock(key: A): Lock = writeLock(key)
    def readLock(key: A): Lock
    def writeLock(key: A): Lock
    def returnUnused(lock: Lock) {}
  }

  /** A single mutex for reads and writes. */
  class BasicLockHolder[A] extends OnDemandMap[A,Lock] with LockHolder[A] {
    def newValue(key: A) = new ReentrantLock

    override def existingReadLock(key: A) = existing(key)
    override def existingWriteLock(key: A) = existing(key)
    def readLock(key: A) = this(key)
    def writeLock(key: A) = this(key)
  }

  /** A read/write lock per key, without garbage collection. */
  class RWLockHolder[A] extends OnDemandMap[A,ReadWriteLock] with LockHolder[A] {
    def newValue(key: A) = new ReentrantReadWriteLock

    override def existingReadLock(key: A) = existing(key).readLock
    override def existingWriteLock(key: A) = existing(key).writeLock
    def readLock(key: A) = this(key).readLock
    def writeLock(key: A) = this(key).writeLock
  }

  /** A single mutex for reads and writes, with garbage collection. */
  class GCLockHolder[A] extends WeakOnDemandMap[A,Lock] with LockHolder[A] {
    def newValue(key: A) = new ReentrantLock

    def readLock(key: A) = this(key)
    def writeLock(key: A) = this(key)
  }

  /** A read/write lock per key, with garbage collection. */
  class GCRWLockHolder[A] extends WeakOnDemandMap[A,ReadWriteLock] with LockHolder[A] {
    def newValue(key: A) = new ReentrantReadWriteLock

    def readLock(key: A) = this(key).readLock
    def writeLock(key: A) = this(key).writeLock
  }

  val refCountUpdater = (new RCLock[AnyRef](null, null)).newUpdater

  class RCLock[A](key: A, underlying: ConcurrentMap[A,RCLock[A]]) extends ReentrantLock {
    def newUpdater = AtomicIntegerFieldUpdater.newUpdater(classOf[RCLock[_]], "refCount")

    @volatile var refCount = 0
    def refCountCAS(before: Int, after: Int) = refCountUpdater.compareAndSet(this, before, after)

    def enter(): RCLock[A] = {
      while (true) {
        val rc = refCount
        if (rc == -1) {
          // this entry is stale, it must be replaced
          val fresh = new RCLock[A](key, underlying)
          fresh.refCount = 1
          if (underlying.replace(key, this, fresh)) {
            // pre-entered
            return fresh
          }
          val race = underlying.putIfAbsent(key, fresh)
          if (null == race) {
            return fresh
          }
          return race.enter()
        }
        if (refCountCAS(rc, rc + 1)) {
          // success
          return this
        }
      }
      throw new Error("unreachable")
    }

    def exit() {
      while (true) {
        val rc = refCount
        if (rc == 1 && refCountCAS(1, -1)) {
          // We have made this entry stale, remove it.  Ignore failure
          underlying.remove(key, this)
          return
        }
        if (refCountCAS(rc, rc - 1)) {
          // success
          return
        }
      }
    }

    override def tryLock(timeout: Long, unit: TimeUnit): Boolean = {
      if (!super.tryLock(timeout, unit)) {
        exit()
        false
      } else {
        true
      }
    }

    override def unlock(): Unit = {
      super.unlock()
      exit()
    }
  }

  class RCLockHolder[A] extends OnDemandMap[A,RCLock[A]] with LockHolder[A] {
    def newValue(key: A) = new RCLock(key, underlying)

    def readLock(key: A) = this(key).enter()
    def writeLock(key: A) = this(key).enter()
    override def returnUnused(lock: Lock) = lock.asInstanceOf[RCLock[A]].exit()
  }

  
  trait LockRef {
    def get: Lock
    def enter(): Lock
  }

  class LazyLock[A](key: A, underlying: ConcurrentMap[A,LockRef]) extends ReentrantLock with LockRef {
    @volatile var weakened = false

    def get = this

    def enter(): Lock = {
      val weak = new WeakLockRef(key, underlying, this)
      if (underlying.replace(key, this, weak)) {
        weakened = true
        this
      } else {
        null
      }
    }

    def exit() {
      if (!weakened) {
        underlying.remove(key, this)
      }
    }

    override def tryLock(timeout: Long, unit: TimeUnit): Boolean = {
      if (!super.tryLock(timeout, unit)) {
        exit()
        false
      } else {
        true
      }
    }

    override def unlock(): Unit = {
      super.unlock()
      exit()
    }
  }

  class WeakLockRef[A](key: A, underlying: ConcurrentMap[A,LockRef], lock: Lock) extends CleanableRef(lock) with LockRef {
    def enter() = get

    def cleanup() {
      underlying.remove(key, this)
    }
  }

  class LazyLockHolder[A] extends LockHolder[A] {
    val underlying = new ConcurrentHashMap[A,LockRef]

    def readLock(key: A) = get(key)
    def writeLock(key: A) = get(key)

    private def get(key: A): Lock = {
      var existing = underlying.get(key)
      if (null == existing) {
        // new locks are pre-enter()ed
        val fresh = new LazyLock(key, underlying)
        existing = underlying.putIfAbsent(key, fresh)
        if (null == existing) {
          return fresh
        }
      }
      val lock = existing.enter()
      if (null != lock) {
        return lock
      }
      return get(key)
    }
  }


  //////// per-txn state

  trait TxnContext[A] {
    def lockForRead(key: A): Lock
    def lockForWrite(key: A): Lock
    def lockForEnumeration()
    def lockForMembershipChange()
    def undo[C <: AnyRef](underlying: ConcurrentHashMap[A,C], key: A, valueOrNull: C)
  }

  object EmptyHandler extends (InTxnEnd => Unit) {
    def apply(txn: InTxnEnd) {}
  }

  class MapBooster[A](lockHolder: LockHolder[A], enumLock: Lock, mcLock: Lock) {

    /** Holds the number of retries. */
    private val retryCount = new ThreadLocal[Int]

    /** Holds the current context. */
    private val currentContext = TxnLocal[TxnContext[A]](
      initialValue = { implicit txn =>
        val z = new TxnContext[A] with (Txn.Status => Unit) {
          // per-txn state
          private val owned = new IdentityHashMap[Lock,Lock]
          private var undoCount = 0
          private var undoData: Array[Any] = null

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
              if (!lock.tryLock()) {
                // acquisition failed, deadlock?  remove before afterCompletion
                owned.remove(lock)
                txn.rollback(Txn.OptimisticFailureCause('tryLock_failed, Some(info)))
              }
            } else {
              lockHolder.returnUnused(lock)
            }
          }

          def undo[C <: AnyRef](underlying: ConcurrentHashMap[A,C], key: A, valueOrNull: C) {
            if (undoCount == 0) {
              undoData = new Array[Any](24)
            } else if (undoCount * 3 == undoData.length) {
              val aa = new Array[Any](undoData.length * 2)
              System.arraycopy(undoData, 0, aa, 0, undoData.length)
              undoData = aa
            }
            undoData(3 * undoCount + 0) = underlying
            undoData(3 * undoCount + 1) = key
            undoData(3 * undoCount + 2) = valueOrNull
            undoCount += 1
          }

          def apply(status: Txn.Status) {
            if (status != Txn.Committed) {
              // apply the undo log in reverse order
              var i = undoCount
              while (i > 0) {
                i -= 1
                val u = undoData(3 * i + 0).asInstanceOf[ConcurrentHashMap[A,AnyRef]]
                val k = undoData(3 * i + 1).asInstanceOf[A]
                val vo = undoData(3 * i + 2).asInstanceOf[AnyRef]
                if (null == vo) u.remove(k) else u.put(k, vo)
              }
            } else {
              retryCount.set(0)
            }

            // release the locks
            var iter = owned.keySet().iterator
            while (iter.hasNext) iter.next.unlock()

            // randomized exponential backoff if we are going to rerun
            if (status != Txn.Committed) {
              val r = retryCount.get
              retryCount.set(r + 1)

              // At full tilt, we can execute several million txns per second
              // on a thread.  This means that Thread.sleep(), which rounds up
              // to a millisecond, is totally inappropriate.  Thread.yield()
              // takes on the order of a microsecond (300 nanos on my dual-core
              // laptop), so we use it instead.  The starting point is an
              // expected backoff of 500 nanoseconds.
              val maxNanos = 1000 << math.min(r, 14)
              val delay = SimpleRandom.nextInt(maxNanos)
              val t0 = System.nanoTime
              while (System.nanoTime < t0 + delay) Thread.`yield`
            }
          }
        }
        // disable the read-only commit optimization
        Txn.whilePreparing(EmptyHandler)
        Txn.afterCompletion(z)
        z
      })

    def context(implicit txn: InTxn): TxnContext[A] = currentContext.get
  }
}

class BoostedHashMap[A,B](lockHolder: BoostedHashMap.LockHolder[A], enumLock: ReadWriteLock) extends AbstractTMap[A, B] {
  import BoostedHashMap._

  private val underlying = new java.util.concurrent.ConcurrentHashMap[A,AnyRef]
  private val booster = new MapBooster[A](lockHolder,
                                          if (null == enumLock) null else enumLock.writeLock,
                                          if (null == enumLock) null else enumLock.readLock)

  //// TMap.View stuff

  def nonTxnGet(key: A): Option[B] = {
    // lockHolder implementations that garbage collect locks cannot perform
    // the existingReadLock optimization
    val lock = lockHolder.existingReadLock(key)
    if (lock == null) {
      None
    } else {
      lock.lock()
      try {
        NullValue.decodeOption(underlying.get(key))
      } finally {
        lock.unlock()
      }
    }
  }

  def nonTxnPut(key: A, value: B): Option[B] = {
    val lock = lockHolder.writeLock(key)
    lock.lock()
    val prev = (try {
      if (null != enumLock && !underlying.containsKey(key)) {
        enumLock.readLock.lock()
        try {
          underlying.put(key, NullValue.encode(value))
        } finally {
          enumLock.readLock.unlock()
        }
      } else {
        underlying.put(key, NullValue.encode(value))
      }
    } finally {
      lock.unlock()
    })
    NullValue.decodeOption(prev)
  }

  def nonTxnRemove(key: A): Option[B] = {
    val lock = lockHolder.existingWriteLock(key)
    if (null == lock) return None

    lock.lock()
    val prev = (try {
      if (null != enumLock) {
        if (!underlying.containsKey(key)) {
          null
        } else {
          enumLock.readLock.lock()
          try {
            underlying.remove(key)
          } finally {
            enumLock.readLock.unlock()
          }
        }
      } else {
        underlying.remove(key)
      }
    } finally {
      lock.unlock()
    })
    return NullValue.decodeOption(prev)
  }

  //// TMap stuff

  override def get(key: A)(implicit txn: InTxn): Option[B] = {
    val ctx = booster.context
    ctx.lockForRead(key)
    NullValue.decodeOption(underlying.get(key))
  }

  override def put(key: A, value: B)(implicit txn: InTxn): Option[B] = {
    val ctx = booster.context
    ctx.lockForWrite(key)
    if (null != enumLock && !underlying.containsKey(key)) {
      ctx.lockForMembershipChange()
    }
    val prev = underlying.put(key, NullValue.encode(value))
    ctx.undo(underlying, key, prev)
    NullValue.decodeOption(prev)
  }

  override def remove(key: A)(implicit txn: InTxn): Option[B] = {
    val ctx = booster.context
    ctx.lockForWrite(key)
    if (null != enumLock) {
      if (!underlying.containsKey(key)) {
        return None
      }
      ctx.lockForMembershipChange()
    }
    val prev = underlying.remove(key)
    ctx.undo(underlying, key, prev)
    return NullValue.decodeOption(prev)
  }
}

