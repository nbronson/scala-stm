/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm

import org.scalatest.FunSuite


/** Performs single-threaded tests of `Ref`. */
class IsolatedRefSuite extends FunSuite {

  // Ref factories produces Ref from an initial value

  object PrimitiveIntFactory extends (Int => Ref[Int]) {
    def apply(v0: Int) = Ref(v0)
    override def toString = "Primitive"
  }

  class KnownGenericFactory[A : ClassManifest] extends (A => Ref[A]) {
    def apply(v0: A) = Ref(v0)
    override def toString = "KnownGeneric"
  }

  class UnknownGenericFactory[A] extends (A => Ref[A]) {
    def apply(v0: A) = Ref(v0)
    override def toString = "UnknownGeneric"
  }

  class ArrayElementFactory[A : ClassManifest] extends (A => Ref[A]) {
    def apply(v0: A) = {
      val ref = TArray.ofDim[A](10).refs(5)
      ref.single() = v0
      ref
    }
    override def toString = "ArrayElement"
  }


  // This implements Ref.View, but requires a surrounding InTxn context and
  // forwards to Ref's methods.
  class DynamicView[A](val ref: Ref[A]) extends Ref.View[A] {
    implicit def txn = Txn.findCurrent.get

    def get: A = ref.get
    def getWith[Z](f: A => Z): Z = ref.getWith(f)
    def relaxedGet(equiv: (A, A) => Boolean): A = ref.relaxedGet(equiv)
    def retryUntil(f: A => Boolean) { if (!f(get)) retry }
    def set(v: A) { ref.set(v) }
    def trySet(v: A) = ref.trySet(v)
    def swap(v: A): A = ref.swap(v)
    def compareAndSet(before: A, after: A): Boolean = {
      if (get == before) { set(after) ; true } else false
    }
    def compareAndSetIdentity[B <: A with AnyRef](before: B, after: A): Boolean = {
      if (before eq get.asInstanceOf[AnyRef]) { set(after) ; true } else false
    }
    def transform(f: A => A) { ref.transform(f) }
    def getAndTransform(f: A => A): A = { val z = get ; ref.transform(f) ; z }
    def transformAndGet(f: A => A): A = { ref.transform(f) ; get }
    def transformIfDefined(pf: PartialFunction[A, A]): Boolean = ref.transformIfDefined(pf)

    override def hashCode: Int = ref.hashCode
    override def equals(rhs: Any): Boolean = ref == rhs
  }

  abstract class TestingView[A](innerDepth: Int, val ref: Ref[A]) extends Ref.View[A] {
    private def wrap[Z](block: => Z): Z = nest(innerDepth, block)
    private def nest[Z](d: Int, block: => Z): Z = {
      if (d == 0)
        block
      else
        atomic { implicit txn => nest(d - 1, block) }
    }

    protected def view: Ref.View[A]

    def get: A = wrap { view.get }
    def getWith[Z](f: A => Z): Z = wrap { view.getWith(f) }
    def relaxedGet(equiv: (A, A) => Boolean): A = wrap { view.relaxedGet(equiv) }
    def retryUntil(f: (A) => Boolean) { wrap { view.retryUntil(f) } }
    def set(v: A) { wrap { view.set(v) } }
    def trySet(v: A) = wrap { view.trySet(v) }
    def swap(v: A): A = wrap { view.swap(v) }
    def compareAndSet(before: A, after: A): Boolean = wrap { view.compareAndSet(before, after) }
    def compareAndSetIdentity[B <: A with AnyRef](before: B, after: A): Boolean = wrap { view.compareAndSetIdentity(before, after) }
    def transform(f: A => A) { wrap { view.transform(f) } }
    def getAndTransform(f: A => A): A = wrap { view.getAndTransform(f) }
    def transformAndGet(f: A => A): A = wrap { view.transformAndGet(f) }
    def transformIfDefined(pf: PartialFunction[A, A]): Boolean = wrap { view.transformIfDefined(pf) }

    override def hashCode: Int = ref.hashCode
    override def equals(rhs: Any): Boolean = ref == rhs
  }

  trait ViewFactory {
    def apply[A](ref: Ref[A], innerDepth: Int): Ref.View[A]
  }

  object SingleAccess extends ViewFactory {
    def apply[A](ref: Ref[A], innerDepth: Int): Ref.View[A] = new TestingView[A](innerDepth, ref) {
      protected def view = ref.single
    }
    override def toString = "Single"
  }

  object RefAccess extends ViewFactory {
    def apply[A](ref: Ref[A], innerDepth: Int): Ref.View[A] = new TestingView[A](innerDepth, ref) {
      protected val view = new DynamicView[A](ref)
    }
    override def toString = "Ref"
  }

  // The test environment is determined by
  //  outerLevels: the number of atomic block nesting levels that surround
  //               the entire test;
  //  innerLevels: the number of atomic block nesting levels that surround
  //               the individual operations of the test;
  //  refFactory:  Ref[Int] can be created with or without the appropriate
  //               manifest, or via the overloaded Ref.apply(Int); and
  //  viewFactory: one of FreshSingleAccess, ReuseSingleAccess, or RefAccess
  //               (which requires outerLevels + innerLevels > 0).
  //
  // Now we enumerate the environments, generating a set of tests for each
  // configuration.

  private def createTests[A : ClassManifest](name: String, v0: A)(block: (() => Ref.View[A]) => Unit) {
    for (outerLevels <- 0 until 2;
         innerLevels <- 0 until 2;
         refFactory <- List(new KnownGenericFactory[A], new UnknownGenericFactory[A], new ArrayElementFactory[A]);
         viewFactory <- List(SingleAccess, RefAccess);
         if !(innerLevels + outerLevels == 0 && viewFactory == RefAccess)) {
      test("outer=" + outerLevels + ", inner=" + innerLevels + ", " +
              refFactory + ", " + viewFactory + ": " + name) {
        val ref = refFactory(v0)
        def getView = viewFactory(ref, innerLevels)
        nest(outerLevels) { block(getView _) }
      }
    }
  }

  test("primitive factory and ClassManifest generic produce same type") {
    // this cuts down on the proliferation of tests
    val g = new KnownGenericFactory[Int]
    assert(PrimitiveIntFactory(10).getClass === g(10).getClass)
  }

  private def nest(depth: Int)(block: => Unit) {
    if (depth == 0)
      block
    else
      atomic { implicit txn => nest(depth - 1)(block) }
  }

  createTests("get and set", 1) { view =>
    for (i <- 1 until 10) {
      assert(view()() === i)
      view()() = view()() + 1
    }
    assert(view()() === 10)
  }

  createTests("get and trySet", 1) { view =>
    for (i <- 1 until 10) {
      assert(view()() === i)
      val f = view().trySet(view()() + 1)
      assert(f) // trySet shouldn't fail in the absence of contention
    }
    assert(view()() === 10)
  }

  createTests("compareAndSet", 1) { view =>
    for (i <- 1 until 10) {
      assert(view()() === i)
      assert(!view().compareAndSet(0, -1))
      assert(view().compareAndSet(i, i + 1))
      assert(!view().compareAndSet(i, i + 1))
    }
    assert(view()() === 10)
  }

  createTests("swap", 1) { view =>
    for (i <- 1 until 10) {
      assert(view().swap(i + 1) === i)
    }
    assert(view()() === 10)
  }

  createTests("set + swap", 1) { view =>
    for (i <- 1 until 10) {
      view()() = -1
      assert(view().swap(i + 1) === -1)
    }
    assert(view()() === 10)
  }

  createTests("transform", 1) { view =>
    for (i <- 1 until 10) {
      assert(view()() === i)
      view().transform(_ + 1)
    }
    assert(view()() === 10)
  }

  createTests("getAndTransform", 1) { view =>
    for (i <- 1 until 10) {
      assert(view()() === i)
      assert(view().getAndTransform(_ + 1) === i)
    }
    assert(view()() === 10)
  }

  createTests("transformIfDefined", 1) { view =>
    for (i <- 1 until 10) {
      assert(view()() === i)
      assert(!(view().transformIfDefined {
        case 0 => -1
      }))
      assert(view().transformIfDefined {
        case x if x > 0 => {
          assert(x === i)
          x + 1
        }
      })
    }
    assert(view()() === 10)
  }

  createTests("+= 1", 1) { view =>
    for (i <- 1 until 10) {
      assert(view()() === i)
      view() += 1
    }
    assert(view()() === 10)
  }

  createTests("-= -1", 1) { view =>
    for (i <- 1 until 10) {
      assert(view()() === i)
      view() -= -1
    }
    assert(view()() === 10)
  }

  createTests("*= 2", 1) { view =>
    for (i <- 1 until 10) {
      view() *= 2
    }
    assert(view()() === 512)
  }

  createTests("getWith", 1) { view =>
    assert(view().getWith(_ * 10) === 10)
  }

  createTests("write ; getWith", 1) { view =>
    view()() = 2
    assert(view().getWith(_ * 10) === 20)
  }

  createTests("getWith ; write", 1) { view =>
    assert(view().getWith(_ * 10) === 10)
    view()() = 2
  }

  createTests("relaxedGet", 10) { view =>
    assert(view().relaxedGet( _ == _ ) === 10)
  }

  createTests("write ; relaxedGet", 10) { view =>
    view()() = 20
    assert(view().relaxedGet( _ == _ ) === 20)
  }

  createTests("relaxedGet ; write", 10) { view =>
    assert(view().relaxedGet( _ == _ ) === 10)
    view()() = 20
  }

  class UserException extends Exception

  createTests("excepting transform", 1) { view =>
    intercept[UserException] {
      view().transform(v => throw new UserException)
    }
    assert(view().get === 1)
    view().transform(_ + 1)
    assert(view().get === 2)
  }

  createTests("excepting transformIfDefined", 1) { view =>
    intercept[UserException] {
      view().transformIfDefined {
        case v if v > 0 => throw new UserException
        case v => v * 100
      }
    }
    assert(view().get === 1)
    view().transform(_ + 1)
    assert(view().get === 2)
  }

  class AngryEquals(val polarity: Boolean) {
    override def equals(rhs: Any): Boolean = {
      if (this eq rhs.asInstanceOf[AnyRef])
        true
      else {
        // equal polarities actively repel
        if (rhs.asInstanceOf[AngryEquals].polarity == polarity)
          throw new UserException
        false
      }
    }
  }

  createTests(".equals not involved in get and set", new AngryEquals(true)) { view =>
    assert(view().get ne null)
    view()() = new AngryEquals(true)
  }

  createTests("excepting compareAndSet", new AngryEquals(true)) { view =>
    val prev = view()()
    intercept[UserException] {
      view().compareAndSet(new AngryEquals(true), new AngryEquals(true))
    }
    assert(!view().compareAndSet(new AngryEquals(false), new AngryEquals(true)))
    val repl = new AngryEquals(false)
    assert(view().compareAndSet(prev, repl))
    assert(view().get === repl)
  }

  createTests("compareAndSetIdentity", "orig") { view =>
    assert(!view().compareAndSetIdentity(new String("orig"), "equal"))
    assert(view().compareAndSetIdentity("orig", "eq"))
    assert(view().get === "eq")
  }

  createTests("/=", 11) { view =>
    view() /= 2
    assert(view()() === 5)
  }

  createTests("/= double", 11.0) { view =>
    view() /= 2
    assert(view()() === 5.5)
  }

  createTests("/= float", 11.0f) { view =>
    view() /= 2
    assert(view()() === 5.5f)
  }

  createTests("BigInt ops", BigInt("1234")) { view =>
    view() += 1
    assert(view()().toString === "1235")
    view() -= 2
    assert(view()().toString === "1233")
    view() *= 3
    assert(view()().toString === "3699")
    view() /= 4
    assert(view()().toString === "924")
  }

  createTests("BigDecimal ops", BigDecimal("1234")) { view =>
    view() += 1
    assert(view()().toString === "1235")
    view() -= 2
    assert(view()().toString === "1233")
    view() *= 3
    assert(view()().toString === "3699")
    view() /= 4
    assert(view()().toString === "924.75")
  }

  private def perTypeTests[A : ClassManifest](v0: A, v1: A) {
    val name = v0.asInstanceOf[AnyRef].getClass.getSimpleName

    createTests(name + " simple get+set", v0) { view =>
      assert(view()() === v0)
      view()() = v1
      assert(view()() === v1)
    }

    createTests(name + " Ref equality", v0) { view =>
      assert(view() == view())
      assert(view().ref == view())
      assert(view() == view().ref)
      assert(view().ref == view().ref)
      assert(view().ref.single == view())
      assert(view() != Ref(v0))
    }

    test(name + " TArray Ref equality") {
      val a = TArray(Seq(v0))
      assert(a.refs(0) == a.refs(0))
      assert(a.single.refViews(0) == a.refs(0))
      assert(a.single.refViews(0).ref == a.refs(0))
      assert(a.single.refViews(0) == a.single.refViews(0))
      assert(a.refs(0) == a.refs(0).single)
      assert(a.single.tarray.refs(0) == a.refs(0).single)
    }

    test(name + " TArray Ref inequality") {
      val a = TArray(Seq(v0))
      val b = TArray(Seq(v1))
      assert(b.refs(0) != a.refs(0))
      assert(b.single.refViews(0) != a.refs(0))
      assert(b.single.refViews(0).ref != a.refs(0))
      assert(b.single.refViews(0) != a.single.refViews(0))
      assert(b.refs(0) != a.refs(0).single)
      assert(b.single.tarray.refs(0) != a.refs(0).single)
    }
  }

  perTypeTests(false, true)
  perTypeTests(1 : Byte, 2 : Byte)
  perTypeTests(1 : Short, 2 : Short)
  perTypeTests('1', '2')
  perTypeTests(1, 2)
  perTypeTests(1.0f, 2.0f)
  perTypeTests(1L, 2L)
  perTypeTests(1.0, 2.0)
  perTypeTests((), ())
  perTypeTests("1", "2")
}
