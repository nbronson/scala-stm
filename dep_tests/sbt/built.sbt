
name := "scala-stm-dep-tests-sbt"

organization := "org.scala-tools"

version := "0.1-SNAPSHOT"

scalaVersion := "2.9.1"

crossScalaVersions := Seq("2.9.1", "2.9.0-1", "2.9.0", "2.8.2", "2.8.1")

// resolvers += ScalaToolsSnapshots

libraryDependencies += ("org.scala-tools" %% "scala-stm" % "0.5")
