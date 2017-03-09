include config-aws.mk # Vars related to AWS credentials and services used
include config-emr.mk # Vars related to type and size of EMR cluster
include config-run.mk # Vars related to ingest step and spark parameters

INGEST_ASSEMBLY := ingest/target/scala-2.11/keras-ingest-assembly-0.1.0-SNAPSHOT.jar
SERVER_ASSEMBLY := server/target/scala-2.11/keras-server-assembly-0.1.0-SNAPSHOT.jar
SCRIPT_RUNNER := s3://elasticmapreduce/libs/script-runner/script-runner.jar
STATIC := ./static

ifeq ($(USE_SPOT),true)
MASTER_BID_PRICE:=BidPrice=${MASTER_PRICE},
WORKER_BID_PRICE:=BidPrice=${WORKER_PRICE},
BACKEND=accumulo
endif

ifdef COLOR
COLOR_TAG=--tags Color=${COLOR}
endif

ifndef CLUSTER_ID
CLUSTER_ID=$(shell if [ -e "cluster-id.txt" ]; then cat cluster-id.txt; fi)
endif

rwildcard=$(foreach d,$(wildcard $1*),$(call rwildcard,$d/,$2) $(filter $(subst *,%,$2),$d))

${INGEST_ASSEMBLY}: $(call rwildcard, ingest/src, *.scala, *.conf, *.sbt) build.sbt
	./sbt ingest/assembly -no-colors
	@touch -m ${INGEST_ASSEMBLY}

${SERVER_ASSEMBLY}: $(call rwildcard, server/src, *.scala, *.conf, *.sbt) build.sbt
	./sbt server/assembly -no-colors
	@touch -m ${SERVER_ASSEMBLY}

viewer/site.tgz: $(call rwildcard, viewer/components, *.js)
	@cd viewer && npm install && npm run build
	tar -czf viewer/site.tgz -C viewer/dist .

upload-code: ${SERVER_ASSEMBLY} ${INGEST_ASSEMBLY} scripts/emr/* viewer/site.tgz
	@aws s3 cp viewer/site.tgz ${S3_URI}/
	@aws s3 cp scripts/emr/bootstrap-demo.sh ${S3_URI}/
	@aws s3 cp conf/backend-profiles.json ${S3_URI}/
	@aws s3 cp conf/input.json ${S3_URI}/
	@aws s3 cp conf/output.json ${S3_URI}/output.json
	@aws s3 cp ${SERVER_ASSEMBLY} ${S3_URI}/
	@aws s3 cp ${INGEST_ASSEMBLY} ${S3_URI}/

create-cluster:
	aws emr create-cluster --name "${NAME}" ${COLOR_TAG} \
--release-label emr-5.0.0 \
--output text \
--use-default-roles \
--configurations "file://$(CURDIR)/scripts/configurations.json" \
--log-uri ${S3_URI}/logs \
--ec2-attributes KeyName=${EC2_KEY},SubnetId=${SUBNET_ID} \
--applications Name=Ganglia Name=Hadoop Name=Hue Name=Spark Name=Zeppelin \
--instance-groups \
'Name=Master,${MASTER_BID_PRICE}InstanceCount=1,InstanceGroupType=MASTER,InstanceType=${MASTER_INSTANCE}' \
'Name=Workers,${WORKER_BID_PRICE}InstanceCount=${WORKER_COUNT},InstanceGroupType=CORE,InstanceType=${WORKER_INSTANCE}' | tee cluster-id.txt

clean:
	./sbt ingest/clean && ./sbt server/clean

build:
	./sbt ingest/assembly -no-colors && ./sbt server/assembly -no-colors

ingest: ${INGEST_ASSEMBLY}
	aws emr add-steps --output text --cluster-id ${CLUSTER_ID} \
--steps Type=CUSTOM_JAR,Name="Ingest Keras",Jar=command-runner.jar,Args=[\
spark-submit,--master,yarn-cluster,\
--class,com.azavea.ingest.Ingest,\
--driver-memory,${DRIVER_MEMORY},\
--driver-cores,${DRIVER_CORES},\
--executor-memory,${EXECUTOR_MEMORY},\
--executor-cores,${EXECUTOR_CORES},\
--conf,spark.dynamicAllocation.enabled=true,\
--conf,spark.yarn.executor.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.yarn.driver.memoryOverhead=${YARN_OVERHEAD},\
${S3_URI}/keras-ingest-assembly-0.1.0-SNAPHOST.jar,\
--input,"file:///tmp/input.json",\
--output,"file:///tmp/output.json",\
--backend-profiles,"file:///tmp/backend-profiles.json"\
] | cut -f2 | tee last-step-id.txt

run-server: ${SERVER_ASSEMBLY}
	aws emr put --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem" \
	--src ${STATIC} --dest /tmp
	aws emr put --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem" \
	--src ${SERVER_ASSEMBLY} --dest /tmp
	aws emr put --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem" \
	--src scripts/run-server.sh --dest /tmp
	aws emr ssh --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem" \
	--command /tmp/run-server.sh

# Local ingest, requires a valid Spark installation.
ingest-local: ${INGEST_ASSEMBLY}
	spark-submit \
		--class com.azavea.ingest.Ingest --driver-memory=4G ${INGEST_ASSEMBLY} \
		--input "file:///${PWD}/conf/input.json" \
		--output "file://${PWD}/conf/output.json" \
		--backend-profiles "file://${PWD}/conf/backend-profiles.json"

local-webui-py3:
	cd static; python -m http.server 8000

local-webui-py2:
	cd static; python -m SimpleHTTPServer 8000

server-local: ${SERVER_ASSEMBLY}
	spark-submit --name "Keras Server ${NAME}" --master "local[4]" --driver-memory 4G --class com.azavea.server.Main \
	${SERVER_ASSEMBLY}
