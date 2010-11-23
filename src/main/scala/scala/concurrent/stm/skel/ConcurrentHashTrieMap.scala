package scala.concurrent.stm
package skel

object ConcurrentHashTrieMap {
  def main(args: Array[String]) {
    for (q <- 0 until 8) {
      val m = new ConcurrentHashTrieMap[Int, String]
      //val m = new SimpleTMap(Map.empty[Int, String])
      for (i <- 0 until 1000)
        m.put(i, "foo")
      val t0 = System.currentTimeMillis
      for (p <- 0 until 1000) {
        var i = 0
        while (i < 10000) {
          m.get(-(i % 1000))
          i += 1
        }
      }
      println((System.currentTimeMillis - t0) / 10)
    }    
  }
}

class ConcurrentHashTrieMap[A, B] private (private val root: Ref.View[TxnHashTrie.Node[A, B]]) {

  def this() = this(Ref(TxnHashTrie.emptyMapNode[A, B]).single)

  override def clone: ConcurrentHashTrieMap[A, B] = new ConcurrentHashTrieMap(TxnHashTrie.clone(root)) 

  def clear() { root() = TxnHashTrie.emptyMapNode[A, B] }

  def contains(key: A): Boolean = TxnHashTrie.contains(root, key)

  def get(key: A): Option[B] = TxnHashTrie.get(root, key)

  def put(key: A, value: B): Option[B] = TxnHashTrie.put(root, key, value)

  def remove(key: A): Option[B] = TxnHashTrie.remove(root, key)

  def foreach(block: ((A, B)) => Unit) = TxnHashTrie.mapForeach(root, block)

  def iterator: Iterator[(A, B)] = TxnHashTrie.mapIterator(root)
}
