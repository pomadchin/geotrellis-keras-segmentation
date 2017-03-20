include config-aws.mk # Vars related to AWS credentials and services used
include config-emr.mk # Vars related to type and size of EMR cluster
include config-run.mk # Vars related to ingest step and spark parameters

INGEST_ASSEMBLY := ingest/target/scala-2.11/keras-ingest-assembly-0.1.0-SNAPSHOT.jar
SERVER_ASSEMBLY := server/target/scala-2.11/keras-server-assembly-0.1.0-SNAPSHOT.jar
GENERATORS_ASSEMBLY := generators/target/scala-2.11/keras-generators-assembly-0.1.0-SNAPSHOT.jar
SCRIPT_RUNNER := s3://elasticmapreduce/libs/script-runner/script-runner.jar
STATIC := ./static
ETL_CONF := ./conf
DSM_DATA := /data/keras/datasets/potsdam/1_DSM_normalisation_geotiff.zip
RGBIR_DATA := /data/keras/datasets/potsdam/4_Ortho_RGBIR_geotiff.zip
LABEL_DATA := /data/keras/datasets/potsdam/5_Labels_for_participants_geotiff.zip
LABEL_NB_DATA := /data/keras/datasets/potsdam/5_Labels_for_participants_no_Boundary_geotiff.zip

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

${GENERATORS_ASSEMBLY}: $(call rwildcard, generators/src, *.scala, *.conf, *.sbt) build.sbt
	./sbt generators/assembly -no-colors
	@touch -m ${GENERATORS_ASSEMBLY}

viewer/site.tgz: $(call rwildcard, viewer/components, *.js)
	@cd viewer && npm install && npm run build
	tar -czf viewer/site.tgz -C viewer/dist .

upload-code: ${SERVER_ASSEMBLY} ${INGEST_ASSEMBLY} ${GENERATORS_ASSEMBLY} scripts/emr/*
	@aws s3 cp conf/backend-profiles.json ${S3_URI}/
	@aws s3 cp conf/input.json ${S3_URI}/
	@aws s3 cp conf/output.json ${S3_URI}/output.json
	@aws s3 cp ${SERVER_ASSEMBLY} ${S3_URI}/
	@aws s3 cp ${INGEST_ASSEMBLY} ${S3_URI}/
	@aws s3 cp ${GENERATORS_ASSEMBLY} ${S3_URI}/

upload-config:
	aws emr put --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem" \
	--src ${ETL_CONF} --dest /tmp
	aws emr put --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem" \
    --src ./scripts/load-hdfs.sh --dest /tmp
	aws emr ssh --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem" \
	--command "hadoop fs -rm -r -f conf"
	aws emr ssh --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem" \
	--command "hadoop fs -copyFromLocal -f /tmp/conf conf"

upload-input:
	aws emr put --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem" \
	--src ${DSM_DATA} --dest /mnt1/
	aws emr put --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem" \
	--src ${RGBIR_DATA} --dest /mnt1/
	aws emr put --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem" \
	--src ${LABEL_DATA} --dest /mnt1/
	aws emr put --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem" \
	--src ${LABEL_NB_DATA} --dest /mnt1/

load-hdfs:
	aws emr ssh --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem" \
	--command /tmp/load-hdfs.sh

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
${S3_URI}/keras-ingest-assembly-0.1.0-SNAPSHOT.jar,\
--input,"file:///tmp/conf/input.json",\
--output,"file:///tmp/conf/output.json",\
--backend-profiles,"file:///tmp/conf/backend-profiles.json"\
] | cut -f2 | tee last-step-id.txt

generate-6000: ${GENERATORS_ASSEMBLY}
	aws emr add-steps --output text --cluster-id ${CLUSTER_ID} \
--steps Type=CUSTOM_JAR,Name="Ingest Keras",Jar=command-runner.jar,Args=[\
spark-submit,--master,yarn-cluster,\
--class,com.azavea.keras.Main,\
--driver-memory,${DRIVER_MEMORY},\
--driver-cores,${DRIVER_CORES},\
--executor-memory,${EXECUTOR_MEMORY},\
--executor-cores,${EXECUTOR_CORES},\
--conf,spark.dynamicAllocation.enabled=true,\
--conf,spark.yarn.executor.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.yarn.driver.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.kryoserializer.buffer.max=2047mb,\
${S3_URI}/keras-generators-assembly-0.1.0-SNAPSHOT.jar,\
--layerName,"keras-raw",\
--catalogPath,"/user/hadoop/keras-ingest/",\
--zoom,0,\
--tiffSize,6000,\
--amount,69,\
--randomization,false,\
--zscore,false,\
--path,"/tmp"\
] | cut -f2 | tee last-step-id.txt

generate-test-6000: ${GENERATORS_ASSEMBLY}
	aws emr add-steps --output text --cluster-id ${CLUSTER_ID} \
--steps Type=CUSTOM_JAR,Name="Ingest Keras",Jar=command-runner.jar,Args=[\
spark-submit,--master,yarn-cluster,\
--class,com.azavea.keras.Main,\
--driver-memory,${DRIVER_MEMORY},\
--driver-cores,${DRIVER_CORES},\
--executor-memory,${EXECUTOR_MEMORY},\
--executor-cores,${EXECUTOR_CORES},\
--conf,spark.dynamicAllocation.enabled=true,\
--conf,spark.yarn.executor.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.yarn.driver.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.kryoserializer.buffer.max=2047mb,\
${S3_URI}/keras-generators-assembly-0.1.0-SNAPSHOT.jar,\
--layerName,"keras-raw",\
--catalogPath,"/user/hadoop/keras-ingest/",\
--discriminator,"test",\
--zoom,0,\
--tiffSize,6000,\
--amount,69,\
--randomization,false,\
--zscore,false,\
--path,"/tmp"\
] | cut -f2 | tee last-step-id.txt

generate-800: ${GENERATORS_ASSEMBLY}
	aws emr add-steps --output text --cluster-id ${CLUSTER_ID} \
--steps Type=CUSTOM_JAR,Name="Ingest Keras",Jar=command-runner.jar,Args=[\
spark-submit,--master,yarn-cluster,\
--class,com.azavea.keras.Main,\
--driver-memory,${DRIVER_MEMORY},\
--driver-cores,${DRIVER_CORES},\
--executor-memory,${EXECUTOR_MEMORY},\
--executor-cores,${EXECUTOR_CORES},\
--conf,spark.dynamicAllocation.enabled=true,\
--conf,spark.yarn.executor.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.yarn.driver.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.kryoserializer.buffer.max=2047mb,\
${S3_URI}/keras-generators-assembly-0.1.0-SNAPSHOT.jar,\
--layerName,"keras-raw",\
--catalogPath,"/user/hadoop/keras-ingest/",\
--zoom,0,\
--tiffSize,800,\
--amount,12500,\
--randomization,true,\
--zscore,true,\
--path,"/tmp"\
] | cut -f2 | tee last-step-id.txt

generate-test-800: ${GENERATORS_ASSEMBLY}
	aws emr add-steps --output text --cluster-id ${CLUSTER_ID} \
--steps Type=CUSTOM_JAR,Name="Ingest Keras",Jar=command-runner.jar,Args=[\
spark-submit,--master,yarn-cluster,\
--class,com.azavea.keras.Main,\
--driver-memory,${DRIVER_MEMORY},\
--driver-cores,${DRIVER_CORES},\
--executor-memory,${EXECUTOR_MEMORY},\
--executor-cores,${EXECUTOR_CORES},\
--conf,spark.dynamicAllocation.enabled=true,\
--conf,spark.yarn.executor.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.yarn.driver.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.kryoserializer.buffer.max=2047mb,\
${S3_URI}/keras-generators-assembly-0.1.0-SNAPSHOT.jar,\
--layerName,"keras-raw",\
--catalogPath,"/user/hadoop/keras-ingest/",\
--discriminator,"test",\
--zoom,0,\
--tiffSize,800,\
--amount,12500,\
--randomization,true,\
--zscore,true,\
--path,"/tmp"\
] | cut -f2 | tee last-step-id.txt

generate-validation-800: ${GENERATORS_ASSEMBLY}
	aws emr add-steps --output text --cluster-id ${CLUSTER_ID} \
--steps Type=CUSTOM_JAR,Name="Ingest Keras",Jar=command-runner.jar,Args=[\
spark-submit,--master,yarn-cluster,\
--class,com.azavea.keras.Main,\
--driver-memory,${DRIVER_MEMORY},\
--driver-cores,${DRIVER_CORES},\
--executor-memory,${EXECUTOR_MEMORY},\
--executor-cores,${EXECUTOR_CORES},\
--conf,spark.dynamicAllocation.enabled=true,\
--conf,spark.yarn.executor.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.yarn.driver.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.kryoserializer.buffer.max=2047mb,\
${S3_URI}/keras-generators-assembly-0.1.0-SNAPSHOT.jar,\
--layerName,"keras-raw",\
--catalogPath,"/user/hadoop/keras-ingest/",\
--discriminator,"validation",\
--zoom,0,\
--tiffSize,800,\
--amount,12500,\
--randomization,true,\
--zscore,true,\
--path,"/tmp"\
] | cut -f2 | tee last-step-id.txt

generate-training-800: ${GENERATORS_ASSEMBLY}
	aws emr add-steps --output text --cluster-id ${CLUSTER_ID} \
--steps Type=CUSTOM_JAR,Name="Ingest Keras",Jar=command-runner.jar,Args=[\
spark-submit,--master,yarn-cluster,\
--class,com.azavea.keras.Main,\
--driver-memory,${DRIVER_MEMORY},\
--driver-cores,${DRIVER_CORES},\
--executor-memory,${EXECUTOR_MEMORY},\
--executor-cores,${EXECUTOR_CORES},\
--conf,spark.dynamicAllocation.enabled=true,\
--conf,spark.yarn.executor.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.yarn.driver.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.kryoserializer.buffer.max=2047mb,\
${S3_URI}/keras-generators-assembly-0.1.0-SNAPSHOT.jar,\
--layerName,"keras-raw",\
--catalogPath,"/user/hadoop/keras-ingest/",\
--discriminator,"training",\
--zoom,0,\
--tiffSize,800,\
--amount,12500,\
--randomization,true,\
--zscore,true,\
--path,"/tmp"\
] | cut -f2 | tee last-step-id.txt

generate-256: ${GENERATORS_ASSEMBLY}
	aws emr add-steps --output text --cluster-id ${CLUSTER_ID} \
--steps Type=CUSTOM_JAR,Name="Ingest Keras",Jar=command-runner.jar,Args=[\
spark-submit,--master,yarn-cluster,\
--class,com.azavea.keras.Main,\
--driver-memory,${DRIVER_MEMORY},\
--driver-cores,${DRIVER_CORES},\
--executor-memory,${EXECUTOR_MEMORY},\
--executor-cores,${EXECUTOR_CORES},\
--conf,spark.dynamicAllocation.enabled=true,\
--conf,spark.yarn.executor.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.yarn.driver.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.kryoserializer.buffer.max=2047mb,\
${S3_URI}/keras-generators-assembly-0.1.0-SNAPSHOT.jar,\
--layerName,"keras-raw",\
--catalogPath,"/user/hadoop/keras-ingest/",\
--zoom,0,\
--tiffSize,256,\
--amount,50000,\
--randomization,true,\
--zscore,true,\
--path,"/tmp"\
] | cut -f2 | tee last-step-id.txt

generate-test-256: ${GENERATORS_ASSEMBLY}
	aws emr add-steps --output text --cluster-id ${CLUSTER_ID} \
--steps Type=CUSTOM_JAR,Name="Ingest Keras",Jar=command-runner.jar,Args=[\
spark-submit,--master,yarn-cluster,\
--class,com.azavea.keras.Main,\
--driver-memory,${DRIVER_MEMORY},\
--driver-cores,${DRIVER_CORES},\
--executor-memory,${EXECUTOR_MEMORY},\
--executor-cores,${EXECUTOR_CORES},\
--conf,spark.dynamicAllocation.enabled=true,\
--conf,spark.yarn.executor.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.yarn.driver.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.kryoserializer.buffer.max=2047mb,\
${S3_URI}/keras-generators-assembly-0.1.0-SNAPSHOT.jar,\
--layerName,"keras-raw",\
--catalogPath,"/user/hadoop/keras-ingest/",\
--discriminator,"test",\
--zoom,0,\
--tiffSize,256,\
--amount,50000,\
--randomization,true,\
--zscore,true,\
--path,"/tmp"\
] | cut -f2 | tee last-step-id.txt

generate-validation-256: ${GENERATORS_ASSEMBLY}
	aws emr add-steps --output text --cluster-id ${CLUSTER_ID} \
--steps Type=CUSTOM_JAR,Name="Ingest Keras",Jar=command-runner.jar,Args=[\
spark-submit,--master,yarn-cluster,\
--class,com.azavea.keras.Main,\
--driver-memory,${DRIVER_MEMORY},\
--driver-cores,${DRIVER_CORES},\
--executor-memory,${EXECUTOR_MEMORY},\
--executor-cores,${EXECUTOR_CORES},\
--conf,spark.dynamicAllocation.enabled=true,\
--conf,spark.yarn.executor.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.yarn.driver.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.kryoserializer.buffer.max=2047mb,\
${S3_URI}/keras-generators-assembly-0.1.0-SNAPSHOT.jar,\
--layerName,"keras-raw",\
--catalogPath,"/user/hadoop/keras-ingest/",\
--discriminator,"validation",\
--zoom,0,\
--tiffSize,256,\
--amount,50000,\
--randomization,true,\
--zscore,true,\
--path,"/tmp"\
] | cut -f2 | tee last-step-id.txt

generate-training-256: ${GENERATORS_ASSEMBLY}
	aws emr add-steps --output text --cluster-id ${CLUSTER_ID} \
--steps Type=CUSTOM_JAR,Name="Ingest Keras",Jar=command-runner.jar,Args=[\
spark-submit,--master,yarn-cluster,\
--class,com.azavea.keras.Main,\
--driver-memory,${DRIVER_MEMORY},\
--driver-cores,${DRIVER_CORES},\
--executor-memory,${EXECUTOR_MEMORY},\
--executor-cores,${EXECUTOR_CORES},\
--conf,spark.dynamicAllocation.enabled=true,\
--conf,spark.yarn.executor.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.yarn.driver.memoryOverhead=${YARN_OVERHEAD},\
--conf,spark.kryoserializer.buffer.max=2047mb,\
${S3_URI}/keras-generators-assembly-0.1.0-SNAPSHOT.jar,\
--layerName,"keras-raw",\
--catalogPath,"/user/hadoop/keras-ingest/",\
--discriminator,"training",\
--zoom,0,\
--tiffSize,256,\
--amount,50000,\
--randomization,true,\
--zscore,true,\
--path,"/tmp",\
--withS3upload,true\
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
		--input "file:///${PWD}/conf/input-local.json" \
		--output "file://${PWD}/conf/output-local.json" \
		--backend-profiles "file://${PWD}/conf/backend-profiles.json"

generate-local: ${GENERATORS_ASSEMBLY}
	spark-submit \
	    --conf spark.kryoserializer.buffer.max=2047mb \
	    --class com.azavea.keras.Main --driver-memory=7G ${GENERATORS_ASSEMBLY} \
		--layerName "keras-raw" \
        --catalogPath "/data/keras-ingest/" \
        --zoom 0 \
        --tiffSize 256 \
        --amount 3 \
        --randomization true \
        --zscore true \
        --path "/tmp" \
        --backend file

local-webui-py3:
	cd static; python -m http.server 8000

local-webui-py2:
	cd static; python -m SimpleHTTPServer 8000

server-local: ${SERVER_ASSEMBLY}
	spark-submit --name "Keras Server ${NAME}" --master "local[4]" --driver-memory 4G --class com.azavea.server.Main \
	${SERVER_ASSEMBLY}

proxy:
	aws emr socks --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem"

ssh:
	aws emr ssh --cluster-id ${CLUSTER_ID} --key-pair-file "${HOME}/${EC2_KEY}.pem"
