package com.azavea.server

import geotrellis.proj4.{LatLng, WebMercator}
import geotrellis.raster._
import geotrellis.raster.render._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.vector.io.json.Implicits._
import geotrellis.vector.Polygon
import geotrellis.vector.reproject._

import org.apache.spark.{SparkConf, SparkContext}
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpResponse, MediaTypes}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json._

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
    get {
      complete { "pong" }
    }
  } ~
    pathEndOrSingleSlash {
      parameter('n.as[Int] ?) { n =>
        getFromFile(staticPath + index(n))
      }
    } ~
    pathPrefix("") {
      getFromDirectory(staticPath)
    } ~
    pathPrefix("tms") {
      pathPrefix("png") {
        pathPrefix(Segment / IntNumber / IntNumber / IntNumber) { (layerName, zoom, x, y) =>
          parameters('poly ? "") { poly =>
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
}
