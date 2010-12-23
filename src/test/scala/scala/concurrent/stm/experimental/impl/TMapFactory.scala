/* CCSTM - (c) 2009-2010 Stanford University - PPL */

// TMapFactory

package scala.concurrent.stm
package experimental
package impl

import reflect.ClassManifest


object TMapFactory {
  def apply[A,B](name: String)(implicit am: ClassManifest[A], bm: ClassManifest[B]): TMap[A,B] = {
    name match {
      case "u_h" => new UnlockedNonTxnMap[A,B](new java.util.HashMap[A,AnyRef])
      case "u_t" => new UnlockedNonTxnMap[A,B](new java.util.TreeMap[A,AnyRef])
      case "u_ch" => new UnlockedNonTxnMap[A,B](new java.util.concurrent.ConcurrentHashMap[A,AnyRef])
      case "u_csl" => new UnlockedNonTxnMap[A,B](new java.util.concurrent.ConcurrentSkipListMap[A,AnyRef])
      case "b_h_basic" => new BoostedHashMap_Basic[A,B]
      case "b_h_enum" => new BoostedHashMap_Enum[A,B]
      case "b_h_enum_rw" => new BoostedHashMap_Enum_RW[A,B]
      case "b_h_gc" => new BoostedHashMap_GC[A,B]
      case "b_h_gc_rw" => new BoostedHashMap_GC_RW[A,B]
      case "b_h_gc_enum" => new BoostedHashMap_GC_Enum[A,B]
      case "b_h_gc_enum_rw" => new BoostedHashMap_GC_Enum_RW[A,B]
      case "b_h_rc" => new BoostedHashMap_RC[A,B]
      case "b_h_rc_enum" => new BoostedHashMap_RC_Enum[A,B]
      case "b_h_lazy" => new BoostedHashMap_LazyGC[A,B]
      case "b_h_lazy_enum" => new BoostedHashMap_LazyGC_Enum[A,B]
      case "b_h_rw" => new BoostedHashMap_RW[A,B]
      case "p_h_gc" => new PredicatedHashMap_GC[A,B]
      case "t_h" => new ChainingHashMap[A,B]
      case "t_sh" => new StripedHashMap[A,B]
      case "snap" => TMap.empty[A, B]
    }
  }
}
