package scala.concurrent.stm

import java.util.IdentityHashMap

import org.scalatest.FunSuite
import concurrent.stm.Ref.View


/** Performs single-threaded tests of `Ref`. */
class IsolatedRefSuite extends FunSuite {

  // Ref factories produces Ref from an initial value

  object PrimitiveFactory extends (Int => Ref[Int]) {
    def apply(v0: Int) = Ref(v0)
    override def toString = "Primitive"
  }

  object KnownGenericFactory extends (Int => Ref[Int]) {
    private def create[A : ClassManifest](v0: A) = Ref(v0)
    def apply(v0: Int) = create(v0)
    override def toString = "KnownGeneric"
  }

  object UnknownGenericFactory extends (Int => Ref[Int]) {
    private def create[A](v0: A) = Ref(v0)
    def apply(v0: Int) = create(v0)
    override def toString = "UnknownGeneric"
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
    def swap(v: A): A = ref.swap(v)
    def compareAndSet(before: A, after: A): Boolean = {
      if (get == before) { set(after) ; true } else false
    }
    def compareAndSetIdentity[B <: A with AnyRef](before: B, after: A): Boolean = {
      if (before eq get.asInstanceOf[AnyRef]) { set(after) ; true } else false
    }
    def transform(f: A => A) { ref.transform(f) }
    def getAndTransform(f: A => A): A = { val z = get ; ref.transform(f) ; z }
    def transformIfDefined(pf: PartialFunction[A, A]): Boolean = ref.transformIfDefined(pf)
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
    def swap(v: A): A = wrap { view.swap(v) }
    def compareAndSet(before: A, after: A): Boolean = wrap { view.compareAndSet(before, after) }
    def compareAndSetIdentity[B <: A with AnyRef](before: B, after: A): Boolean = wrap { view.compareAndSetIdentity(before, after) }
    def transform(f: A => A) { wrap { view.transform(f) } }
    def getAndTransform(f: A => A): A = wrap { view.getAndTransform(f) }
    def transformIfDefined(pf: PartialFunction[A, A]): Boolean = wrap { view.transformIfDefined(pf) }
  }

  object FreshSingleAccess extends ((Ref[Int], Int) => Ref.View[Int]) {
    def apply(ref: Ref[Int], innerDepth: Int): View[Int] = new TestingView[Int](innerDepth, ref) {
      protected def view = ref.single
    }
    override def toString = "FreshSingle"
  }

  object ReuseSingleAccess extends ((Ref[Int], Int) => Ref.View[Int]) {
    def apply(ref: Ref[Int], innerDepth: Int): View[Int] = new TestingView[Int](innerDepth, ref) {
      protected val view = ref.single
    }
    override def toString = "ReuseSingle"
  }

  object RefAccess extends ((Ref[Int], Int) => Ref.View[Int]) {
    def apply(ref: Ref[Int], innerDepth: Int): View[Int] = new TestingView[Int](innerDepth, ref) {
      protected val view = new DynamicView[Int](ref)
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

  private def createTests(name: String, v0: Int)(block: (() => Ref.View[Int]) => Unit) {
    for (outerLevels <- 0 until 2;
         innerLevels <- 0 until 2;
         refFactory <- List(PrimitiveFactory, KnownGenericFactory, UnknownGenericFactory);
         viewFactory <- List(FreshSingleAccess, ReuseSingleAccess, RefAccess);
         if !(innerLevels + outerLevels == 0 && viewFactory == RefAccess)) {
      test("outer=" + outerLevels + ", inner=" + innerLevels + ", " +
              refFactory + ", " + viewFactory + ": " + name) {
        val ref = refFactory(v0)
        def getView = viewFactory(ref, innerLevels)
        nest(outerLevels) { block(getView _) }
      }
    }
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

  createTests("compareAndSet", 1) { view =>
    for (i <- 1 until 10) {
      assert(view()() === i)
      assert(!view().compareAndSet(0, -1))
      assert(view().compareAndSet(i, i + 1))
      assert(!view().compareAndSet(i, i + 1))
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

  class UserException extends Exception

  createTests("excepting transform", 1) { view =>
    intercept[UserException] {
      view().transform(v => throw new UserException)
    }
    assert(view().get === 1)
    view().transform(_ + 1)
    assert(view().get === 2)
  }

  // TODO: compareAndSet with excepting equals
  // TODO: compareAndSetIdentity
  // TODO: excepting transformIfDefined
  // TODO: relaxedGet

  createTests("/=", 11) { view =>
    view() /= 2
    assert(view()() === 5)
  }

  // TODO: division test for Ref[Double] and Ref[Float]
}
