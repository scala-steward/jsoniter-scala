import sbt._
import scala.sys.process._

lazy val oldVersion = "git describe --abbrev=0".!!.trim.replaceAll("^v", "")

lazy val commonSettings = Seq(
  organization := "com.github.plokhotnyuk.jsoniter-scala",
  organizationHomepage := Some(url("https://github.com/plokhotnyuk")),
  homepage := Some(url("https://github.com/plokhotnyuk/jsoniter-scala")),
  licenses := Seq(("MIT License", url("https://opensource.org/licenses/mit-license.html"))),
  startYear := Some(2017),
  developers := List(
    Developer(
      id = "plokhotnyuk",
      name = "Andriy Plokhotnyuk",
      email = "plokhotnyuk@gmail.com",
      url = url("https://twitter.com/aplokhotnyuk")
    )
  ),
  resolvers ++= Seq(
    Resolver.mavenLocal,
    Resolver.sonatypeRepo("staging")
  ),
  scalaVersion := "2.13.1",
  javacOptions ++= Seq("-source", "1.8", "-target", "1.8"),
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding", "UTF-8",
    "-target:jvm-1.8",
    "-feature",
    "-unchecked",
    "-Ywarn-dead-code",
    "-Xlint",
    "-Xmacro-settings:" + sys.props.getOrElse("macro.settings", "none")
  ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 11)) => Seq(
      "-language:higherKinds",
      "-Ybackend:GenBCode",
      "-Ydelambdafy:inline"
    )
    case _ => Seq()
  }),
  compileOrder := CompileOrder.JavaThenScala,
  testOptions in Test += Tests.Argument("-oDF"),
  sonatypeProfileName := "com.github.plokhotnyuk",
  publishTo := sonatypePublishToBundle.value,
  publishMavenStyle := true,
  pomIncludeRepository := { _ => false },
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/plokhotnyuk/jsoniter-scala"),
      "scm:git@github.com:plokhotnyuk/jsoniter-scala.git"
    )
  )
)

lazy val noPublishSettings = Seq(
  skip in publish := true,
  mimaPreviousArtifacts := Set()
)

lazy val publishSettings = Seq(
  mimaCheckDirection := {
    def isPatch: Boolean = {
      val Array(newMajor, newMinor, _) = version.value.split('.')
      val Array(oldMajor, oldMinor, _) = oldVersion.split('.')
      newMajor == oldMajor && newMinor == oldMinor
    }

    if (isPatch) "both" else "backward"
  },
  mimaPreviousArtifacts := {
    def isCheckingRequired: Boolean = {
      val Array(newMajor, _, _) = version.value.split('.')
      val Array(oldMajor, _, _) = oldVersion.split('.')
      newMajor == oldMajor
    }

    if (isCheckingRequired) Set(organization.value %% moduleName.value % oldVersion)
    else Set()
  },
  mimaReportSignatureProblems := true
)

lazy val `jsoniter-scala` = project.in(file("."))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .aggregate(`jsoniter-scala-core`, `jsoniter-scala-macros`, `jsoniter-scala-benchmark`)

lazy val `jsoniter-scala-core` = project
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    crossScalaVersions := Seq("2.13.1", "2.12.11", "2.11.12"),
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.expression-evaluator" %% "expression-evaluator" % "0.1.2" % "compile-internal",
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.4" % Test,
      "org.scalatestplus" %% "scalacheck-1-14" % "3.1.1.1" % Test,
      "org.scalatest" %% "scalatest" % "3.1.1" % Test
    )
  )

lazy val `jsoniter-scala-macros` = project
  .dependsOn(`jsoniter-scala-core`)
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    crossScalaVersions := Seq("2.13.1", "2.12.11", "2.11.12"),
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
      "org.scalatest" %% "scalatest" % "3.1.1" % Test
    )
  )

lazy val `jsoniter-scala-benchmark` = project
  .enablePlugins(JmhPlugin)
  .dependsOn(`jsoniter-scala-macros`)
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    resolvers += "Rally Health" at "https://dl.bintray.com/rallyhealth/maven",
    crossScalaVersions := Seq("2.13.1", "2.12.11"),
    libraryDependencies ++= Seq(
      "com.rallyhealth" %% "weepickle-v1" % "1.0.1",
      "io.bullet" %% "borer-derivation" % "1.5.0",
      "pl.iterators" %% "kebs-spray-json" % "1.7.1",
      "io.spray" %%  "spray-json" % "1.3.5",
      "com.avsystem.commons" %% "commons-core" % "2.0.0-M5",
      "com.lihaoyi" %% "upickle" % "1.0.0",
      "com.dslplatform" %% "dsl-json-scala" % "1.9.5",
      "com.jsoniter" % "jsoniter" % "0.9.23",
      "org.javassist" % "javassist" % "3.26.0-GA", // required for Jsoniter Java
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.10.3",
      "com.fasterxml.jackson.module" % "jackson-module-afterburner" % "2.10.3",
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % "2.10.3",
      "io.circe" %% "circe-generic-extras" % "0.13.0",
      "io.circe" %% "circe-generic" % "0.13.0",
      "io.circe" %% "circe-parser" % "0.13.0",
      "com.typesafe.play" %% "play-json" % "2.8.1",
      "org.julienrf" %% "play-json-derived-codecs" % "7.0.0",
      "ai.x" %% "play-json-extensions" % "0.42.0",
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.4",
      "pl.project13.scala" % "sbt-jmh-extras" % "0.3.7",
      "org.openjdk.jmh" % "jmh-core" % "1.23",
      "org.openjdk.jmh" % "jmh-generator-asm" % "1.23",
      "org.openjdk.jmh" % "jmh-generator-bytecode" % "1.23",
      "org.openjdk.jmh" % "jmh-generator-reflection" % "1.23",
      "org.scalatest" %% "scalatest" % "3.1.1" % Test
    )
  )
