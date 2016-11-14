
name := "scala-stm"

organization := "org.scala-stm"

version := "0.7"

scalaVersion := "2.12.0"

//scalaBinaryVersion := "2.11.0-M8"

crossScalaVersions := Seq("2.12.0", "2.11.8")

//libraryDependencies += ("org.scalatest" % "scalatest_2.10.0-RC5" % "[1.5,)" % "test")
libraryDependencies += ("org.scalatest" %% "scalatest" % "3.0.1" % "test")

libraryDependencies += ("junit" % "junit" % "4.12" % "test")

// skip exhaustive tests
testOptions += Tests.Argument("-l", "slow")

// test of TxnExecutor.transformDefault must be run by itself
parallelExecution in Test := false

// make sure Java classes use 1.8 file format
javacOptions in (Compile, compile) ++= Seq("-source", "1.8", "-target", "1.8")

////////////////////
// publishing

pomExtra :=
  <url>http://nbronson.github.com/scala-stm/</url>
  <licenses>
    <license>
      <name>BSD</name>
      <url>https://github.com/nbronson/scala-stm/blob/master/LICENSE.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <connection>scm:git:git@github.com:nbronson/scala-stm.git</connection>
    <url>git@github.com:nbronson/scala-stm.git</url>
  </scm>
  <developers>
    <developer>
      <id>nbronson</id>
      <name>Nathan Bronson</name>
      <email>ngbronson@gmail.com</email>
    </developer>
  </developers>

publishMavenStyle := true

publishTo <<= (version) { v: String =>
    val base = "https://oss.sonatype.org/"
    if (v.trim.endsWith("SNAPSHOT"))
      Some("snapshots" at base + "content/repositories/snapshots/")
    else
      Some("releases" at base + "service/local/staging/deploy/maven2/")
  }

// exclude scalatest from the Maven POM
/*pomPostProcess := { xi: scala.xml.Node =>
    import scala.xml._
    val badDeps = (xi \\ "dependency") filter {
      x => (x \ "artifactId").text != "scala-library"
    } toSet
    def filt(root: Node): Node = root match {
      case x: Elem => {
        val ch = x.child filter { !badDeps(_) } map { filt(_) }
        Elem(x.prefix, x.label, x.attributes, x.scope, ch: _*)
      }
      case x => x
    }
    filt(xi)
  }
*/
//credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

import scala.util.matching.Regex
import scala.util.matching.Regex.Match

val externalJavadocMap = Map(
  "owlapi" -> "http://owlcs.github.io/owlapi/apidocs_4_0_2/index.html"
)

/*
 * The rt.jar file is located in the path stored in the sun.boot.class.path system property.
 * See the Oracle documentation at http://docs.oracle.com/javase/6/docs/technotes/tools/findingclasses.html.
 */
val rtJar: String = System.getProperty("sun.boot.class.path").split(java.io.File.pathSeparator).collectFirst {
  case str: String if str.endsWith(java.io.File.separator + "rt.jar") => str
}.get // fail hard if not found

val javaApiUrl: String = "http://docs.oracle.com/javase/8/docs/api/index.html"

val allExternalJavadocLinks: Seq[String] = javaApiUrl +: externalJavadocMap.values.toSeq

def javadocLinkRegex(javadocURL: String): Regex = ("""\"(\Q""" + javadocURL + """\E)#([^"]*)\"""").r

def hasJavadocLink(f: File): Boolean = allExternalJavadocLinks exists {
  javadocURL: String => 
    (javadocLinkRegex(javadocURL) findFirstIn IO.read(f)).nonEmpty
}

val fixJavaLinks: Match => String = m =>
  m.group(1) + "?" + m.group(2).replace(".", "/") + ".html"

/* You can print the classpath with `show compile:fullClasspath` in the SBT REPL.
 * From that list you can find the name of the jar for the managed dependency.
 */
lazy val documentationSettings = Seq(
  apiMappings ++= {
    // Lookup the path to jar from the classpath
    val classpath = (fullClasspath in Compile).value
    def findJar(nameBeginsWith: String): File = {
      classpath.find { attributed: Attributed[File] => (attributed.data ** s"$nameBeginsWith*.jar").get.nonEmpty }.get.data // fail hard if not found
    }
    // Define external documentation paths
    (externalJavadocMap map {
      case (name, javadocURL) => findJar(name) -> url(javadocURL)
    }) + (file(rtJar) -> url(javaApiUrl))
  },
  // Override the task to fix the links to JavaDoc
  doc in Compile <<= (doc in Compile) map {
    target: File =>
      (target ** "*.html").get.filter(hasJavadocLink).foreach { f => 
        //println(s"Fixing $f.")
        val newContent: String = allExternalJavadocLinks.foldLeft(IO.read(f)) {
          case (oldContent: String, javadocURL: String) =>
            javadocLinkRegex(javadocURL).replaceAllIn(oldContent, fixJavaLinks)
        }
        IO.write(f, newContent)
      }
      target
  }
)