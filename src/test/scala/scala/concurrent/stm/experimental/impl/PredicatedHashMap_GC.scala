/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// PredicatedHashMap_GC

package scala.concurrent.stm
package experimental
package impl

import java.util.concurrent.ConcurrentHashMap
import skel.TMapViaClone
import annotation.tailrec
import ccstm.CCSTMExtensions

private object PredicatedHashMap_GC {
  private class Predicate[A, B](map: PredicatedHashMap_GC[A, B], key: A, tok: Token, contents0: (Token, B)
                                 ) extends CleanableRef[Token](tok) {
    val contents = Ref(contents0)
    def cleanup(): Unit = map.predicates.remove(key, this)
  }

  private class Token extends (Txn.Status => Unit) {
    def apply(status: Txn.Status) {}
  }
}

class PredicatedHashMap_GC[A, B] extends AbstractTMap[A, B] {
  import PredicatedHashMap_GC._

  private val predicates = new ConcurrentHashMap[A, Predicate[A, B]] {

    override def putIfAbsent(key: A, value: Predicate[A, B]): Predicate[A, B] = {
      CCSTMExtensions.resurrect(key.hashCode, value.contents)
      super.putIfAbsent(key, value)
    }

    override def replace(key: A, oldValue: Predicate[A, B], newValue: Predicate[A, B]): Boolean = {
      val h = key.hashCode
      CCSTMExtensions.embalm(h, oldValue.contents)
      CCSTMExtensions.resurrect(h, newValue.contents)
      super.replace(key, oldValue, newValue)
    }

    override def remove(key: Any, value: Any): Boolean = {
      CCSTMExtensions.embalm(key.hashCode, value.asInstanceOf[Predicate[A, B]].contents)
      super.remove(key, value)
    }
  }

  //// TMap.View stuff

  def nonTxnGet(key: A): Option[B] = {
    // p may be missing (null), no longer active (weakRef.get == null), or
    // active (weakRef.get != null).  We don't need to distinguish between
    // the last two cases, since they will both have a null txn Token ref and
    // both correspond to None
    val p = predicates.get(key)
    decodePair(if (null == p) null else p.contents.single.get)
  }

  def nonTxnPut(key: A, value: B): Option[B] = singlePutImpl(key, value, predicates.get(key))

  @tailrec private def singlePutImpl(key: A, value: B, pred: Predicate[A, B]): Option[B] = {
    val token = if (null == pred) null else pred.get
    if (null != token) {
      singlePutImpl(value, token, pred)
    } else {
      val freshToken = new Token
      val freshPred = new Predicate[A, B](this, key, freshToken, (freshToken -> value))

      if (null == pred) {
        val existingPred = predicates.putIfAbsent(key, freshPred)
        if (null == existingPred) {
          // our predicate was installed, no isolation barrier necessary
          None
        } else {
          // we've got the predicate that beat us to it, try with that one
          singlePutImpl(key, value, existingPred)
        }
      } else {
        if (predicates.replace(key, pred, freshPred)) {
          // successful
          None
        } else {
          // failure, but replace doesn't give us the old one
          singlePutImpl(key, value, predicates.get(key))
        }
      }
    }
  }

  private def singlePutImpl(value: B, token: Token, pred: Predicate[A, B]): Option[B] = {
    decodePair(pred.contents.single.swap(token -> value))
  }

  def nonTxnRemove(key: A): Option[B] = {
    // if the pred is stale, then swap(null) is a no-op and doesn't harm
    // anything
    val p = predicates.get(key)
    decodePair(if (null == p) null else p.contents.single.swap(null))
  }

//    override def transform(key: A, f: (Option[B]) => Option[B]) {
//      val tok = activeToken(key)
//      tok.pred.escaped.transform(liftF(tok, f))
//    }
//
//    override def transformIfDefined(key: A, pf: PartialFunction[Option[B],Option[B]]): Boolean = {
//      val tok = activeToken(key)
//      tok.pred.escaped.transformIfDefined(liftPF(tok, pf))
//    }
//
//    protected def transformIfDefined(key: A,
//                                     pfOrNull: PartialFunction[Option[B],Option[B]],
//                                     f: Option[B] => Option[B]): Boolean = {
//      throw new Error
//    }

//    def iterator: Iterator[(A,B)] = new Iterator[(A,B)] {
//      val iter = predicates.keySet().iterator
//      var avail: (A,B) = null
//      advance()
//
//      private def advance() {
//        while (iter.hasNext) {
//          val k = iter.next()
//          get(k) match {
//            case Some(v) => {
//              avail = (k,v)
//              return
//            }
//            case None => // keep looking
//          }
//        }
//        avail = null
//      }
//
//      def hasNext: Boolean = null != avail
//      def next(): (A,B) = {
//        val z = avail
//        advance()
//        z
//      }
//    }

  //// TMap stuff

  override def get(key: A)(implicit txn: InTxn): Option[B] = {
    val pred = predicates.get(key)
    if (null != pred) {
      val pair = pred.contents.get
      if (null != pair) {
        // no access to the soft ref necessary
        return Some(pair._2)
      }
    }
    return txnGetImpl(key, pred)
  }

  @tailrec private def txnGetImpl(key: A, pred: Predicate[A, B])(implicit txn: InTxn): Option[B] = {
    val token = if (null == pred) null else pred.get
    if (null != token) {
      txnGetImpl(token, pred)
    } else {
      val freshToken = new Token
      val freshPred = new Predicate[A, B](this, key, freshToken, null)

      if (null == pred) {
        val existingPred = predicates.putIfAbsent(key, freshPred)
        if (null == existingPred) {
          // successful
          txnGetImpl(freshToken, freshPred)
        } else {
          // we've got the predicate that beat us to it, try with that one
          txnGetImpl(key, existingPred)
        }
      } else {
        if (predicates.replace(key, pred, freshPred)) {
          // successful
          txnGetImpl(freshToken, freshPred)
        } else {
          // failure, but replace doesn't give us the old one
          txnGetImpl(key, predicates.get(key))
        }
      }
    }
  }

  private def txnGetImpl(token: Token, pred: Predicate[A, B])(implicit txn: InTxn): Option[B] = {
    decodePairAndPin(token, pred.contents.get)
  }
  
  
  override def put(key: A, value: B)(implicit txn: InTxn): Option[B] = txnPutImpl(key, value, predicates.get(key))

  @tailrec private def txnPutImpl(key: A, value: B, pred: Predicate[A, B])(implicit txn: InTxn): Option[B] = {
    val token = if (null == pred) null else pred.get
    if (null != token) {
      txnPutImpl(value, token, pred)
    } else {
      val freshToken = new Token
      val freshPred = new Predicate[A, B](this, key, freshToken, null)

      if (null == pred) {
        val existingPred = predicates.putIfAbsent(key, freshPred)
        if (null == existingPred) {
          // successful
          txnPutImpl(value, freshToken, freshPred)
        } else {
          // we've got the predicate that beat us to it, try with that one
          txnPutImpl(key, value, existingPred)
        }
      } else {
        if (predicates.replace(key, pred, freshPred)) {
          // successful
          txnPutImpl(value, freshToken, freshPred)
        } else {
          // failure, but replace doesn't give us the old one
          txnPutImpl(key, value, predicates.get(key))
        }
      }
    }
  }

  private def txnPutImpl(value: B, token: Token, pred: Predicate[A, B])(implicit txn: InTxn): Option[B] = {
    decodePair(pred.contents.swap(token -> value))
  }

  
  override def remove(key: A)(implicit txn: InTxn): Option[B] = txnRemoveImpl(key, predicates.get(key))

  @tailrec private def txnRemoveImpl(key: A, pred: Predicate[A, B])(implicit txn: InTxn): Option[B] = {
    val token = if (null == pred) null else pred.get
    if (null != token) {
      txnRemoveImpl(token, pred)
    } else {
      val freshToken = new Token
      val freshPred = new Predicate[A, B](this, key, freshToken, null)

      if (null == pred) {
        val existingPred = predicates.putIfAbsent(key, freshPred)
        if (null == existingPred) {
          // successful
          txnRemoveImpl(freshToken, freshPred)
        } else {
          // we've got the predicate that beat us to it, try with that one
          txnRemoveImpl(key, existingPred)
        }
      } else {
        if (predicates.replace(key, pred, freshPred)) {
          // successful
          txnRemoveImpl(freshToken, freshPred)
        } else {
          // failure, but replace doesn't give us the old one
          txnRemoveImpl(key, predicates.get(key))
        }
      }
    }
  }

  private def txnRemoveImpl(token: Token, pred: Predicate[A, B])(implicit txn: InTxn): Option[B] = {
    decodePairAndPin(token, pred.contents.swap(null))
  }
  
//  override def transform(key: A, f: (Option[B]) => Option[B])(implicit txn: InTxn) {
//    val tok = activeToken(key)
//    // in some cases this is overkill, but it is always correct
//    txn.addReference(tok)
//    tok.pred.transform(liftF(tok, f))
//  }
//
//  override def transformIfDefined(key: A, pf: PartialFunction[Option[B],Option[B]])(implicit txn: InTxn): Boolean = {
//    val tok = activeToken(key)
//    // in some cases this is overkill, but it is always correct
//    txn.addReference(tok)
//    tok.pred.transformIfDefined(liftPF(tok, pf))
//  }
//
//  protected def transformIfDefined(key: A,
//                                   pfOrNull: PartialFunction[Option[B],Option[B]],
//                                   f: Option[B] => Option[B])(implicit txn: InTxn): Boolean = {
//    throw new Error
//  }

  //////////////// encoding and decoding into the pair

  private def encodePair(token: Token, vOpt: Option[B]): (Token, B) = {
    vOpt match {
      case Some(v) => (token -> v)
      case None => null
    }
  }

  private def decodePair(pair: (Token, B)): Option[B] = {
    if (null == pair) None else Some(pair._2)
  }

  private def decodePairAndPin(token: Token, pair: (Token, B))(implicit txn: InTxn): Option[B] = {
    if (null == pair) {
      // We need to make sure that this token survives until the end of the
      // transaction.
      Txn.afterRollback(token)
      None
    } else {
      // The token will survive on its own until the commit of a remove,
      // because it is has a strong ref via the transactional state.  If the
      // removal does happen it will invalidate this txn correctly.
      Some(pair._2)
    }
  }

//  private def liftF(token: Token[A,B], f: Option[B] => Option[B]) = (pair: Token[A,B],B)) => encodePair(token, f(decodePair(pair)))
//
//  private def liftPF(token: Token[A,B], pf: PartialFunction[Option[B],Option[B]]) = new PartialFunction[(Token[A,B],B),(Token[A,B],B)] {
//    def isDefinedAt(pair: (Token[A,B],B)) = pf.isDefinedAt(decodePair(pair))
//    def apply(pair: (Token[A,B],B)) = encodePair(token, pf(decodePair(pair)))
//  }

  //////////////// predicate management

//  private def existingPred(key: A): Predicate[A, B] = predicates.get(key)
//
//  private def activeToken(key: A): (Token,Predicate[A, B]) = activeToken(key, predicates.get(key))
//
//  private def activeToken(key: A, pred: Predicate[A, B]): (Token,Predicate[A, B]) = {
//    val token = if (null == pred) null else pred.softRef.get
//    if (null != token) {
//      (token, pred)
//    } else {
//      val freshToken = new Token
//      val freshRef = new TokenRef(this, key, freshToken)
//      val freshPred = new Predicate(freshRef)
//      freshRef.pred = freshPred
//
//      if (null == pred) {
//        val racingPred = predicates.putIfAbsent(key, freshPred)
//        if (null == racingPred) {
//          // successful
//          (freshToken, freshPred)
//        } else {
//          // we've got the predicate that beat us to it, try with that one
//          activeToken(key, racingPred)
//        }
//      } else {
//        if (predicates.replace(key, existing, freshPred)) {
//          // successful
//          (freshToken, freshPred)
//        } else {
//          // failure, but replace doesn't give us the old one
//          activeToken(key, predicates.get(key))
//        }
//      }
//    }
//  }
}
