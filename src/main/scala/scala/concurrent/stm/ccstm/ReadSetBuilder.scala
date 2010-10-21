/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm.ccstm

import annotation.tailrec


private[impl] final class ReadSetBuilder {
  private var _size = 0
  private var _handles = new Array[Handle[_]](maxSizeForCap(InitialCap) + 1)
  private var _versions = new Array[CCSTM.Version](maxSizeForCap(InitialCap) + 1)
  private var _next = new Array[Int](maxSizeForCap(InitialCap) + 1)
  private var _dispatch = new Array[Int](InitialCap)

  private def InitialCap = 16
  private def maxSizeForCap(cap: Int) = cap - (cap / 4)

  def add(handle: Handle[_], version: CCSTM.Version): Unit = {
    val slot = CCSTM.hash(handle.ref, handle.offset) & (_dispatch.length - 1)
    addImpl(slot, _dispatch(slot), handle, version)
  }

  @tailrec
  private def addImpl(slot: Int, i: Int, handle: Handle[_], version: CCSTM.Version): Unit = {
    if (i == 0)
      append(slot, handle, version)
    else if (!hEq(_handles(i - 1), handle))
      addImpl(slot, _next(i - 1), handle, version)
    // else it is a duplicate
  }

  private def append(slot: Int, handle: Handle[_], version: CCSTM.Version): Unit = {
    val i = _size + 1
    _size = i
    _handles(i - 1) = handle
    _versions(i - 1) = version
    _next(i - 1) = _dispatch(slot)
    _dispatch(slot) = i
    if (_size > maxSizeForCap(_dispatch.length))
      grow()
  }

  private def grow(): Unit = {
    // store the current contents
    val s = _size
    val hh = _handles
    val vv = _versions

    // reallocate
    _size = 0
    val c = _dispatch.length * 2
    _handles = new Array[Handle[_]](maxSizeForCap(c) + 1)
    _versions = new Array[CCSTM.Version](maxSizeForCap(c) + 1)
    _next = new Array[Int](maxSizeForCap(c) + 1)
    _dispatch = new Array[Int](c)

    // reinsert the current contents
    var i = 0
    while (i < s) {
      val h = hh(i)
      append(CCSTM.hash(h.ref, h.offset) & (c - 1), h, vv(i))
      i += 1
    }
  }

  private def hEq(a: Handle[_], b: Handle[_]) = (a eq b) || ((a.ref eq b.ref) && (a.offset == b.offset))

  def result(): ReadSet = {
    _dispatch = null
    _next = null
    new ReadSet(_size, _handles, _versions)
  }
}
