/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm


object TxnLocal {
  def apply[A](init: => A): TxnLocal[A] = new TxnLocal[A] {
    override def initialValue(txn: InTxn) = init
  }
}

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
