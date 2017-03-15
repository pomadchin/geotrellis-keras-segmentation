package com.azavea.keras

import geotrellis.proj4.LatLng
import geotrellis.raster._
import geotrellis.raster.render._
import geotrellis.raster.io.geotiff.GeoTiff
import geotrellis.raster.io.geotiff.writer.GeoTiffWriter
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.stitch._
import geotrellis.vector.io.json.Implicits._
import geotrellis.vector.{Extent, Polygon}
import org.apache.spark.{SparkConf, SparkContext}

import scala.concurrent.Future
import scala.util.Random
import spire.syntax.cfor._

object Process {
  val layerName: String = ???
  val zoom: Int = ???
  val batchSize: Int = ???
  val batchIndex: Int = ???
  val poly: Option[String] = None
  val bands: Option[String] = None
  val attributeStore: AttributeStore = ???
  val reader: FilteringLayerReader[LayerId] = ???

  val layerId = LayerId(layerName, zoom)
  val md = attributeStore.readMetadata[TileLayerMetadata[SpatialKey]](layerId)
  val layerExtent = md.extent

  val mk = md.bounds match {
    case kb: KeyBounds[SpatialKey] =>
      kb.minKey
  }

  val e = md.mapTransform(mk)
  val squareSide = (e.xmax - e.xmin) / (md.tileLayout.tileSize / 256)

  val polygons = (1 to 50000).map { _ =>
    val newXMin = layerExtent.xmin + (Math.random() * ((layerExtent.xmax - squareSide - layerExtent.xmin) + 1))
    val newYMin = layerExtent.ymin + (Math.random() * ((layerExtent.ymax - squareSide - layerExtent.ymin) + 1))
    val newXMax = newXMin + squareSide
    val newYMax = newYMin + squareSide

    Extent(xmin = newXMin, xmax = newXMax, ymin = newYMin, ymax = newYMax)
  }.distinct.map(_.toPolygon)

  //val polygon = poly.map(_.parseGeoJson[Polygon].reproject(LatLng, md.crs))
  val bandsSeq = bands.toList.flatMap(_.split("\\.").toList.map(_.toInt))

  // save as geotiffs into some directory
  val q = reader.query[SpatialKey, MultibandTile, TileLayerMetadata[SpatialKey]](layerId)
  val tiles = polygons.map { p =>
    val layer = q.where(Intersects(p)).result.mask(p)
    if (bandsSeq.nonEmpty) layer.withContext(_.mapValues(_.subsetBands(bandsSeq))).stitch
    else layer.stitch
  }

}
