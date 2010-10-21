/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm
package ccstm

// ViewOps

class ViewOps[T] extends Ref.View[T] {
  
  def handle: Handle[T]

  def get: T = InTxnImpl.dynCurrentOrNull match {
    case null => NonTxn.get(handle)
    case txn: InTxnImpl => txn.get(handle)
  }
  def getWith[Z](f: (T) => Z): Z = InTxnImpl.dynCurrentOrNull match {
    case null => f(NonTxn.get(handle))
    case txn: InTxnImpl => txn.getWith(handle, f)
  }
  def retryUntil(f: T => Boolean): Unit = InTxnImpl.dynCurrentOrNull match {
    case null => NonTxn.await(handle, pred)
    case txn: InTxnImpl => if (!pred(txn.get(handle))) txn.retry
  }
  def set(v: T): Unit = InTxnImpl.dynCurrentOrNull match {
    case null => NonTxn.set(handle, v)
    case txn: InTxnImpl => txn.set(handle, v)
  }
  def swap(v: T): T = InTxnImpl.dynCurrentOrNull match {
    case null => NonTxn.swap(handle, v)
    case txn: InTxnImpl => txn.swap(handle, v)
  }
  def compareAndSet(before: T, after: T): Boolean = InTxnImpl.dynCurrentOrNull match {
    case null => NonTxn.compareAndSet(handle, before, after)
    case txn: InTxnImpl => txn.compareAndSet(handle, before, after)
  }
  def compareAndSetIdentity[R <: AnyRef with T](before: R, after: T): Boolean = InTxnImpl.dynCurrentOrNull match {
    case null => NonTxn.compareAndSetIdentity(handle, before, after)
    case txn: InTxnImpl => txn.compareAndSetIdentity(handle, before, after)
  }
  def transform(f: T => T): Unit = InTxnImpl.dynCurrentOrNull match {
    case null => NonTxn.getAndTransform(handle, f)
    case txn: InTxnImpl => txn.getAndTransform(handle, f)
  }
  def getAndTransform(f: T => T): T = InTxnImpl.dynCurrentOrNull match {
    case null => NonTxn.getAndTransform(handle, f)
    case txn: InTxnImpl => txn.getAndTransform(handle, f)
  }
  def transformIfDefined(pf: PartialFunction[T,T]): Boolean = InTxnImpl.dynCurrentOrNull match {
    case null => NonTxn.transformIfDefined(handle, pf)
    case txn: InTxnImpl => txn.transformIfDefined(handle, pf)
  }
}
