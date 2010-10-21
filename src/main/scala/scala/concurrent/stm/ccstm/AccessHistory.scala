/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm.ccstm

private[impl] object AccessHistory {
  class UndoLog {
    var prevLog: UndoLog = null
    var prevReadCount = 0
    var prevWriteThreshold = 0

    private var _logSize = 0
    private var _indices: Array[Int] = null
    private var _prevValues: Array[AnyRef] = null

    final def logWrite(i: Int, v: AnyRef) {
      if (_indices == null || _logSize == _indices.length)
        grow()
      _indices(size) = i
      _prevValues(size) = v
      _logSize += 1
    }

    private def grow() {
      if (_logSize == 0) {
        _indices = new Array[Int](16)
        _prevValues = new Array[AnyRef](16)
      } else {
        _indices = java.util.Arrays.copyOf(_indices, _indices.length * 2)
        _prevValues = java.util.Arrays.copyOf(_prevValues, _prevValues.length * 2)
      }
    }

    final def undoWrites(hist: AccessHistory) {
      // it is important to apply in reverse order
      var i = _logSize - 1
      while (i >= 0) {
        hist.setSpecValue(_indices(i), _prevValues(i))
        i -= 1
      }
    }
  }
}

/** The `AccessHistory` includes the read set and the write buffer for all
 *  transaction levels that have not been rolled back.
 */
private[impl] class AccessHistory {
  import AccessHistory.UndoLog

  private var _undoLog: UndoLog = null

  protected def pushAccessHistory(top: UndoLog) {
    top.prevLog = _undoLog
    _undoLog = top
    checkpointReadSet()
    checkpointWriteBuffer()
  }

  protected def mergeAccessHistory() {
    mergeWriteBuffer()
    _undoLog = top._undoLog
  }

  /** Releases locks for discarded handles */
  protected def popAccessHistory(slot: CCSTM.Slot) {
    rollbackReadSet()
    rollbackWriteBuffer(slot)
    _undoLog = top._undoLog
  }

  /** Does not release locks */
  protected def resetAccessHistory() {
    resetReadSet()
    resetWriteBuffer()
    _undoLog = null
  }

  /** Adds to `accum` all handles that were read during this nesting level, and
   *  all handles written in the current level but not in a parent.
   */
  protected def accumulateRetrySet(accum: ReadSetBuilder) {
    accumulateReads(acum)
    accumulateWrites(accum)
  }

  //////////// read set

  private def InitialReadCapacity = 1024
  private def MaxRetainedReadCapacity = 8 * InitialReadCapacity

  private var _rCount = 0
  private var _rHandles = new Array[Handle[_]](InitialReadCapacity)
  private var _rVersions = new Array[CCSTM.Version](InitialReadCapacity)

  final protected def readCount = _rCount
  final protected def readHandle(i: Int): Handle[_] = _rHandles(i)
  final protected def readVersion(i: Int): CCSTM.Version = _rVersions(i)

  final protected def recordRead(handle: Handle[_], version: CCSTM.Version) {
    val i = _rCount
    if (i == _rHandles.length)
      growReadSet()
    _rHandles(i) = handle
    _rVersions(i) = version
    _rCount = i + 1
  }

  private def growReadSet() {
    _rHandles = java.util.Arrays.copyOf(_rHandles, _rHandles.length * 2)
    _rVersions = java.util.Arrays.copyOf(_rVersions, _rVersions.length * 2)
  }

  final protected def releaseRead(i: Int) {
    _rHandles(i) = null
  }

  private def checkpointReadSet() {
    _undoLog.prevReadCount = _rCount
  }

  private def rollbackReadSet() {
    val n = _undoLog.prevReadCount
    java.util.Arrays.fill(_rHandles, n, _rCount, null)
    _rCount = n
  }

  private def resetReadSet() {
    if (_rHandles.length > MaxRetainedCapacity) {
      // avoid staying very large
      _rHandles = new Array[Handle[_]](InitialReadCapacity)
      _rVersions = new Array[CCSTM.Version](InitialReadCapacity)
    } else {
      // allow GC of old handles
      java.util.Arrays.fill(_rHandles, 0, _rCount, null)
    }
    _rCount = 0
  }

  private def accumulateReads(accum: ReadSetBuilder) {
    var i = _undoLog.prevReadCount
    while (i < _rCount) {
      accum.add(_rHandles(i), _rVersions(i))
      i += 1
    }
  }

  

  //////////// write buffer

  private def InitialWriteCapacity = 8
  private def MinAllocatedWriteCapacity = 512
  private def MaxRetainedWriteCapacity = 8 * MinAllocatedWriteCapacity

  // This write buffer implementation uses chaining, but instead of storing the
  // buckets in objects, they are packed into the arrays bucketAnys and
  // bucketInts.  Pointers to a bucket are represented as a 0-based int, with
  // -1 representing nil.  The dispatch array holds the entry index for the
  // beginning of a bucket chain.
  //
  // When a nested context is created with push(), the current number of
  // allocated buckets is recorded in undoThreshold.  Any changes to the
  // speculative value for a bucket with an index less than this threshold are
  // logged to allow partial rollback.  Buckets with an index greater than the
  // undoThreshold can be discarded during rollback.  This means that despite
  // nesting, each <ref,offset> key is stored at most once in the write buffer.

  /** The maximum index for which undo entries are required. */
  private var _wUndoThreshold = 0

  private var _wCount = 0
  private var _wCapacity = InitialWriteCapacity
  private var _wAnys: Array[AnyRef] = null
  private var _wInts: Array[Int] = null
  private var _wDispatch: Array[Int] = null
  allocateWriteBuffer()

  private def allocateWriteBuffer() {
    _wAnys = new Array[AnyRef](bucketAnysLen(MinAllocatedWriteCapacity))
    _wInts = new Array[Int](bucketIntsLen(MinAllocatedWriteCapacity))
    _wDispatch = new Array[Int](MinAllocatedWriteCapacity)
    java.util.Arrays.fill(_wDispatch, 0, InitialWriteCapacity, -1)
  }

  @inline private def refI(i: Int) = 3 * i
  @inline private def specValueI(i: Int) = 3 * i + 1
  @inline private def handleI(i: Int) = 3 * i + 2
  @inline private def offsetI(i: Int) = 2 * i
  @inline private def nextI(i: Int) = 2 * i + 1 // bits 31..1 are the next, bit 0 is set iff freshOwner

  private def bucketAnysLen(c: Int) = 3 * (maxSizeForCap(c) + 1)
  private def bucketIntsLen(c: Int) = 2 * (maxSizeForCap(c) + 1)

  private def maxSizeForCap(c: Int) = c - c / 4
  private def shouldGrow(s: Int, c: Int): Boolean = s > maxSizeForCap(c)
  private def shouldGrow: Boolean = shouldGrow(_wCount, _wCapacity)
  private def shouldShrink(s: Int, c: Int): Boolean = c > InitialWriteCapacity && !shouldGrow(s, c / 4)
  private def shouldShrink: Boolean = shouldShrink(_wCount, _wCapacity)

  //////// accessors

  @inline private def getRef(i: Int) = _wAnys(refI(i))
  @inline final protected def getWriteHandle(i: Int) = _wAnys(handleI(i)).asInstanceOf[Handle[_]]
  @inline final protected def getWriteSpecValue[T](i: Int) = _wAnys(specValueI(i)).asInstanceOf[T]
  @inline private def getOffset(i: Int) = _wInts(offsetI(i))
  @inline private def getNext(i: Int): Int = _wInts(nextI(i)) >>> 1
  @inline final protected def wasWriteFreshOwner(i: Int): Boolean = (_wInts(nextI(i)) & 1) != 0

  @inline private def setRef(i: Int, r: AnyRef) { _wAnys(refI(i)) = r }
  @inline private def setHandle(i: Int, h: Handle[_]) { _wAnys(handleI(i)) = h }
  @inline final private[AccessHistory] def setSpecValue[T](i: Int, v: T) { _wAnys(specValueI(i)) = v.asInstanceOf[AnyRef] }
  @inline private def setOffset(i: Int, o: Int) { _wInts(offsetI(i)) = o }
  @inline private def setNextAndFreshOwner(i: Int, n: Int, freshOwner: Boolean) { _wInts(nextI(i)) = (n << 1) | (if (freshOwner) 1 else 0) }
  @inline private def setNext(i: Int, n: Int) { _wInts(nextI(i)) = (n << 1) | (_wInts(nextI(i)) & 1) }

  //////// bulk access

  final protected def writeCount = _wCount

  //////// reads

  /** Reads a handle that is owned by this InTxnImpl family. */
  final protected def stableGet[T](handle: Handle[T]): T = {
    val i = find(handle)
    if (i >= 0) {
      // hit
      getWriteSpecValue[T](i)
    } else {
      // miss (due to shared metadata)
      handle.data
    }
  }

  private def find(handle: Handle[_]): Int = {
    val ref = handle.ref
    val offset = handle.offset
    find(ref, offset, computeSlot(ref, offset))
  }

  private def computeSlot(ref: AnyRef, offset: Int): Int = {
    if (_wCapacity == InitialWriteCapacity) 0 else CCSTM.hash(ref, offset) & (_wCapacity - 1)
  }

  private def find(ref: AnyRef, offset: Int, slot: Int): Int = {
    var i = _wDispatch(slot)
    while (i >= 0 && ((ref ne getRef(i)) || offset != getOffset(i)))
      i = getNext(i)
    i
  }

  //////// writes

  final protected def put[T](handle: Handle[T], freshOwner: Boolean, value: T) {
    val ref = handle.ref
    val offset = handle.offset
    val slot = computeSlot(ref, offset)
    val i = find(ref, offset, slot)
    if (i >= 0) {
      //assert(!freshOwner)
      // hit, update an existing entry, optionally with undo
      if (i < _wUndoThreshold)
        _undoLog.logWrite(i, getWriteSpecValue(i))
      setSpecValue(i, value)
    } else {
      // miss, create a new entry
      append(ref, offset, handle, freshOwner, value, slot)
    }
  }

  final protected def allocatingGet[T](handle: Handle[T], freshOwner: Boolean): T = {
    return getWriteSpecValue[T](findOrAllocate(handle, freshOwner))
  }

  final protected def swap[T](handle: Handle[T], freshOwner: Boolean, value: T): T = {
    val i = findOrAllocate(handle, freshOwner)
    val before = getWriteSpecValue[T](i)
    setSpecValue(i, value)
    return before
  }

  final protected def getAndTransform[T](handle: Handle[T], freshOwner: Boolean, func: T => T): T = {
    val i = findOrAllocate(handle, freshOwner)
    val before = getWriteSpecValue[T](i)
    setSpecValue(i, func(before))
    return before
  }

  private def findOrAllocate(handle: Handle[_], freshOwner: Boolean): Int = {
    val ref = handle.ref
    val offset = handle.offset
    val slot = computeSlot(ref, offset)
    val i = find(ref, offset, slot)
    if (i >= 0) {
      //assert(!freshOwner) // TODO: remove
      // hit, undo log entry is required to capture the potential reads that
      // won't be recorded in this nested txn's read set
      if (i < _wUndoThreshold)
        _undoLog.logWrite(i, getWriteSpecValue(i))
      return i
    } else {
      // miss, create a new entry using the existing data value
      return append(ref, offset, handle, freshOwner, handle.data, slot)
    }
  }

  private def append(ref: AnyRef, offset: Int, handle: Handle[_], freshOwner: Boolean, value: Any, slot: Int): Int = {
    val i = _wCount
    setRef(i, ref)
    setSpecValue(i, value)
    setHandle(i, handle)
    setOffset(i, offset)
    setNextAndFreshOwner(i, _wDispatch(slot), freshOwner)
    _wDispatch(slot) = i
    _wCount = i + 1

    if (shouldGrow)
      grow()

    // grow() relinks the buckets but doesn't move them, so i is still valid
    return i
  }

  private def grow() {
    // adjust capacity
    _wCapacity *= 2
    if (_wCapacity > _wDispatch.length) {
      // we actually need to reallocate
      _wAnys = java.util.Arrays.copyOf(_wAnys, bucketAnysLen(_wCapacity))
      _wInts = java.util.Arrays.copyOf(_wInts, bucketIntsLen(_wCapacity))
      _wDispatch = new Array[Int](_wCapacity)
    }
    rebuildDispatch()
  }

  private def rebuildDispatch() {
    java.util.Arrays.fill(_wDispatch, 0, _wCapacity, -1)

    var i = 0
    while (i < _wCount) {
      val slot = computeSlot(getRef(i), getOffset(i))
      setNext(i, _wDispatch(slot))
      _wDispatch(slot) = i
      i += 1
    }
  }

  //////// nesting management and visitation

  private def checkpointWriteBuffer() {
    _undoLog.prevWriteThreshold = _wUndoThreshold
    _wUndoThreshold = _wCount
  }

  private def mergeWriteBuffer() {
    _wUndoThreshold = _undoLog.prevWriteThreshold
  }

  private def rollbackWriteBuffer(slot: CCSTM.Slot) {
    // restore the specValue-s modified in pre-existing write buffer entries
    _undoLog.undoWrites(this)

    var i = _wCount - 1
    val e = _wUndoThreshold
    val completeRelink = i >= 2 * e // faster to reprocess remaining entries?
    while (i >= e) {
      // unlock
      if (wasWriteFreshOwner(i)) {
        val h = getWriteHandle(i)

        // we must use CAS because there can be concurrent pendingWaiter adds
        // and concurrent "helpers" that release the lock
        var m0 = h.meta
        while (CCSTM.owner(m0) == slot && !h.metaCAS(m0, CCSTM.withRollback(m0)))
          m0 = h.meta
      }

      // unlink
      if (!completeRelink) {
        _wDispatch(computeSlot(getRef(i), getOffset(i))) = getNext(i)
      }

      // discard references to aid GC
      setRef(i, null)
      setSpecValue(i, null)
      setHandle(i, null)

      i -= 1
    }
    _wCount = e

    if (completeRelink) {
      while (shouldShrink)
        _wCapacity /= 2
      rebuildDispatch()
    }

    // revert to previous context
    _wUndoThreshold = _undoLog.prevWriteThreshold
  }

  private def resetWriteBuffer() {
    var i = _wCount - 1
    while (i >= 0) {
      // discard references to aid GC
      setRef(i, null)
      setSpecValue(i, null)
      setHandle(i, null)
      i -= 1
    }

    _wCount = 0
    _wCapacity = InitialWriteCapacity
    if (_wDispatch.length > MaxRetainedWriteCapacity)
      allocateWriteBuffer()
    else
      java.util.Arrays.fill(_wDispatch, 0, InitialWriteCapacity, -1)
  }

  private def accumulateWrites(accum: ReadSetBuilder) {
    var i = _wCount - 1
    while (i >= _wUndoThreshold) {
      val h = getWriteHandle(i)
      accum.add(h, CCSTM.version(h.meta))
      i -= 1
    }
  }
}

