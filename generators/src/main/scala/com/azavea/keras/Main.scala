package com.azavea.keras

import com.azavea.keras.config.ProcessConf

import geotrellis.spark.io.s3.S3InputFormat
import geotrellis.spark.util.SparkUtils

import org.apache.spark.SparkConf

object Main {
  def main(args: Array[String]): Unit = {
    implicit val sc = SparkUtils.createSparkContext("GeoTrellis ETL Keras MultibandIngest", new SparkConf(true))
    val opts = ProcessConf(args)
    try {
      val process =
        if (opts.isFile) FileProcess(opts.catalogPath)
        else if (opts.isS3) {
          val S3InputFormat.S3UrlRx(_, _, bucket, key) = opts.catalogPath
          S3Process(bucket, key)
        }
        else HadoopProcess(opts.catalogPath)

      process.generate(opts)
    } finally sc.stop()
  }
}
