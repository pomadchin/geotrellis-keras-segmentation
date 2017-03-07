package com.azavea.server

import geotrellis.proj4.LatLng
import geotrellis.raster._
import geotrellis.raster.render._
import geotrellis.raster.io.geotiff.GeoTiff
import geotrellis.raster.io.geotiff.writer.GeoTiffWriter
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.stitch._
import geotrellis.vector.io.json.Implicits._
import geotrellis.vector.Polygon

import org.apache.spark.{SparkConf, SparkContext}
import ch.megard.akka.http.cors.CorsDirectives._
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpResponse, MediaTypes}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

trait Router extends Directives with AkkaSystem.LoggerExecutor {
  val conf: SparkConf
  implicit val sc: SparkContext

  val reader: FilteringLayerReader[LayerId]
  val tileReader: ValueReader[LayerId]
  val attributeStore: AttributeStore
  val staticPath: String

  def getMetaData(id: LayerId): TileLayerMetadata[SpatialKey] =
    attributeStore.readMetadata[TileLayerMetadata[SpatialKey]](id)

  def index(i: Option[Int] = None) = i match {
    case Some(n) if n > 1 && n < 6 => s"/index${n}.html"
    case _ => "/index.html"
  }

  def routes = pathPrefix("ping") {
    cors() {
      get {
        complete {
          "pong"
        }
      }
    }
  } ~
    pathEndOrSingleSlash {
      parameter('n.as[Int] ?) { n =>
        cors() { getFromFile(staticPath + index(n)) }
      }
    } ~
    pathPrefix("") {
      cors() { getFromDirectory(staticPath) }
    } ~
    pathPrefix("md") {
      import spray.json._
      import DefaultJsonProtocol._

      pathPrefix(Segment / IntNumber) { (layerName, zoom) =>
        complete {
          Future {
            getMetaData(LayerId(layerName, zoom)).toJson
          }
        }
      }
    } ~
    pathPrefix("tms") {
      pathPrefix("png") {
        pathPrefix(Segment / IntNumber / IntNumber / IntNumber) { (layerName, zoom, x, y) =>
          parameters('poly ? "") { poly =>
            cors() {
              val layerId = LayerId(layerName, zoom)
              val key = SpatialKey(x, y)
              val md = attributeStore.readMetadata[TileLayerMetadata[SpatialKey]](layerId)
              val extent = md.mapTransform(key)
              val polygon =
                if (poly.isEmpty) None
                else Some(poly.parseGeoJson[Polygon].reproject(LatLng, md.crs))

              complete {
                Future {
                  val tileOpt =
                    try {
                      Some(tileReader.reader[SpatialKey, MultibandTile](layerId).read(key))
                    } catch {
                      case e: ValueNotFoundError =>
                        None
                    }
                  tileOpt.map { tile =>
                    val bytes = polygon.fold(tile) { p => tile.mask(extent, p.geom) }.renderPng().bytes
                    HttpResponse(entity = HttpEntity(ContentType(MediaTypes.`image/png`), bytes))
                  }
                }
              }
            }
          }
        }
      }
    } ~
    pathPrefix("layer") {
      pathPrefix(Segment / IntNumber) { (layerName, zoom) =>
        parameters('poly ?, 'bands ?, 'format ? "tiff") { (poly, bands, format) =>
          cors() {
            val layerId = LayerId(layerName, zoom)
            val md = attributeStore.readMetadata[TileLayerMetadata[SpatialKey]](layerId)
            val polygon = poly.map(_.parseGeoJson[Polygon].reproject(LatLng, md.crs))
            val bandsSeq = bands.toList.flatMap(_.split("\\.").toList.map(_.toInt))

            complete {
              Future {
                val q = reader.query[SpatialKey, MultibandTile, TileLayerMetadata[SpatialKey]](layerId)
                val layer =
                  polygon
                    .fold(q.result)(p => q.where(Intersects(p)).result.mask(p))

                val selectedLayer =
                  if (bandsSeq.nonEmpty) layer.withContext(_.mapValues(_.subsetBands(bandsSeq)))
                  else layer

                val tileOpt =
                  try {
                    Some(selectedLayer.stitch)
                  } catch {
                    case e: Throwable => {
                      e.printStackTrace()
                      None
                    }
                  }

                tileOpt.map { tile =>
                  val raster = polygon.fold(tile) { p => tile.crop(p.envelope) }
                  val (ct, bytes) = format match {
                    case "png" => MediaTypes.`image/png` -> raster.tile.renderPng().bytes
                    case _ => MediaTypes.`image/tiff` -> GeoTiffWriter.write(GeoTiff(raster, md.crs))
                  }

                  HttpResponse(entity = HttpEntity(ContentType(ct), bytes))
                }
              }
            }
          }
        }
      }
    }
}
