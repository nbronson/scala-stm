import sbt._

class DepTestsSBTProject(info: ProjectInfo) extends DefaultProject(info) {
  val scala_tools_snapshots = "Scala-Tools Maven2 Repository - snapshots" at "http://scala-tools.org/repo-snapshots"
  val scala_stm = "org.scala-tools" %% "scala-stm" % "0.4-SNAPSHOT"
}
