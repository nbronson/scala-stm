/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm
package ccstm

import java.util.concurrent.atomic.AtomicLongFieldUpdater

private[ccstm] object CCSTMRefs {
  
  import UnsafeCCSTMRefs._
  
  trait Factory extends impl.RefFactory {
    def newRef(v0: Boolean): Ref[Boolean] = if (unsafe != null) new UBooleanRef(v0) else new SBooleanRef(v0)
    def newRef(v0: Byte): Ref[Byte] = if (unsafe != null) new UByteRef(v0) else new SByteRef(v0)
    def newRef(v0: Short): Ref[Short] = if (unsafe != null) new UShortRef(v0) else new SShortRef(v0)
    def newRef(v0: Char): Ref[Char] = if (unsafe != null) new UCharRef(v0) else new SCharRef(v0)
    def newRef(v0: Int): Ref[Int] = if (unsafe != null) new UIntRef(v0) else new SIntRef(v0)
    def newRef(v0: Float): Ref[Float] = if (unsafe != null) new UFloatRef(v0) else new SFloatRef(v0)
    def newRef(v0: Long): Ref[Long] = if (unsafe != null) new ULongRef(v0) else new SLongRef(v0)
    def newRef(v0: Double): Ref[Double] = if (unsafe != null) new UDoubleRef(v0) else new SDoubleRef(v0)
    def newRef(v0: Unit): Ref[Unit] = if (unsafe != null) new UGenericRef(v0) else new SGenericRef(v0)
    def newRef[T : ClassManifest](v0: T): Ref[T] = if (unsafe != null) new UGenericRef(v0) else new SGenericRef(v0)

    def newTArray[A: ClassManifest](length: Int): TArray[A] = new TArrayImpl[A](length)
    def newTArray[A: ClassManifest](xs: TraversableOnce[A]): TArray[A] = new TArrayImpl[A](xs)

    def newTMap[A, B](): TMap[A, B] = new skel.HashTrieTMap[A, B]
    def newTMap[A, B](kvs: TraversableOnce[(A, B)]): TMap[A, B] = new skel.HashTrieTMap[A, B](kvs)

    def newTSet[A](): TSet[A] = new skel.HashTrieTSet[A]
    def newTSet[A](xs: TraversableOnce[A]): TSet[A] = new skel.HashTrieTSet[A](xs)
  }

  abstract class BaseRef[A] extends Handle[A] with RefOps[A] with ViewOps[A] {
    def handle: Handle[A] = this
    def single: Ref.View[A] = this
    def ref: Ref[A] = this
    def base: AnyRef = this
    def metaOffset: Int = 0
    def offset: Int = 0
  }

  //////// versions using AtomicLongFieldUpdater

  // Every call to AtomicLongFieldUpdater checks the receiver's type with
  // receiver.getClass().isInstanceOf(declaringClass).  This means that there
  // is a substantial performance benefit to putting the meta field in the
  // concrete leaf classes instead of the abstract base class. 

  private val booleanMetaUpdater = if (unsafe != null) null else (new SBooleanRef(false)).newMetaUpdater
  private val byteMetaUpdater = if (unsafe != null) null else (new SByteRef(0 : Byte)).newMetaUpdater
  private val shortMetaUpdater = if (unsafe != null) null else (new SShortRef(0 : Short)).newMetaUpdater
  private val charMetaUpdater = if (unsafe != null) null else (new SCharRef(0 : Char)).newMetaUpdater
  private val intMetaUpdater = if (unsafe != null) null else (new SIntRef(0 : Int)).newMetaUpdater
  private val floatMetaUpdater = if (unsafe != null) null else (new SFloatRef(0 : Float)).newMetaUpdater
  private val longMetaUpdater = if (unsafe != null) null else (new SLongRef(0 : Long)).newMetaUpdater
  private val doubleMetaUpdater = if (unsafe != null) null else (new SDoubleRef(0 : Double)).newMetaUpdater
  private val genericMetaUpdater = if (unsafe != null) null else (new SGenericRef("")).newMetaUpdater

  final class SBooleanRef(@volatile var data: Boolean) extends BaseRef[Boolean] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = booleanMetaUpdater.compareAndSet(this, m0, m1)
    def newMetaUpdater = AtomicLongFieldUpdater.newUpdater(classOf[SBooleanRef], "meta")
  }

  final class SByteRef(@volatile var data: Byte) extends BaseRef[Byte] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = byteMetaUpdater.compareAndSet(this, m0, m1)
    def newMetaUpdater = AtomicLongFieldUpdater.newUpdater(classOf[SByteRef], "meta")
  }

  final class SShortRef(@volatile var data: Short) extends BaseRef[Short] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = shortMetaUpdater.compareAndSet(this, m0, m1)
    def newMetaUpdater = AtomicLongFieldUpdater.newUpdater(classOf[SShortRef], "meta")
  }

  final class SCharRef(@volatile var data: Char) extends BaseRef[Char] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = charMetaUpdater.compareAndSet(this, m0, m1)
    def newMetaUpdater = AtomicLongFieldUpdater.newUpdater(classOf[SCharRef], "meta")
  }

  final class SIntRef(@volatile var data: Int) extends BaseRef[Int] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = intMetaUpdater.compareAndSet(this, m0, m1)
    def newMetaUpdater = AtomicLongFieldUpdater.newUpdater(classOf[SIntRef], "meta")
  }

  final class SFloatRef(@volatile var data: Float) extends BaseRef[Float] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = floatMetaUpdater.compareAndSet(this, m0, m1)
    def newMetaUpdater = AtomicLongFieldUpdater.newUpdater(classOf[SFloatRef], "meta")
  }

  final class SLongRef(@volatile var data: Long) extends BaseRef[Long] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = longMetaUpdater.compareAndSet(this, m0, m1)
    def newMetaUpdater = AtomicLongFieldUpdater.newUpdater(classOf[SLongRef], "meta")
  }

  final class SDoubleRef(@volatile var data: Double) extends BaseRef[Double] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = doubleMetaUpdater.compareAndSet(this, m0, m1)
    def newMetaUpdater = AtomicLongFieldUpdater.newUpdater(classOf[SDoubleRef], "meta")
  }

  final class SGenericRef[A](@volatile var data: A) extends BaseRef[A] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = genericMetaUpdater.compareAndSet(this, m0, m1)
    def newMetaUpdater = AtomicLongFieldUpdater.newUpdater(classOf[SGenericRef[_]], "meta")
  }

  //////// versions using sun.misc.Unsafe
  
  final class UBooleanRef(@volatile var data: Boolean) extends BaseRef[Boolean] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = unsafe.compareAndSwapLong(this, booleanRefMetaOff, m0, m1)
  }

  final class UByteRef(@volatile var data: Byte) extends BaseRef[Byte] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = unsafe.compareAndSwapLong(this, byteRefMetaOff, m0, m1)
  }

  final class UShortRef(@volatile var data: Short) extends BaseRef[Short] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = unsafe.compareAndSwapLong(this, shortRefMetaOff, m0, m1)
  }

  final class UCharRef(@volatile var data: Char) extends BaseRef[Char] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = unsafe.compareAndSwapLong(this, charRefMetaOff, m0, m1)
  }

  final class UIntRef(@volatile var data: Int) extends BaseRef[Int] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = unsafe.compareAndSwapLong(this, intRefMetaOff, m0, m1)
  }

  final class UFloatRef(@volatile var data: Float) extends BaseRef[Float] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = unsafe.compareAndSwapLong(this, floatRefMetaOff, m0, m1)
  }

  final class ULongRef(@volatile var data: Long) extends BaseRef[Long] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = unsafe.compareAndSwapLong(this, longRefMetaOff, m0, m1)
  }

  final class UDoubleRef(@volatile var data: Double) extends BaseRef[Double] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = unsafe.compareAndSwapLong(this, doubleRefMetaOff, m0, m1)
  }

  final class UGenericRef[A](@volatile var data: A) extends BaseRef[A] {
    @volatile var meta = 0L
    def metaCAS(m0: Long, m1: Long) = unsafe.compareAndSwapLong(this, genericRefMetaOff, m0, m1)
  }
}
