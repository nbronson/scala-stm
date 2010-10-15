/* scala-stm - (c) 2010, LAMP/EPFL */

package scala.concurrent.stm

/** The presence of an implicit `InTxn` instance grants the caller permission
 *  to perform transactional reads and writes on `Ref` instances, as well as
 *  permission to call `object Txn` methods that require an `InTxnEnd`.
 *  `InTxn` instances themselves might be reused by the STM, use
 *  `Txn.currentLevel` or `Txn.rootLevel` to get a `Txn.NestingLevel` if you
 *  need to track an individual execution attempt.
 */
trait InTxn extends InTxnEnd
