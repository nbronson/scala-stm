package scala.concurrent.stm
package skel

object ConcurrentHashTrieSet {
  def main(args: Array[String]) {
    for (q <- 0 until 8) {
      val m = new ConcurrentHashTrieSet[Int]
      //val m = new SimpleTSet(Set.empty[Int, String])
      for (i <- 0 until 1000)
        m.add(i)
      val t0 = System.currentTimeMillis
      for (p <- 0 until 1000) {
        var i = 0
        while (i < 10000) {
          m.contains(-(i % 1000))
          i += 1
        }
      }
      println((System.currentTimeMillis - t0) / 10)
    }    
  }
}

class ConcurrentHashTrieSet[A] private (private val root: Ref.View[TxnHashTrie.Node[A, AnyRef]]) {

  def this() = this(Ref(TxnHashTrie.emptySetNode[A]).single)

  override def clone: ConcurrentHashTrieSet[A] = new ConcurrentHashTrieSet(TxnHashTrie.clone(root))

  def contains(key: A): Boolean = TxnHashTrie.contains(root, key)

  def add(key: A): Boolean = !TxnHashTrie.put(root, key, null).isEmpty
}
