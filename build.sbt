name := "Distributed LSH"

version := "1.0"

scalaVersion := "2.12.1"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= Seq(
  "org.scalactic" %% "scalactic" % "3.0.0",
  "org.scalatest" %% "scalatest" % "3.0.0" % "test",
  "com.typesafe.akka" %% "akka-actor" % "2.4.17",
  "com.typesafe.akka" %% "akka-remote" % "2.4.17"
)

lazy val DistributedLSH = project in file(".") dependsOn RepetitionHandler

lazy val Utils = project in file("Utils")

lazy val RecallTest = project in file("RecallTest") dependsOn(DistributedLSH, RepetitionHandler, Utils)

lazy val MicroBenchmark = project in file("MicroBenchmark") dependsOn(DistributedLSH, RepetitionHandler, Utils) enablePlugins JmhPlugin

lazy val RepetitionHandler = project in file("RepetitionHandler") dependsOn Utils

