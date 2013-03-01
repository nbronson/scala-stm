
name := "scala-stm-dep-tests-sbt"

organization := "org.scala-stm"

version := "0.1-SNAPSHOT"

scalaVersion := "2.10.0"

crossScalaVersions := Seq("2.10.0", "2.9.3", "2.9.2")

resolvers += ("releases" at "http://oss.sonatype.org/content/repositories/releases")

// resolvers += ("snapshots" at "http://oss.sonatype.org/content/repositories/snapshots")

libraryDependencies += ("org.scala-stm" %% "scala-stm" % "0.7")
