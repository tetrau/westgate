name := "westgate"

version := "0.1"

scalaVersion := "2.13.1"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.6.3"
)

libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.14.1" % "test"
