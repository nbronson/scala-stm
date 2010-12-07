/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm


object TxnLocal {
  /** Constructs and returns a `TxnLocal` that will evaluate `init` each time
   *  that an initial value is required.
   */
  def apply[A](init: => A): TxnLocal[A] = new TxnLocal[A] {
    override def initialValue(txn: InTxn) = init
  }
}

/** `TxnLocal[A]` holds an instance of `A` that is local to an atomic block.
 *  If a `TxnLocal` is read before it is has been assigned a value in the
 *  current context, the value will be computed using the overridable
 *  `initialValue` method. `TxnLocal` holds a value until just before the
 *  after-completion handlers for the transaction are fired.  To arrange
 *  cleanup of a value created by `initialValue`, register a handler via
 *  `Txn.afterCommit`, `Txn.afterRollback`, or `Txn.afterCompletion`.
 *
 *  @author Nathan Bronson
 */
class TxnLocal[A] extends RefLike[A] {

  private val perThread = new ThreadLocal[(NestingLevel, Ref[Option[A]])]

  private def perRoot(implicit txn: InTxn): Ref[Option[A]] = {
    val u = perThread.get
    val root = txn.rootLevel
    if (u != null && (u._1 eq root))
      u._2
    else {
      val fresh = Ref(None: Option[A])
      perThread.set(root -> fresh)
      Txn.afterCompletion { status =>
        val u = perThread.get
        if (u != null && (u._1 eq root))
          perThread.remove
      }
      fresh
    }
  }

  /** Invoked if the value of this `TxnLocal` is read from a context in which
   *  it has not been previously accessed.
   */
  protected def initialValue(txn: InTxn): A = null.asInstanceOf[A]

  //////// SourceLike

  def get(implicit txn: InTxn): A = getImpl(perRoot)

  private def getImpl(ref: Ref[Option[A]])(implicit txn: InTxn): A = ref() match {
    case Some(v) => v
    case None => {
      val v = initialValue(txn)
      ref() = Some(v)
      v
    }
  }

  def getWith[Z](f: (A) => Z)(implicit txn: InTxn): Z = f(get)

  def relaxedGet(equiv: (A, A) => Boolean)(implicit txn: InTxn): A = get

  //////// SinkLike

  def set(v: A)(implicit txn: InTxn) = {
    perRoot() = Some(v)
  }

  def trySet(v: A)(implicit txn: InTxn): Boolean = { set(v) ; true }

  //////// RefLike

  def swap(v: A)(implicit txn: InTxn): A = {
    val ref = perRoot
    val z = getImpl(ref)
    ref() = Some(v)
    z
  }

  def transform(f: (A) => A)(implicit txn: InTxn) {
    val ref = perRoot
    ref() = Some(f(getImpl(ref)))
  }

  def transformIfDefined(pf: PartialFunction[A, A])(implicit txn: InTxn): Boolean = {
    val ref = perRoot
    val v0 = getImpl(ref)
    pf.isDefinedAt(v0) && { ref() = Some(pf(v0)) ; true }
  }
}
