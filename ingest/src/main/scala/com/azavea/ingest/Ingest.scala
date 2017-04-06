package com.azavea.ingest

import geotrellis.raster.{ArrayTile, CellType, MultibandTile, Tile}
import geotrellis.spark.SpatialKey
import geotrellis.spark.etl.Etl
import geotrellis.spark._
import geotrellis.spark.etl.config.EtlConf
import geotrellis.spark.io._
import geotrellis.spark.io.hadoop._
import geotrellis.spark.util.SparkUtils
import geotrellis.vector._
import geotrellis.vector.io._

import spray.json.DefaultJsonProtocol._
import spire.syntax.cfor._
import org.apache.hadoop.fs.Path
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD

object Ingest extends {
  val pattern = """(\d+)_(\d+)""".r

  // RGBIR + DSM + LABEL + LABEL NO BOUNDARY
  val totalBandsCount = 4 + 1 + 3 + 3

  val totalInputs = 4

  val indicesTraining =
    List(
      (2, 10), (3, 10), (3, 11), (3, 12), (4, 11), (4, 12), (5, 10),
      (5, 12), (6, 10), (6, 11), (6, 12), (6, 8), (6, 9), (7, 11),
      (7, 12), (7, 7), (7, 9)
    )

  val indicesValidation =
    List(
      (2, 11), (2, 12), (4, 10), (5, 11),
      (6, 7), (7, 10), (7, 8)
    )

  val indicesTest =
    List(
      (2, 13), (2, 14), (3, 13), (3, 14), (4, 13), (4, 14), (4, 15),
      (5, 13), (5, 14), (5, 15), (6, 13), (6, 14), (6, 15), (7, 13)
    )

  def main(args: Array[String]): Unit = {
    implicit val sc = SparkUtils.createSparkContext("GeoTrellis ETL Keras MultibandIngest", new SparkConf(true))
    try {

      try {
        HdfsUtils.deletePath(new Path("file:///tmp/conf"), sc.hadoopConfiguration)
      } catch {
        case _: Throwable => println("Can't delete file:///tmp/conf")
      }

      try {
        HdfsUtils.copyPath(new Path("conf"), new Path("file:///tmp/conf"), sc.hadoopConfiguration)
      } catch {
        case _: Throwable => println("Can't copy conf to file:///tmp/conf")
      }

      val etlConf = EtlConf(args)

      etlConf foreach { conf =>
        /** Parse command line arguments */
        val etl = Etl(conf, Etl.defaultModules)

        val inputPosition = conf.input.backend.path.toString.split(",").collect { case path if path.nonEmpty => path }.zipWithIndex.toMap

        /** Path in RDD requires kryo serialization to be enabled, should be required to use */
        val input = conf.input.backend.path.toString.split(",").collect { case path if path.nonEmpty =>
          HadoopGeoTiffRDD.multiband[ProjectedExtent, (Path, ProjectedExtent)](
            path,
            (uri, key) => new Path(uri.toString) -> key,
            HadoopGeoTiffRDD.Options(
              crs = conf.input.getCrs,
              maxTileSize = conf.input.maxTileSize,
              numPartitions = conf.input.numPartitions
            )
          )
        } reduce (_ union _)

        val keyedSource: RDD[((Int, Int), (ProjectedExtent, MultibandTile))] =
          input.map { case ((p, k), v) =>
            // round extent up to 1 number after decimal point
            val Extent(xmin, ymin, xmax, ymax) = k.extent
            val List(rxmin, rymin, rxmax, rymax) = List(xmin, ymin, xmax, ymax).map(BigDecimal(_).setScale(1, BigDecimal.RoundingMode.HALF_UP).toDouble)

            (p, (k.copy(extent = Extent(xmin = rxmin, ymin = rymin, xmax = rxmax, ymax = rymax)), v))
          }.groupBy { case (p, _) =>  // label has a corrupted extent, group by name, fix the extent
            val Array(i, j) = (pattern findAllIn p.getName).mkString.split("_").map(_.toInt)
            i -> j
          }.map { case (discriminator, iter: Iterable[(Path, (ProjectedExtent, MultibandTile))]) =>
            val resampledIter =
              iter.map { case (p, v) =>
                ((inputPosition(p.getParent.getName), p), v) // strong bands ordering
              }.toVector.sortBy(_._1._1).map { case ((_, p), (k, mbtile)) =>
                (p, (k, mbtile.mapBands { case (_, tile) =>
                  if (tile.cols != 6000 || tile.rows != 6000) {
                    val newTile = ArrayTile.alloc(tile.cellType, 6000, 6000)

                    if (!tile.cellType.isFloatingPoint) {
                      cfor(0)(_ < tile.cols, _ + 1) { col =>
                        cfor(0)(_ < tile.rows, _ + 1) { row =>
                          newTile.set(col, row, tile.get(col, row))
                        }
                      }
                    } else {
                      cfor(0)(_ < tile.cols, _ + 1) { col =>
                        cfor(0)(_ < tile.rows, _ + 1) { row =>
                          newTile.setDouble(col, row, tile.getDouble(col, row))
                        }
                      }
                    }

                    newTile
                  } else tile
                }))
              }

            val key = resampledIter.filter { case (_, (k, _)) =>
              k.extent != Extent(0, 0, 6000, 6000)
            }.head._2._1

            val (cellType, bandsCount) =
              resampledIter
                .map { case (_, (_, tile)) => tile.cellType -> tile.bandCount }
                .reduce[(CellType, Int)] { case ((ct, c1), (_, c2)) => (ct, c1 + c2) }

            val fixedResampledIter =
              if(bandsCount < totalBandsCount) {
                val lp = resampledIter.head._1
                resampledIter ++ Iterator({
                  lp -> (key, MultibandTile((0 until (totalBandsCount - bandsCount)) map { _ => ArrayTile.alloc(cellType, 6000, 6000) }))
                })
              } else resampledIter

            discriminator -> (key, MultibandTile(
              fixedResampledIter.foldLeft(Vector[Tile]()) { case (acc, (_, (_, v))) =>
                acc ++ v.bands
              }
            ))
          }

        val trainingExtent =
          try {
            Some(
              keyedSource
                .filter { case (discriminator, _) => indicesTraining.contains(discriminator) }
                .map { case (_, (key, _)) => key.extent }
                .reduce(_ combine _)
            )
          } catch {
            case _: java.lang.UnsupportedOperationException => None
          }

        val validationExtent =
          try {
            Some(
              keyedSource
                .filter { case (discriminator, _) => indicesValidation.contains(discriminator) }
                .map { case (_, (key, _)) => key.extent }
                .reduce(_ combine _)
            )
          } catch {
            case _: java.lang.UnsupportedOperationException => None
          }

        val testExtent =
          try {
            Some(
              keyedSource
                .filter { case (discriminator, _) => indicesTest.contains(discriminator) }
                .map { case (_, (key, _)) => key.extent }
                .reduce(_ combine _)
            )
          } catch {
            case _: java.lang.UnsupportedOperationException => None
          }

        val saveAction: Etl.SaveAction[SpatialKey, MultibandTile, TileLayerMetadata[SpatialKey]] =
          (attributeStore, writer, id, rdd) => {
            writer.write(id, rdd)
            if(id.zoom == 0) {
              attributeStore.write(id, "trainingExtent", trainingExtent)
              attributeStore.write(id, "validationExtent", validationExtent)
              attributeStore.write(id, "testExtent", testExtent)
            }
          }

        val source = keyedSource.map(_._2)

        val (zoom, tiled) = etl.tile(source)
        etl.save[SpatialKey, MultibandTile](LayerId(etl.input.name, zoom), tiled, saveAction)
      }

    } finally sc.stop()
  }
}