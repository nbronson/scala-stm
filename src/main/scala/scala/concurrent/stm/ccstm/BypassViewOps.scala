/* scala-stm - (c) 2009-2011, Stanford University, PPL */

package scala.concurrent.stm
package ccstm

/** The default implementation of `Ref.BypassView`'s operations in CCSTM. */
private[ccstm] trait BypassViewOps[T] extends Ref.BypassView[T] with Handle.Provider[T] {
  
  def handle: Handle[T]

  def get(implicit ctx: BypassCtx): T = NonTxn.get(handle)
  def weakGet(implicit ctx: BypassCtx): T = handle.data
  def set(v: T)(implicit ctx: BypassCtx) { NonTxn.set(handle, v) }
  def trySet(v: T)(implicit ctx: BypassCtx): Boolean = NonTxn.trySet(handle, v)
  def swap(v: T)(implicit ctx: BypassCtx): T = NonTxn.swap(handle, v)
  def compareAndSet(before: T, after: T)(implicit ctx: BypassCtx): Boolean =
      NonTxn.compareAndSet(handle, before, after)
  def compareAndSetIdentity[R <: AnyRef with T](before: R, after: T)(implicit ctx: BypassCtx): Boolean =
      NonTxn.compareAndSetIdentity(handle, before, after)
  def transform(f: T => T)(implicit ctx: BypassCtx) { NonTxn.transformAndGet(handle, f) }
  def getAndTransform(f: T => T)(implicit ctx: BypassCtx): T = NonTxn.getAndTransform(handle, f)
  def transformAndGet(f: T => T)(implicit ctx: BypassCtx): T = NonTxn.transformAndGet(handle, f)
  def transformIfDefined(pf: PartialFunction[T,T])(implicit ctx: BypassCtx): Boolean =
      NonTxn.transformIfDefined(handle, pf)
}
