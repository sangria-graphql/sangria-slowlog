import com.typesafe.tools.mima.core.{DirectMissingMethodProblem, ProblemFilters}

name := "sangria-slowlog"
organization := "org.sangria-graphql"

val isScala3 = Def.setting(
  CrossVersion.partialVersion(scalaVersion.value).exists(_._1 == 3)
)

mimaPreviousArtifacts := {
  if (isScala3.value) Set.empty else Set("org.sangria-graphql" %% "sangria-slowlog" % "2.0.2")
}

description := "Sangria middleware to log slow GraphQL queries"
homepage := Some(url("https://sangria-graphql.github.io/"))
licenses := Seq(
  "Apache License, ASL Version 2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))

// sbt-github-actions needs configuration in `ThisBuild`
ThisBuild / crossScalaVersions := Seq("2.12.17", "2.13.10", "3.3.0")
ThisBuild / scalaVersion := crossScalaVersions.value.last
ThisBuild / githubWorkflowPublishTargetBranches := List()
ThisBuild / githubWorkflowBuildPreamble ++= List(
  WorkflowStep.Sbt(List("mimaReportBinaryIssues"), name = Some("Check binary compatibility")),
  WorkflowStep.Sbt(List("scalafmtCheckAll"), name = Some("Check formatting"))
)
// Binary Incompatible Changes, we'll document.
ThisBuild / mimaBinaryIssueFilters ++= Seq(
  ProblemFilters.exclude[DirectMissingMethodProblem]("sangria.slowlog.SlowLog.even")
)

scalacOptions += { if (isScala3.value) "-Xtarget:8" else "-target:jvm-1.8" }
javacOptions ++= Seq("-source", "8", "-target", "8")

scalacOptions ++= Seq("-deprecation", "-feature")

libraryDependencies ++= Seq(
  "org.sangria-graphql" %% "sangria" % "4.0.0",
  "io.dropwizard.metrics" % "metrics-core" % "4.2.18",
  "org.slf4j" % "slf4j-api" % "2.0.7",
  ("io.opentracing.contrib" %% "opentracing-scala-concurrent" % "0.0.6").cross(
    CrossVersion.for3Use2_13),
  "io.opentracing" % "opentracing-mock" % "0.33.0" % Test,
  "org.scalatest" %% "scalatest" % "3.2.16" % Test,
  "org.sangria-graphql" %% "sangria-json4s-native" % "1.0.2" % Test,
  "org.slf4j" % "slf4j-simple" % "2.0.7" % Test
)

// Publishing
ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches :=
  Seq(RefPredicate.StartsWith(Ref.Tag("v")))

ThisBuild / githubWorkflowPublish := Seq(
  WorkflowStep.Sbt(
    List("ci-release"),
    env = Map(
      "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
      "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
      "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
    )
  )
)

startYear := Some(2017)
organizationHomepage := Some(url("https://github.com/sangria-graphql"))
developers := Developer(
  "OlegIlyenko",
  "Oleg Ilyenko",
  "",
  url("https://github.com/OlegIlyenko")) :: Nil
scmInfo := Some(
  ScmInfo(
    browseUrl = url("https://github.com/sangria-graphql/sangria-slowlog"),
    connection = "scm:git:git@github.com:sangria-graphql/sangria-slowlog.git"))

// nice *magenta* prompt!
ThisBuild / shellPrompt := { state =>
  scala.Console.MAGENTA + Project.extract(state).currentRef.project + "> " + scala.Console.RESET
}
