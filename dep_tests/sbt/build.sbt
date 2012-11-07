
name := "scala-stm-dep-tests-sbt"

organization := "org.scala-stm"

version := "0.1-SNAPSHOT"

scalaVersion := "2.9.2"

crossScalaVersions := Seq("2.10.0-RC2", "2.9.2", "2.9.1", "2.9.0-1", "2.9.0", "2.8.2", "2.8.1")

resolvers += ("releases" at "http://oss.sonatype.org/content/repositories/releases")

resolvers += ("snapshots" at "http://oss.sonatype.org/content/repositories/snapshots")

libraryDependencies += ("org.scala-stm" %% "scala-stm" % "0.7-SNAPSHOT")
