/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// PredicatedHashMap_GC

package scala.concurrent.stm.experimental.impl

import scala.concurrent.stm.experimental.TMap
import scala.concurrent.stm.experimental.TMap.Bound
import scala.concurrent.stm.{STM, Txn}
import java.util.concurrent.ConcurrentHashMap

private object PredicatedHashMap_GC {
  private class TokenRef[A,B](map: PredicatedHashMap_GC[A,B], key: A, token: Token) extends CleanableRef[Token](token) {
    var pred: Predicate[B] = null
    def cleanup(): Unit = map.predicates.remove(key, pred)
  }

  private class Token

  // we extend from TIdentityPairRef opportunistically
  private class Predicate[B](val softRef: CleanableRef[Token], tok: Token, value: B) extends
      TIdentityPairRef[Token,B](tok, value) {
    def this(softRef0: CleanableRef[Token]) = this(softRef0, null, null.asInstanceOf[B])
  }
}

class PredicatedHashMap_GC[A,B] extends TMap[A,B] {
  import PredicatedHashMap_GC._

  private val predicates = new ConcurrentHashMap[A,Predicate[B]] {
    override def putIfAbsent(key: A, value: Predicate[B]): Predicate[B] = {
      STM.resurrect(key.hashCode, value)
      super.putIfAbsent(key, value)
    }

    override def replace(key: A, oldValue: Predicate[B], newValue: Predicate[B]): Boolean = {
      val h = key.hashCode
      STM.embalm(h, oldValue)
      STM.resurrect(h, newValue)
      super.replace(key, oldValue, newValue)
    }

    override def remove(key: Any, value: Any): Boolean = {
      STM.embalm(key.hashCode, value.asInstanceOf[Predicate[B]])
      super.remove(key, value)
    }
  }

  def escaped: Bound[A,B] = new TMap.AbstractNonTxnBound[A,B,PredicatedHashMap_GC[A,B]](this) {

    def get(key: A): Option[B] = {
      // p may be missing (null), no longer active (weakRef.get == null), or
      // active (weakRef.get != null).  We don't need to distinguish between
      // the last two cases, since they will both have a null txn Token ref and
      // both correspond to None
      val p = predicates.get(key)
      decodePair(if (null == p) null else p.escaped.get)
    }

    override def put(key: A, value: B): Option[B] = putImpl(key, value, predicates.get(key))

    private def putImpl(key: A, value: B, pred: Predicate[B]): Option[B] = {    
      val token = if (null == pred) null else pred.softRef.get
      if (null != token) {
        putImpl(value, token, pred)
      } else {
        val freshToken = new Token
        val freshRef = new TokenRef(unbind, key, freshToken)
        val freshPred = new Predicate[B](freshRef, freshToken, value)
        freshRef.pred = freshPred

        if (null == pred) {
          val racingPred = predicates.putIfAbsent(key, freshPred)
          if (null == racingPred) {
            // our predicate was installed, no isolation barrier necessary
            None
          } else {
            // we've got the predicate that beat us to it, try with that one
            putImpl(key, value, racingPred)
          }
        } else {
          if (predicates.replace(key, pred, freshPred)) {
            // successful
            None
          } else {
            // failure, but replace doesn't give us the old one
            putImpl(key, value, predicates.get(key))
          }
        }
      }
    }

    private def putImpl(value: B, token: Token, pred: Predicate[B]): Option[B] = {
      decodePair(pred.escaped.swap(IdentityPair(token, value)))
    }

    override def remove(key: A): Option[B] = {
      // if the pred is stale, then swap(None) is a no-op and doesn't harm
      // anything
      val p = predicates.get(key)
      decodePair(if (null == p) null else p.escaped.swap(null))
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

    def iterator: Iterator[(A,B)] = new Iterator[(A,B)] {
      val iter = predicates.keySet().iterator
      var avail: (A,B) = null
      advance()

      private def advance() {
        while (iter.hasNext) {
          val k = iter.next()
          get(k) match {
            case Some(v) => {
              avail = (k,v)
              return
            }
            case None => // keep looking
          }
        }
        avail = null
      }

      def hasNext: Boolean = null != avail
      def next(): (A,B) = {
        val z = avail
        advance()
        z
      }
    }
  }

  def bind(implicit txn0: Txn): Bound[A, B] = new TMap.AbstractTxnBound[A,B,PredicatedHashMap_GC[A,B]](txn0, this) {
    def iterator: Iterator[(A,B)] = throw new UnsupportedOperationException
  }

  def isEmpty(implicit txn: Txn): Boolean = throw new UnsupportedOperationException

  def size(implicit txn: Txn): Int = throw new UnsupportedOperationException

  def get(key: A)(implicit txn: Txn): Option[B] = {
    val pred = predicates.get(key)
    if (null != pred) {
      val pair = pred.get
      if (null != pair) {
        // no access to the soft ref necessary
        return Some(pair._2)
      }
    }
    return getImpl(key, pred)
  }

  private def getImpl(key: A, pred: Predicate[B])(implicit txn: Txn): Option[B] = {
    val token = if (null == pred) null else pred.softRef.get
    if (null != token) {
      getImpl(token, pred)
    } else {
      val freshToken = new Token
      val freshRef = new TokenRef(this, key, freshToken)
      val freshPred = new Predicate[B](freshRef)
      freshRef.pred = freshPred

      if (null == pred) {
        val racingPred = predicates.putIfAbsent(key, freshPred)
        if (null == racingPred) {
          // successful
          getImpl(freshToken, freshPred)
        } else {
          // we've got the predicate that beat us to it, try with that one
          getImpl(key, racingPred)
        }
      } else {
        if (predicates.replace(key, pred, freshPred)) {
          // successful
          getImpl(freshToken, freshPred)
        } else {
          // failure, but replace doesn't give us the old one
          getImpl(key, predicates.get(key))
        }
      }
    }
  }

  private def getImpl(token: Token, pred: Predicate[B])(implicit txn: Txn): Option[B] = {
    decodePairAndPin(token, pred.get)
  }
  
  
  def put(key: A, value: B)(implicit txn: Txn): Option[B] = putImpl(key, value, predicates.get(key))

  private def putImpl(key: A, value: B, pred: Predicate[B])(implicit txn: Txn): Option[B] = {    
    val token = if (null == pred) null else pred.softRef.get
    if (null != token) {
      putImpl(value, token, pred)
    } else {
      val freshToken = new Token
      val freshRef = new TokenRef(this, key, freshToken)
      val freshPred = new Predicate[B](freshRef)
      freshRef.pred = freshPred

      if (null == pred) {
        val racingPred = predicates.putIfAbsent(key, freshPred)
        if (null == racingPred) {
          // successful
          putImpl(value, freshToken, freshPred)
        } else {
          // we've got the predicate that beat us to it, try with that one
          putImpl(key, value, racingPred)
        }
      } else {
        if (predicates.replace(key, pred, freshPred)) {
          // successful
          putImpl(value, freshToken, freshPred)
        } else {
          // failure, but replace doesn't give us the old one
          putImpl(key, value, predicates.get(key))
        }
      }
    }
  }

  private def putImpl(value: B, token: Token, pred: Predicate[B])(implicit txn: Txn): Option[B] = {
    decodePair(pred.swap(IdentityPair(token, value)))
  }

  
  def remove(key: A)(implicit txn: Txn): Option[B] = removeImpl(key, predicates.get(key))

  private def removeImpl(key: A, pred: Predicate[B])(implicit txn: Txn): Option[B] = {
    val token = if (null == pred) null else pred.softRef.get
    if (null != token) {
      removeImpl(token, pred)
    } else {
      val freshToken = new Token
      val freshRef = new TokenRef(this, key, freshToken)
      val freshPred = new Predicate[B](freshRef)
      freshRef.pred = freshPred

      if (null == pred) {
        val racingPred = predicates.putIfAbsent(key, freshPred)
        if (null == racingPred) {
          // successful
          removeImpl(freshToken, freshPred)
        } else {
          // we've got the predicate that beat us to it, try with that one
          removeImpl(key, racingPred)
        }
      } else {
        if (predicates.replace(key, pred, freshPred)) {
          // successful
          removeImpl(freshToken, freshPred)
        } else {
          // failure, but replace doesn't give us the old one
          removeImpl(key, predicates.get(key))
        }
      }
    }
  }

  private def removeImpl(token: Token, pred: Predicate[B])(implicit txn: Txn): Option[B] = {
    decodePairAndPin(token, pred.swap(null))
  }
  
//  override def transform(key: A, f: (Option[B]) => Option[B])(implicit txn: Txn) {
//    val tok = activeToken(key)
//    // in some cases this is overkill, but it is always correct
//    txn.addReference(tok)
//    tok.pred.transform(liftF(tok, f))
//  }
//
//  override def transformIfDefined(key: A, pf: PartialFunction[Option[B],Option[B]])(implicit txn: Txn): Boolean = {
//    val tok = activeToken(key)
//    // in some cases this is overkill, but it is always correct
//    txn.addReference(tok)
//    tok.pred.transformIfDefined(liftPF(tok, pf))
//  }
//
//  protected def transformIfDefined(key: A,
//                                   pfOrNull: PartialFunction[Option[B],Option[B]],
//                                   f: Option[B] => Option[B])(implicit txn: Txn): Boolean = {
//    throw new Error
//  }

  //////////////// encoding and decoding into the pair

  private def encodePair(token: Token, vOpt: Option[B]): IdentityPair[Token,B] = {
    vOpt match {
      case Some(v) => IdentityPair(token, v)
      case None => null
    }
  }

  private def decodePair(pair: IdentityPair[Token,B]): Option[B] = {
    if (null == pair) None else Some(pair._2)
  }

  private def decodePairAndPin(token: Token, pair: IdentityPair[Token,B])(implicit txn: Txn): Option[B] = {
    if (null == pair) {
      // We need to make sure that this TPairRef survives until the end of the
      // transaction.
      txn.addReference(token)
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

//  private def existingPred(key: A): Predicate[B] = predicates.get(key)
//
//  private def activeToken(key: A): (Token,Predicate[B]) = activeToken(key, predicates.get(key))
//
//  private def activeToken(key: A, pred: Predicate[B]): (Token,Predicate[B]) = {
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
