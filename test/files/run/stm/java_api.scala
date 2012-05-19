/* scala-stm - (c) 2009-2012, Stanford University, PPL */

import java.lang.reflect.Modifier

object Test {
  def main(args: Array[String]) {
    for (m <- classOf[JavaAPITests].getMethods) {
      if (Modifier.isStatic(m.getModifiers)) {
        println(m.getName + "...")
        m.invoke(null)
      }
    }
  }
}
