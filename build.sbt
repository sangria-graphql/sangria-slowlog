name := "sangria-slowlog"
organization := "org.sangria-graphql"

mimaPreviousArtifacts := Set("org.sangria-graphql" %% "sangria-slowlog" % "2.0.0-M1")

description := "Sangria middleware to log slow GraphQL queries"
homepage := Some(url("http://sangria-graphql.org"))
licenses := Seq("Apache License, ASL Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))

scalaVersion := "2.13.0"
crossScalaVersions := Seq("2.12.10", scalaVersion.value)

scalacOptions ++= Seq("-deprecation", "-feature")

libraryDependencies ++= Seq(
  "org.sangria-graphql" %% "sangria" % "2.0.0-RC1",
  "io.dropwizard.metrics" % "metrics-core" % "4.1.12",
  "org.slf4j" % "slf4j-api" % "1.7.30",
  "io.opentracing.contrib" %% "opentracing-scala-concurrent" % "0.0.6",
  "io.opentracing" % "opentracing-mock" % "0.33.0" % Test,
  "org.scalatest" %% "scalatest" % "3.1.0" % Test,
  "org.sangria-graphql" %% "sangria-json4s-native" % "1.0.1" % Test,
  "org.slf4j" % "slf4j-simple" % "1.7.30" % Test
)

// Publishing

releaseCrossBuild := true
releasePublishArtifactsAction := PgpKeys.publishSigned.value
publishMavenStyle := true
publishArtifact in Test := false
pomIncludeRepository := (_ => false)
publishTo := Some(
  if (version.value.trim.endsWith("SNAPSHOT"))
    "snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
  else
    "releases" at "https://oss.sonatype.org/service/local/staging/deploy/maven2")
startYear := Some(2017)
organizationHomepage := Some(url("https://github.com/sangria-graphql"))
developers := Developer("OlegIlyenko", "Oleg Ilyenko", "", url("https://github.com/OlegIlyenko")) :: Nil
scmInfo := Some(ScmInfo(
  browseUrl = url("https://github.com/sangria-graphql-org/sangria-circe.git"),
  connection = "scm:git:git@github.com:sangria-graphql-org/sangria-circe.git"))

// nice *magenta* prompt!

shellPrompt in ThisBuild := { state =>
  scala.Console.MAGENTA + Project.extract(state).currentRef.project + "> " + scala.Console.RESET
}
