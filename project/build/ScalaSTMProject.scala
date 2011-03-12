/* scala-stm - (c) 2010, Stanford University, PPL */

import sbt._
import java.io.File

class ScalaSTMProject(info: ProjectInfo) extends DefaultProject(info) {

  //// Compilation

  val scala_tools_snapshots = "Scala-Tools Maven2 Repository - snapshots" at "http://scala-tools.org/repo-snapshots"

  val scalatest = if (buildScalaVersion.startsWith("2.9.")) {
    "org.scalatest" % "scalatest" % "1.4-SNAPSHOT"
  } else {
    "org.scalatest" % "scalatest" % "1.3"
  }

  //// Testing

  override def testOptions = super.testOptions ++ Seq(TestArgument("-l", "slow"))


  //// Deployment

  override def managedStyle = ManagedStyle.Maven

  val publishTo = if (version.toString endsWith "-SNAPSHOT") {
    "Scala Tools Nexus" at "http://nexus.scala-tools.org/content/repositories/snapshots/"
  } else {
    "Scala Tools Nexus" at "http://nexus.scala-tools.org/content/repositories/releases/"
  }

  Credentials(Path.userHome / ".ivy2" / ".credentials", log)
}
