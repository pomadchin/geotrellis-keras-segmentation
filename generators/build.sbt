name := "keras-generators"

libraryDependencies ++= Seq(
  "org.locationtech.geotrellis" %% "geotrellis-spark-etl" % Version.geotrellis,
  "org.apache.spark" %% "spark-core"    % Version.spark % Provided,
  "org.apache.hadoop" % "hadoop-client" % Version.hadoop % Provided,
  "org.scalatest"    %% "scalatest"     % Version.scalaTest % Test
)
