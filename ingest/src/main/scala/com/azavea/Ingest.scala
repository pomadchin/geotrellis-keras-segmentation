package com.azavea

import geotrellis.raster.Tile
import geotrellis.spark.SpatialKey
import geotrellis.spark.etl.Etl
import geotrellis.spark._
import geotrellis.spark.util.SparkUtils
import geotrellis.vector.ProjectedExtent

import org.apache.spark.SparkConf

object Ingest extends {
  def main(args: Array[String]): Unit = {
    implicit val sc = SparkUtils.createSparkContext("GeoTrellis ETL SinglebandIngest", new SparkConf(true))
    try {
      Etl.ingest[ProjectedExtent, SpatialKey, Tile](args)
    } finally sc.stop()    
  }
}