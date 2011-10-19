/* scala-stm - (c) 2010, Stanford University, PPL */

import scala.xml._
import sbt._
import java.io.File

class ScalaSTMProject(info: ProjectInfo) extends DefaultProject(info) {

  //// Compilation

  val scala_tools_snapshots = "Scala-Tools Maven2 Repository - snapshots" at "http://scala-tools.org/repo-snapshots"

  val scalatest = if (buildScalaVersion.startsWith("2.8")) {
    "org.scalatest" % "scalatest" % "1.3"
  } else if (buildScalaVersion == "2.9.0") {
    "org.scalatest" % "scalatest_2.9.0" % "1.4.1"
  } else {
    "org.scalatest" % ("scalatest_" + buildScalaVersion) % "1.6.1"
  }

  //// Testing

  override def testOptions = super.testOptions ++ Seq(TestArgument("-l", "slow"))


  //// Deployment

  override def managedStyle = ManagedStyle.Maven

  // scalatest isn't needed for the published JAR.  There's probably a cleaner
  // way to exclude it, but this works
  override def pomPostProcess(pom: Node): Node = {
    val dep = (pom \\ "dependency") find { x => (x \ "artifactId") == "scalatest" }
    val repo = (pom \\ "repository") find { x => (x \ "name").text endsWith "snapshots" }
    rm(pom, Set.empty[Node] ++ dep ++ repo)
  }

  private def rm(root: Node, targets: Set[Node]): Node = root match {
    case x: Elem => {
      val ch = x.child filter { !targets.contains(_) } map { rm(_, targets) }
      Elem(x.prefix, x.label, x.attributes, x.scope, ch: _*)
    }
    case x => x
  }

  val publishTo = if (version.toString endsWith "-SNAPSHOT") {
    "Scala Tools Nexus" at "http://nexus.scala-tools.org/content/repositories/snapshots/"
  } else {
    "Scala Tools Nexus" at "http://nexus.scala-tools.org/content/repositories/releases/"
  }

  Credentials(Path.userHome / ".ivy2" / ".credentials", log)
}
