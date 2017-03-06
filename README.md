# GeoTrellis Keras

This is an application is a GeoTrellis based backend for [Keras Semantic Segmentation](https://github.com/azavea/keras-semantic-segmentation) project.

### Getting Started

## Running on EMR

_Requires_: Reasonably up to date [`aws-cli`](https://aws.amazon.com/cli/).

EMR boostrup script would build PDAL with JNI bindings on each node.

### Configuration

 - [config-aws.mk](./config-aws.mk) AWS credentials, S3 staging bucket, subnet, etc
 - [config-emr.mk](./config-emr.mk) EMR cluster type and size
 - [config-run.mk](./config-run.mk) Ingest step parameters

You will need to modify `config-aws.mk` to reflect your credentials and your VPC configuration. `config-emr.mk` and `config-ingest.mk` have been configured with an area over Japan. Be especially aware that as you change instance types `config-emr.mk` parameters like `EXECUTOR_MEMORY` and `EXECUTOR_CORES` need to be reviewed and likely adjusted.

### EMR pipeline example

```bash
make upload-code && make create-cluster
make ingest
make run-server # after completing ingest
```

## Licence

* Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
