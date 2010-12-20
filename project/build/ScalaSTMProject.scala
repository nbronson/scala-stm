/* scala-stm - (c) 2010, Stanford University, PPL */

import sbt._

class ScalaSTMProject(info: ProjectInfo) extends DefaultProject(info) {

  //// Compilation

  val scalatest = "org.scalatest" % "scalatest" % "1.2"


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
