package com.azavea.keras

import com.azavea.keras.config.ProcessConf

import geotrellis.spark.util.SparkUtils
import org.apache.spark.SparkConf

object Main {
  def main(args: Array[String]): Unit = {
    implicit val sc = SparkUtils.createSparkContext("GeoTrellis ETL Keras MultibandIngest", new SparkConf(true))
    val opts = ProcessConf(args)
    try { HadoopProcess(opts.catalogPath).generate(opts) } finally sc.stop()
  }
}
