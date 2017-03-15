package com.azavea.keras

import geotrellis.raster._
import com.azavea.keras.Implicits._
import geotrellis.raster.io.geotiff.GeoTiff
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.hadoop.HdfsUtils

import org.apache.hadoop.fs.Path
import org.apache.spark.SparkContext

import scala.concurrent.forkjoin.ThreadLocalRandom

trait Process {
  val attributeStore: AttributeStore
  val reader: FilteringLayerReader[LayerId]

  def generate(
    layerName: String,
    zoom: Int,
    amount: Int = 5000,
    randomization: Boolean = true,
    zscore: Boolean = true,
    path: String = "/tmp",
    bands: Option[String] = None
  )(implicit sc: SparkContext): Unit = {
    val layerId = LayerId(layerName, zoom)
    val md = attributeStore.readMetadata[TileLayerMetadata[SpatialKey]](layerId)
    val layerExtent = md.extent

    val squareSide = {
      val mk = md.bounds match {
        case kb: KeyBounds[SpatialKey] => kb.minKey
        case _ => sys.error("No correct KeyBounds for the current metadata.")
      }

      val extent = md.mapTransform(mk)
      math.min(extent.xmax - extent.xmin, extent.ymax - extent.ymin) / md.tileLayout.tileSize * 256
    }

    val polygons =
      (1 to amount)
        .map { _ => layerExtent.randomSquare(squareSide) }
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
