package com.azavea.ingest

import geotrellis.raster.{MultibandTile, Tile}
import geotrellis.spark.SpatialKey
import geotrellis.spark.etl.Etl
import geotrellis.spark._
import geotrellis.spark.etl.config.EtlConf
import geotrellis.spark.io._
import geotrellis.spark.io.hadoop._
import geotrellis.spark.util.SparkUtils
import geotrellis.vector.Extent

import org.apache.spark.SparkConf

object Ingest extends {
  val pattern = """(\d+)_(\d+)""".r

  def main(args: Array[String]): Unit = {
    implicit val sc = SparkUtils.createSparkContext("GeoTrellis ETL Keras MultibandIngest", new SparkConf(true))
    try {
      val etlConf = EtlConf(args)

      etlConf foreach { conf =>
        /* parse command line arguments */
        val etl = Etl(conf, Etl.defaultModules)
        val Array(p1, p2, p3) = conf.input.backend.path.toString.split(",")

        val input = NewHadoopGeoTiffRDD.spatialMultiband(
          p1,
          NewHadoopGeoTiffRDD.Options(
            crs = conf.input.getCrs,
            maxTileSize = conf.input.maxTileSize,
            numPartitions = conf.input.numPartitions
          )
        ).union(NewHadoopGeoTiffRDD.spatialMultiband(
          p2,
          NewHadoopGeoTiffRDD.Options(
            crs = conf.input.getCrs,
            maxTileSize = conf.input.maxTileSize,
            numPartitions = conf.input.numPartitions
          )
        )).union(NewHadoopGeoTiffRDD.spatialMultiband(
          p3,
          NewHadoopGeoTiffRDD.Options(
            crs = conf.input.getCrs,
            maxTileSize = conf.input.maxTileSize,
            numPartitions = conf.input.numPartitions
          )
        ))

        val source =
          input.map { case (p, (k, v)) =>
            // round extent up to 1 number after decimal point
            val Extent(xmin, ymin, xmax, ymax) = k.extent
            val List(rxmin, rymin, rxmax, rymax) = List(xmin, ymin, xmax, ymax).map(BigDecimal(_).setScale(1, BigDecimal.RoundingMode.HALF_UP).toDouble)

            (p, (k.copy(extent = Extent(xmin = rxmin, ymin = rymin, xmax = rxmax, ymax = rymax)), v))
          }.groupBy { case (p, _) =>  // label has a corrupted extent, group by name, fix the extent
            val Array(i, j) = (pattern findAllIn p.getName).mkString.split("_").map(_.toInt)
            s"${i}_${j}"
          }.map { case (_, iter) =>
            iter.head._2._1 -> MultibandTile(
              iter.foldLeft(Vector[Tile]()) { case (acc, (_, (_, v))) =>
                acc ++ v.bands
              }
            )
          }

        /* perform the reprojection and mosaicing step to fit tiles to LayoutScheme specified */
        val (zoom, tiled) = etl.tile(source)
        /* save and optionally pyramid the mosaiced layer */
        etl.save[SpatialKey, MultibandTile](LayerId(etl.input.name, zoom), tiled)
      }

    } finally sc.stop()
  }
}