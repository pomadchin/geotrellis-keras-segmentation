# Makefile to simplify project interaction.
TAG := $(shell git rev-parse --short HEAD)
INGEST_JAR := ingest/target/scala-2.11/keras-ingest-assembly-0.1.0-SNAPHOST.jar
SERVER_JAR := server/target/scala-2.11/keras-server-assembly-0.1.0-SNAPSHOT.jar

clean:
	./sbt ingest/clean && ./sbt server/clean

build:
	./sbt ingest/assembly && ./sbt server/assembly

# Local ingest, requires a valid Spark installation.
ingest: ${INGEST_JAR}
	spark-submit \
		--class com.azavea.Ingest --driver-memory=4G ${INGEST_JAR} \
		--input "file:///${PWD}/conf/input.json" \
		--output "file://${PWD}/conf/output.json" \
		--backend-profiles "file://${PWD}/conf/backend-profiles.json"

server: ${SERVER_JAR}
	java -cp ${SERVER_JAR} com.azavea.Main
