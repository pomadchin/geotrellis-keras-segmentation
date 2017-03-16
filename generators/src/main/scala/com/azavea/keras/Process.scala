package com.azavea.keras

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
    val mapTransform = md.mapTransform
    val layerExtent = discriminator match {
      case "training" | "validation" | "test" => attributeStore.read[Extent](layerId, s"${discriminator}Extent")
      case _ => md.extent
    }

    def squareSide(tiffSize: Int) = {
      val GridBounds(colMin, rowMin, _, _) = mapTransform(layerExtent)
      val mk = SpatialKey(colMin, rowMin)
      val extent = md.mapTransform(mk)
      math.min(extent.xmax - extent.xmin, extent.ymax - extent.ymin) / md.tileLayout.tileSize * tiffSize
    }

    val polygons =
      (1 to amount)
        .map { _ => layerExtent.randomSquare(squareSide(tiffSize)) }
        .distinct
        .map(_.toPolygon)

    val bandsSeq = bands.toList.flatMap(_.split("\\.").toList.map(_.toInt))

    val q = reader.query[SpatialKey, MultibandTile, TileLayerMetadata[SpatialKey]](layerId)
    polygons.zipWithIndex.foreach { case (p, i) =>
      val layer = q.where(Intersects(p)).result.mask(p)
      var tile =
        if (bandsSeq.nonEmpty) layer.withContext(_.mapValues(_.subsetBands(bandsSeq))).stitch
        else layer.stitch

      if(randomization) {
        if (Math.random() > 0.5)
          tile = tile.flipVertical

        if (Math.random() > 0.5)
          tile = tile.flipHorizontal

        tile = tile.rotate90(ThreadLocalRandom.current().nextInt(0, 5))
      }

      val to = s"$path/$i.tiff"
      GeoTiff(tile, md.crs).write(to)
      HdfsUtils.copyPath(new Path(s"file://$to"), new Path(s"s3://geotrellis-test/keras/${to.split("/").last}"), sc.hadoopConfiguration)
      HdfsUtils.deletePath(new Path(s"file://$to"), sc.hadoopConfiguration)

      if(zscore) {
        val to = s"$path/$i-z.tiff"
        GeoTiff(tile.zscore, md.crs).write(to)
        HdfsUtils.copyPath(new Path(s"file://$to"), new Path(s"s3://geotrellis-test/keras/${to.split("/").last}"), sc.hadoopConfiguration)
        HdfsUtils.deletePath(new Path(s"file://$to"), sc.hadoopConfiguration)
      }
    }
  }
}
