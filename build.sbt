
name := "scala-stm"

organization := "org.scala-tools"

version := "0.4"

scalaVersion := "2.9.1"

crossScalaVersions := Seq("2.9.0-1", "2.9.0", "2.8.2", "2.8.1")

// 2.8.* -> 1.5.1, 2.9.* -> 1.6.1
libraryDependencies += ("org.scalatest" %% "scalatest" % "[1.5,)" % "test")

// skip exhaustive tests
testOptions += Tests.Argument("-l", "slow")

// test of TxnExecutor.transformDefault must be run by itself
parallelExecution in Test := false

////////////////////
// publishing

publishMavenStyle := true

publishTo <<= (version) { v: String =>
    val nexus = "http://nexus.scala-tools.org/content/repositories/"
    if (v.trim.endsWith("SNAPSHOT"))
      Some("snapshots" at nexus + "snapshots/") 
    else
      Some("releases" at nexus + "releases/")
  }

// exclude scalatest from the Maven POM
pomPostProcess := { xi: scala.xml.Node =>
    import scala.xml._
    val badDep = (xi \\ "dependency") find {
      x => (x \ "groupId").text == "org.scalatest"
    }
    def filt(root: Node): Node = root match {
      case x: Elem => {
        val ch = x.child filter { Some(_) != badDep } map { filt(_) }
        Elem(x.prefix, x.label, x.attributes, x.scope, ch: _*)
      }
      case x => x
    }
    filt(xi)
  }

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")
