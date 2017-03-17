package com.azavea.keras

import java.io.File

import com.azavea.keras.raster._
import com.azavea.keras.config._
import geotrellis.raster._
import geotrellis.raster.io.geotiff.GeoTiff
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.hadoop.HdfsUtils
import geotrellis.vector._
import geotrellis.vector.io._
import org.apache.hadoop.fs.Path
import org.apache.spark.SparkContext
import spray.json.DefaultJsonProtocol._

import scala.concurrent.forkjoin.ThreadLocalRandom

trait Process {
  implicit val sc: SparkContext
  val attributeStore: AttributeStore
  val reader: FilteringLayerReader[LayerId]

  def generate(opts: ProcessConf.Options): Unit =
    generate(
      opts.layerName,
      opts.discriminator,
      opts.zoom,
      opts.tiffSize,
      opts.amount,
      opts.randomization,
      opts.zscore,
      opts.path,
      opts.bands
    )

  def generate(
    layerName: String,
    discriminator: String,
    zoom: Int,
    tiffSize: Int,
    amount: Int,
    randomization: Boolean,
    zscore: Boolean,
    path: String,
    bands: Option[String]
  ): Unit = {
    val layerId = LayerId(layerName, zoom)
    val md = attributeStore.readMetadata[TileLayerMetadata[SpatialKey]](layerId)
    val layerExtent = discriminator match {
      case "training" | "validation" | "test" =>
        attributeStore
          .read[Option[Extent]](layerId, s"${discriminator}Extent")
          .getOrElse(throw new Exception(s"there is no ${discriminator}Extent attribute for $layerId"))
      case _ => md.extent
    }

    val polygons =
      (1 to amount)
        .map { _ => layerExtent.randomSquare(md.cellSize.height * (tiffSize - 1), md.cellSize.width * (tiffSize - 1)) }
        .distinct
        .map(_.toPolygon)

    val bandsSeq = bands.toList.flatMap(_.split("\\.").toList.map(_.toInt))

    val q = reader.query[SpatialKey, MultibandTile, TileLayerMetadata[SpatialKey]](layerId)
    polygons.zipWithIndex.foreach { case (p, i) =>
      val layer = q.where(Intersects(p)).result
      val tileOpt = {
        val l =
          if (bandsSeq.nonEmpty) layer.withContext(_.mapValues(_.subsetBands(bandsSeq)))
          else layer

        if(l.count() > 0) Some(l.stitch)
        else None
      }

      tileOpt foreach { t =>
        var tile = t
        if (randomization) {
          if (Math.random() > 0.5) tile = tile.flipVertical
          if (Math.random() > 0.5) tile = tile.flipHorizontal
          tile = tile.rotate90(ThreadLocalRandom.current().nextInt(0, 5))
        }

        val res = tile.crop(p.envelope)
        val ndvi = NDVI(res)

        // how to gzip and to deliver on s3 (?)
        val toPath = s"$path/$discriminator/${tiffSize}x${tiffSize}"
        val to = s"$toPath/$i.tiff"
        val tondvi = s"$toPath/ndvi/$i.tiff"

        new File(toPath).mkdirs()
        new File(s"$toPath/ndvi").mkdirs()

        GeoTiff(res, md.crs).write(to)
        GeoTiff(Raster(ndvi, res.extent), md.crs).write(tondvi)
        HdfsUtils.copyPath(new Path(s"file://$to"), new Path(s"hdfs:///keras/${to.split("/").last}"), sc.hadoopConfiguration)
        HdfsUtils.deletePath(new Path(s"file://$to"), sc.hadoopConfiguration)
        HdfsUtils.copyPath(new Path(s"file://$tondvi"), new Path(s"hdfs:///keras/${tondvi.split("/").last}"), sc.hadoopConfiguration)
        HdfsUtils.deletePath(new Path(s"file://$tondvi"), sc.hadoopConfiguration)

        if (zscore) {
          val to = s"$path/$i-z.tiff"
          GeoTiff(res.zscore, md.crs).write(to)
          HdfsUtils.copyPath(new Path(s"file://$to"), new Path(s"hdfs:///geotrellis-test/keras/${to.split("/").last}"), sc.hadoopConfiguration)
          HdfsUtils.deletePath(new Path(s"file://$to"), sc.hadoopConfiguration)
        }
      }
    }
  }
}
