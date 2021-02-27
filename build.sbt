name := "sangria-slowlog"
organization := "org.sangria-graphql"

mimaPreviousArtifacts := Set("org.sangria-graphql" %% "sangria-slowlog" % "2.0.0-M1")

description := "Sangria middleware to log slow GraphQL queries"
homepage := Some(url("http://sangria-graphql.org"))
licenses := Seq("Apache License, ASL Version 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))

// sbt-github-actions needs configuration in `ThisBuild`
ThisBuild / crossScalaVersions := Seq("2.12.12", "2.13.5")
ThisBuild / scalaVersion := crossScalaVersions.value.last
ThisBuild / githubWorkflowPublishTargetBranches := List()
ThisBuild / githubWorkflowBuildPreamble ++= List(
  WorkflowStep.Sbt(List("mimaReportBinaryIssues"), name = Some("Check binary compatibility")),
  WorkflowStep.Sbt(List("scalafmtCheckAll"), name = Some("Check formatting"))
)


scalacOptions += "-target:jvm-1.8"
javacOptions ++= Seq("-source", "8", "-target", "8")

scalacOptions ++= Seq("-deprecation", "-feature")

libraryDependencies ++= Seq(
  "org.sangria-graphql" %% "sangria" % "2.1.0",
  "io.dropwizard.metrics" % "metrics-core" % "4.1.18",
  "org.slf4j" % "slf4j-api" % "1.7.30",
  "io.opentracing.contrib" %% "opentracing-scala-concurrent" % "0.0.6",
  "io.opentracing" % "opentracing-mock" % "0.33.0" % Test,
  "org.scalatest" %% "scalatest" % "3.2.4" % Test,
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
  browseUrl = url("https://github.com/sangria-graphql/sangria-circe.git"),
  connection = "scm:git:git@github.com:sangria-graphql/sangria-circe.git"))

// nice *magenta* prompt!

shellPrompt in ThisBuild := { state =>
  scala.Console.MAGENTA + Project.extract(state).currentRef.project + "> " + scala.Console.RESET
}
