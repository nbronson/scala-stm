/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

import impl.{RefFactory, STMImpl}
import reflect.{AnyValManifest, OptManifest}

object Ref extends RefCompanion {

  protected def factory: RefFactory = STMImpl.instance

  trait View[A] extends Source.View[A] with Sink.View[A] {

    /** Returns a `Ref` that accesses the same memory location as this view.
     *  The returned `Ref` might be the original reference that was used to
     *  construct this view, or it might be a `Ref` that is equivalent (and
     *  `==`) to the original.
     *  @return a `Ref` that accesses the same memory location as this view.
     */
    override def unbind: Ref[A]

    /** Works like `set(v)`, but returns the old value.  This is an
     *  atomic swap, equivalent to atomically performing a `get`
     *  followed by `set(v)`.
     *  @return the previous value of the viewed `Ref`.
     */
    def swap(v: A): A

    /** Equivalent to atomically executing
     *  `(if (before == get) { set(after); true } else false)`, but may be more
     *  efficient, especially if there is no enclosing atomic block.
     *  @param before a value to compare against the current `Ref` contents.
     *  @param after a value to store in the `Ref` if `before` was equal to the
     *      previous cell contents.
     *  @return true if `before` was equal to the previous value of the bound
     *      `Ref`, false otherwise.
     */
    def compareAndSet(before: A, after: A): Boolean

    /** Equivalent to atomically executing
     *  `(if (before eq get) { set(after); true } else false)`, but may be more
     *  efficient, especially if there is no enclosing atomic block.
     *  @param before a reference whose identity will be compared against the
     *      current `Ref` contents.
     *  @param after a value to store in the `Ref` if `before` has the same
     *      reference identity as the previous cell contents.
     *  @return true if `before` has the same reference identity as the
     *      previous value of the bound `Ref`, false otherwise.
     */
    def compareAndSetIdentity[B <: A with AnyRef](before: B, after: A): Boolean

    /** Atomically replaces the value ''v'' stored in the `Ref` with
     *  `f`(''v'').  Some `Ref` implementations may defer execution of `f` or
     *  call `f` multiple times to avoid transaction conflicts.
     *  @param f a function that is safe to call multiple times, and safe to
     *      call later during the enclosing atomic block, if any.
     */
    def transform(f: A => A)

    /** Atomically replaces the value ''v'' stored in the `Ref` with
     *  `f`(''v''), returning the old value.  `transform` should be preferred
     *  if the return value is not needed, since it gives the STM more
     *  flexibility to avoid transaction conflicts.
     *  @param f a function that is safe to call multiple times, and safe to
     *      call later during the enclosing atomic block, if any.
     *  @return the previous value of the viewed `Ref`.
     */
    def getAndTransform(f: A => A): A

    /** Either atomically transforms this reference without blocking and
     *  returns true, or returns false.  `transform` is to `tryTransform` as
     *  `set` is to `trySet`.  If this method is called inside an atomic block
     *  a true return value does not necessarily mean that `f` has already been
     *  called, just that the transformation will definitely be performed in
     *  the outer transaction if it commits.
     *  @param f a function that is safe to call multiple times, and safe to
     *      call later during the enclosing atomic block, if any.
     *  @return true if the function was (or will be) applied, false if it was
     *      not.
     */
    def tryTransform(f: A => A): Boolean

    /** Atomically replaces the value ''v'' stored in the `Ref` with
     *  `pf`(''v'') if `pf.isDefinedAt`(''v''), returning true, otherwise
     *  leaves the element unchanged and returns false.  `pf.apply` and
     *  `pf.isDefinedAt` might be invoked multiple times by the STM, and might
     *  be called later in the enclosing atomic block, if any.
     *  @param pf a partial function that is safe to call multiple times, and
     *      safe to call later in the enclosing atomic block, if any.
     *  @return `pf.isDefinedAt``(''v''), where ''v'' is the element held by
     *      this `Ref` on entry.
     */
    def transformIfDefined(pf: PartialFunction[A,A]): Boolean

    /** Transforms the value stored in the `Ref` by incrementing it.
     *
     *  '''Note: Some `Ref` implementations may choose to ignore the passed-in
     *  `Numeric[A]` instance if `A` is a primitive type.'''
     *
     *  @param rhs the quantity by which to increment the value of `unbind`.
     */
    def += (rhs: A)(implicit num: Numeric[A]) { transform { v => num.plus(v, rhs) } }

    /** Transforms the value stored in the `Ref` by decrementing it.
     *
     *  '''Note: Some `Ref` implementations may choose to ignore the passed-in
     *  `Numeric[A]` instance if `A` is a primitive type.'''
     *
     *  @param rhs the quantity by which to decrement the value of `unbind`.
     */
    def -= (rhs: A)(implicit num: Numeric[A]) { transform { v => num.minus(v, rhs) } }

    /** Transforms the value stored in the `Ref` by multiplying it.
     *
     *  '''Note: Some `Ref` implementations may choose to ignore the passed-in
     *  `Numeric[A]` instance if `A` is a primitive type.'''
     *
     *  @param rhs the quantity by which to multiple the value of `unbind`.
     */
    def *= (rhs: A)(implicit num: Numeric[A]) { transform { v => num.times(v, rhs) } }

    /** Transforms the value stored in the `Ref` by performing a division on
     *  it, throwing away the remainder if division is not exact for instances
     *  of `A`.  The careful reader will note that division is actually
     *  provided by either an implicit `Fractional[A]` or an implicit
     *  `Integral[A]`, it is not defined on `Numeric[A]`.  Due to problems
     *  overloading based on the available implicits this method accepts any
     *  `Numeric[A]` and assumes that it can be converted at runtime into
     *  exactly one of the two previous types.
     *
     *  '''Note: Some `Ref` implementations may choose to ignore the passed-in
     *  `Integral[A]` instance if `A` is a primitive type.'''
     *
     *  @param rhs the quantity by which to divide the value of `unbind`.
     */
    def /= (rhs: A)(implicit num: Numeric[A]) {
      num match {
        //case numF: Fractional[A] => transform { v => numF.div(v, rhs) }
        case numF: Fractional[_] => transform { v => numF.asInstanceOf[Fractional[A]].div(v, rhs) }
        //case numI: Integral[A] => transform { v => numI.quot(v, rhs) }
        case numI: Integral[_] => transform { v => numI.asInstanceOf[Integral[A]].quot(v, rhs) }
      }
    }
  }
}

// All of object Ref's functionality is actually in RefCompanion.  The split
// allows RefCompanion to be tested independently of the globally configured
// RefFactory, without introducing an extra level of mutable indirection for
// normal uses of the companion object.

trait RefCompanion {
  
  protected def factory: RefFactory

  /** Returns a new `Ref` instance suitable for holding instances of `A`.
   *  If you have an initial value `v0` available, prefer `apply(v0)`.
   */
  def make[A]()(implicit om: OptManifest[A]): Ref[A] = (om match {
    case m: ClassManifest[_] => m.newArray(0).asInstanceOf[AnyRef] match {
      // these can be reordered, so long as Unit comes before AnyRef
      case _: Array[Boolean] => apply(false)
      case _: Array[Byte]    => apply(0 : Byte)
      case _: Array[Short]   => apply(0 : Short)
      case _: Array[Char]    => apply(0 : Char)
      case _: Array[Int]     => apply(0 : Int)
      case _: Array[Float]   => apply(0 : Float)
      case _: Array[Long]    => apply(0 : Long)
      case _: Array[Double]  => apply(0 : Double)
      case _: Array[Unit]    => apply(())
      case _: Array[AnyRef]  => factory.newRef(null.asInstanceOf[A])(m.asInstanceOf[ClassManifest[A]])
    }
    case _ => factory.newRef(null.asInstanceOf[Any])(implicitly[ClassManifest[Any]])
  }).asInstanceOf[Ref[A]]

  /** Returns a new `Ref` instance with the specified initial value.  The
   *  returned instance is not part of any transaction's read or write set.
   *
   *  Example: {{{
   *    val x = Ref("initial") // creates a Ref[String]
   *    val list1 = Ref(Nil : List[String]) // creates a Ref[List[String]]
   *    val list2 = Ref[List[String]](Nil)  // creates a Ref[List[String]]
   *  }}}
   */
  def apply[A](initialValue: A)(implicit om: OptManifest[A]): Ref[A] = om match {
    case m: AnyValManifest[_] => newPrimitiveRef(initialValue, m.asInstanceOf[AnyValManifest[A]])
    case m: ClassManifest[_] => factory.newRef(initialValue)(m.asInstanceOf[ClassManifest[A]])
    case _ => factory.newRef[Any](initialValue).asInstanceOf[Ref[A]]
  }

  private def newPrimitiveRef[A](initialValue: A, m: AnyValManifest[A]): Ref[A] = {
    (m.newArray(0).asInstanceOf[AnyRef] match {
      // these can be reordered, so long as Unit comes before AnyRef
      case _: Array[Boolean] => apply(initialValue.asInstanceOf[Boolean])
      case _: Array[Byte]    => apply(initialValue.asInstanceOf[Byte])
      case _: Array[Short]   => apply(initialValue.asInstanceOf[Short])
      case _: Array[Char]    => apply(initialValue.asInstanceOf[Char])
      case _: Array[Int]     => apply(initialValue.asInstanceOf[Int])
      case _: Array[Float]   => apply(initialValue.asInstanceOf[Float])
      case _: Array[Long]    => apply(initialValue.asInstanceOf[Long])
      case _: Array[Double]  => apply(initialValue.asInstanceOf[Double])
      case _: Array[Unit]    => apply(initialValue.asInstanceOf[Unit])
    }).asInstanceOf[Ref[A]]
  }

  def apply(initialValue: Boolean): Ref[Boolean] = factory.newRef(initialValue)
  def apply(initialValue: Byte   ): Ref[Byte]    = factory.newRef(initialValue)
  def apply(initialValue: Short  ): Ref[Short]   = factory.newRef(initialValue)
  def apply(initialValue: Char   ): Ref[Char]    = factory.newRef(initialValue)
  def apply(initialValue: Int    ): Ref[Int]     = factory.newRef(initialValue)
  def apply(initialValue: Long   ): Ref[Long]    = factory.newRef(initialValue)
  def apply(initialValue: Float  ): Ref[Float]   = factory.newRef(initialValue)
  def apply(initialValue: Double ): Ref[Double]  = factory.newRef(initialValue)
  def apply(initialValue: Unit   ): Ref[Unit]    = factory.newRef(initialValue)
}

trait Ref[A] extends Source[A] with Sink[A] {

  override def single: Ref.View[A]

  // read-only operations (covariant) are in Source
  // write-only operations (contravariant) are in Sink
  // read+write operations go here 

  /** Works like `set(v)`, but returns the old value.
   *  @return the previous value of this `Ref`, as observed by `txn`.
   *  @throws IllegalStateException if `txn` is not active.
   */
  def swap(v: A)(implicit txn: Txn): A

  /** Transforms the value referenced by this `Ref` by applying the function
   *  `f`.  Acts like `ref.set(f(ref.get))`, but the execution of `f` may be
   *  deferred or repeated by the STM to reduce transaction conflicts.
   *  @param f a function that is safe to call multiple times, and safe to
   *      call later during the transaction.
   *  @throws IllegalStateException if `txn` is not active.
   */
  def transform(f: A => A)(implicit txn: Txn)

  /** Transforms the value ''v'' referenced by this `Ref` by to
   *  `pf.apply`(''v''), but only if `pf.isDefinedAt`(''v'').  Returns true if
   *  a transformation was performed, false otherwise.  `pf.apply` and
   *  `pf.isDefinedAt` may be deferred or repeated by the STM to reduce
   *  transaction conflicts.
   *  @param pf a partial function that is safe to call multiple times, and
   *      safe to call later in the transaction.
   *  @return `pf.isDefinedAt(v)`, where `v` was the value of this `Ref`
   *      before transformation (if any).
   *  @throws IllegalStateException if `txn` is not active.
   */
  def transformIfDefined(pf: PartialFunction[A,A])(implicit txn: Txn): Boolean

  /** Transforms the value stored in the `Ref` by incrementing it.
   *
   *  '''Note: Some `Ref` implementations may choose to ignore the passed-in
   *  `Numeric[A]` instance if `A` is a primitive type.'''
   *
   *  @param rhs the quantity by which to increment the value of this `Ref`.
   *  @throws IllegalStateException if `txn` is not active. */
  def += (rhs: A)(implicit txn: Txn, num: Numeric[A]) { transform { v => num.plus(v, rhs) } }

  /** Transforms the value stored in the `Ref` by decrementing it.
   *
   *  '''Note: Some `Ref` implementations may choose to ignore the passed-in
   *  `Numeric[A]` instance if `A` is a primitive type.'''
   *
   *  @param rhs the quantity by which to decrement the value of this `Ref`.
   *  @throws IllegalStateException if `txn` is not active. */
  def -= (rhs: A)(implicit txn: Txn, num: Numeric[A]) { transform { v => num.minus(v, rhs) } }

  /** Transforms the value stored in the `Ref` by multiplying it.
   *
   *  '''Note: Some `Ref` implementations may choose to ignore the passed-in
   *  `Numeric[A]` instance if `A` is a primitive type.'''
   *
   *  @param rhs the quantity by which to multiply the value of this `Ref`.
   *  @throws IllegalStateException if `txn` is not active.
   */
  def *= (rhs: A)(implicit txn: Txn, num: Numeric[A]) { transform { v => num.times(v, rhs) } }

  /** Transforms the value stored in the `Ref` by performing a division on
   *  it, throwing away the remainder if division is not exact for instances
   *  of `A`.  The careful reader will note that division is actually
   *  provided by either an implicit `Fractional[A]` or an implicit
   *  `Integral[A]`, it is not defined on `Numeric[A]`.  Due to problems
   *  overloading based on the available implicits this method accepts any
   *  `Numeric[A]` and assumes that it can be converted at runtime into
   *  exactly one of the two previous types.
   *
   *  '''Note: Some `Ref` implementations may choose to ignore the passed-in
   *  `Integral[A]` instance if `A` is a primitive type.'''
   *
   *  @param rhs the quantity by which to divide the value of this `Ref`.
   *  @throws IllegalStateException if `txn` is not active.
   */
  def /= (rhs: A)(implicit txn: Txn, num: Numeric[A]) {
    num match {
      //case numF: Fractional[A] => transform { v => numF.div(v, rhs) }
      case numF: Fractional[_] => transform { v => numF.asInstanceOf[Fractional[A]].div(v, rhs) }
      //case numI: Integral[A] => transform { v => numI.quot(v, rhs) }
      case numI: Integral[_] => transform { v => numI.asInstanceOf[Integral[A]].quot(v, rhs) }
    }
  }
}
