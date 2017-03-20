name := "keras-generators"

libraryDependencies ++= Seq(
  "org.zeroturnaround" % "zt-zip" % "1.11",
  "org.locationtech.geotrellis" %% "geotrellis-spark-etl" % Version.geotrellis,
  "org.apache.spark" %% "spark-core"    % Version.spark % Provided,
  "org.apache.hadoop" % "hadoop-client" % Version.hadoop % Provided,
  "org.scalatest"    %% "scalatest"     % Version.scalaTest % Test
)
