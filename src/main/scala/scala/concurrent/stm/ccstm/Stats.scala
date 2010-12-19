/* scala-stm - (c) 2009-2010, Stanford University, PPL */

package scala.concurrent.stm.ccstm

import collection.mutable.ArrayBuffer

private[ccstm] object Stats {

  class LazyCounterMap[A] {
    import scala.collection.JavaConversions._

    private val _counters = new java.util.concurrent.ConcurrentHashMap[A, Counter]

    def += (k: A) {
      var v = _counters.get(k)
      if (v == null) {
        _counters.putIfAbsent(k, new Counter)
        v = _counters.get(k)
      }
      v += 1
    }

    def toStr(k: A): String = k.toString

    def contents: Seq[(String, Long)] = {
      val aa = _counters.entrySet.toSeq map { e => (toStr(e.getKey) -> e.getValue.apply()) }
      aa sortBy { -_._2 }
    }
  }

  class Histo(numBuckets: Int) {
    private val _sum = new Counter
    private val _buckets = Array.tabulate(numBuckets) { _ => new Counter }

    def += (value: Int) {
      if (value != 0) {
        _sum += value
        _buckets(bucketFor(value)) += 1
      }
    }

    protected def bucketFor(value: Int): Int = {
      if (value < 0 || value >= _buckets.length)
        _buckets.length - 1
      else
        value
    }

    def contents: Seq[Long] = {
      val snap = _buckets map { _.apply() }
      snap.take(1 + snap.lastIndexWhere { _ != 0L })
    }

    override def toString = {
      val s = _sum()
      val c = contents
      val count = c.foldLeft(0L)( _ + _ )
      val avg = if (count == 0) 0.0 else s * 1.0 / count
      "sum= %-10d count= %-8d avg= %-5.1f [%s]".format(s, count, avg, c.mkString(" "))
    }
  }

  class ExponentialHisto extends Histo(32) {
    override protected def bucketFor(value: Int): Int = {
      var v = value >>> 1
      var i = 0
      while (v != 0) {
        v >>>= 1
        i += 1
      }
      i
    }
  }

  class Level {
    val commits = new Counter
    val alternatives = new Histo(10)
    val retrySet = new ExponentialHisto
    val explicitRetries = new Counter
    val optimisticRetries = new LazyCounterMap[Symbol]
    val failures = new LazyCounterMap[Class[_]] { override def toStr(k: Class[_]) = k.getSimpleName }
    val commitReadSet = new ExponentialHisto
    val commitBargeSet = new ExponentialHisto
    val commitWriteSet = new ExponentialHisto
    val rollbackReadSet = new ExponentialHisto
    val rollbackBargeSet = new ExponentialHisto
    val rollbackWriteSet = new ExponentialHisto

    def contents: Seq[String] = {
      val buf = new ArrayBuffer[String]
      for (f <- getClass.getDeclaredFields) {
        val name = f.getName
        val value = getClass.getDeclaredMethod(name).invoke(this)
        value match {
          case c: Counter => buf += "%17s= %d".format(name, c())
          case m: LazyCounterMap[_] => {
            for ((k, v) <- m.contents)
              buf += "%17s (%s) = %d".format(name, k, v)
          }
          case h: Histo => buf += "%17s: %s".format(name, h)
        }
      }
      buf.result
    }

    def mkString(prefix: String): String = {
      prefix + ("-" * 64) + "\n" + contents.map( prefix + _ ).mkString("\n")
    }
  }

  val top = new Level
  val nested = new Level
  registerShutdownHook()

  private def registerShutdownHook() {
    Runtime.getRuntime.addShutdownHook(new Thread("shutdown stats printer") {
      override def run() { println(Stats) }
    })
  }

  override def toString() = {
    top.mkString("CCSTM: top: ") + "\n" + nested.mkString("CCSTM: nested: ")
  }
}