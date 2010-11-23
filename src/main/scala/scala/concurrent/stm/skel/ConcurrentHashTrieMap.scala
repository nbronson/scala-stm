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

  def contains(key: A): Boolean = TxnHashTrie.contains(root, key)

  def get(key: A): Option[B] = TxnHashTrie.get(root, key)

  def put(key: A, value: B): Option[B] = TxnHashTrie.put(root, key, value)
//
//  def remove(key: A): Option[B] = {
//    val r = root.value
//    remove(r._1, r._2, 0, 0, keyHash(key), key, false)
//  }
//
//  @tailrec private def remove(gen: Long, a: Array[Node[A, B]], i: Int, shift: Int, hash: Int, key: A, checked: Boolean): Option[B] = {
//    a(i) match {
//      case leaf: Leaf[A, B] => {
//        val after = leaf.withRemove(hash, key)
//        if (after eq leaf)
//          None // no change, key must not have been present
//        else if (n.casi(leaf, { root.value._1 == gen }, after))
//          leaf.get(hash, key)
//        else if (root.value._1 == gen)
//          remove(gen, a, i, shift, hash, key, true) // retry locally
//        else {
//          val r = root.value
//          remove(r._1, r._2, 0, 0, hash, key, true) // retry completely
//        }
//      }
//      case branch: Branch[A, B] => {
//        if (branch.gen == gen)
//          remove(gen, branch.children, indexFor(shift, hash), shift + LogBF, hash, key, checked)
//        else {
//          // no use in cloning paths if the key isn't actually present
//          if (!checked && !contains(branch.children, indexFor(shift, hash), shift + LogBF, hash, key))
//            None
//          else {
//            n.casi(branch, branch.clone(gen))
//            // try again, either picking up our improvement or someone else's
//            remove(gen, a, i, shift, hash, key, true)
//          }
//        }
//      }
//    }
//  }

}
