/* scala-stm - (c) 2010, LAMP/EPFL */

import sbt._

class ScalaSTMProject(info: ProjectInfo) extends DefaultProject(info) {
  val scalatest = "org.scalatest" % "scalatest" % ("1.2-for-scala-" + crossScalaVersionString + "-SNAPSHOT")
  val scalaToolsSnapshots = "Scala-Tools Maven2 Snapshots Repository" at "http://scala-tools.org/repo-snapshots"
}
