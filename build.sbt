lazy val commonSettings = Seq(
  organization := "com.azavea",
  version := "0.1.0-SNAPHOST",
  scalaVersion := "2.11.8",
  scalacOptions ++= Seq(
    "-deprecation",
    "-unchecked",
    "-Yinline-warnings",
    "-language:implicitConversions",
    "-language:reflectiveCalls",
    "-language:higherKinds",
    "-language:postfixOps",
    "-language:existentials",
    "-feature"),
  outputStrategy := Some(StdoutOutput),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  fork := true,
  fork in Test := true,
  parallelExecution in Test := false,
  test in assembly := {},
  libraryDependencies += { scalaVersion ("org.scala-lang" % "scala-reflect" % _) }.value,
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full),
  ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) },
  resolvers ++=
    Seq(
      "geosolutions" at "http://maven.geo-solutions.it/",
      "osgeo" at "http://download.osgeo.org/webdav/geotools/" //,
     // "LocationTech GeoTrellis Snapshots" at "https://repo.locationtech.org/content/repositories/geotrellis-snapshots"
    ),
  assemblyShadeRules in assembly := {
    val shadePackage = "com.azavea.shaded.demo"
    Seq(
      ShadeRule.rename("com.google.common.**" -> s"$shadePackage.google.common.@1")
        .inLibrary(
          "com.azavea.geotrellis" %% "geotrellis-cassandra" % Version.geotrellis,
          "com.github.fge" % "json-schema-validator" % "2.2.6"
        ).inAll
    )
  },
  assemblyMergeStrategy in assembly := {
    case m if m.toLowerCase.endsWith("manifest.mf") => MergeStrategy.discard
    case m if m.toLowerCase.matches("meta-inf.*\\.sf$") => MergeStrategy.discard
    case "reference.conf" | "application.conf" => MergeStrategy.concat
    case _ => MergeStrategy.first
  }
)

lazy val root =
  Project("root", file("."))
    .aggregate(server, ingest)
    .settings(commonSettings: _*)

lazy val server = project.settings(commonSettings: _*)

lazy val ingest = project.settings(commonSettings: _*)
