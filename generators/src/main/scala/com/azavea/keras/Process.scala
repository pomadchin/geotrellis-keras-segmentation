package com.azavea.keras

import com.azavea.keras.raster._
import com.azavea.keras.config._

import geotrellis.raster._
import geotrellis.raster.io.geotiff.GeoTiff
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.hadoop._
import geotrellis.vector._
import geotrellis.vector.io._

import org.zeroturnaround.zip.ZipUtil
import org.apache.spark.SparkContext
import spray.json.DefaultJsonProtocol._
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.IOUtils
import org.apache.hadoop.io.compress.CompressionCodecFactory

import java.io.File
import java.net.URI
import scala.concurrent.forkjoin.ThreadLocalRandom

trait Process {
  implicit val sc: SparkContext
  val attributeStore: AttributeStore
  val reader: FilteringLayerReader[LayerId]

  // gzips only one file, mr reqiured to gzip a dir
  def gzip(uri: String) = {
    val conf = sc.hadoopConfiguration
    val fs = FileSystem.get(URI.create(uri), conf)
    val inputPath = new Path(uri)

    val factory = new CompressionCodecFactory(conf)
    // the correct codec will be discovered by the extension of the file
    val outputUri = s"$uri.gz"
    val outputPath = new Path(outputUri)
    val codec = factory.getCodec(outputPath)

    if (codec == null) {
      System.err.println("No codec found for " + uri)
      System.exit(1)
    }

    val is = fs.open(inputPath)
    val out = codec.createOutputStream(fs.create(outputPath))
    IOUtils.copyBytes(is, out, conf)
  }

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
      opts.bands,
      opts.withS3upload
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
    bands: Option[String],
    withS3upload: Boolean
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

      val l =
        if (bandsSeq.nonEmpty) layer.withContext(_.mapValues(_.subsetBands(bandsSeq)))
        else layer

      if (!l.isEmpty) {
        var tile = l.stitch.crop(p.envelope)
        if (randomization) {
          if (Math.random() > 0.5) tile = tile.flipVertical
          if (Math.random() > 0.5) tile = tile.flipHorizontal
          tile = tile.rotate90(ThreadLocalRandom.current().nextInt(0, 5))
        }
        val ndvi = Raster(NDVI(tile), tile.extent)
        val mask = Raster(MASK(tile), tile.extent)

        val toPath = s"$path/$discriminator/${tiffSize}x${tiffSize}"
        val to = s"$toPath/$i.tiff"
        val tondvi = s"$toPath/ndvi/$i.tiff"
        val tomask = s"$toPath/mask/$i.tiff"

        GeoTiff(tile, md.crs).writeHdfs(to)
        GeoTiff(ndvi, md.crs).writeHdfs(tondvi)
        GeoTiff(mask, md.crs).writeHdfs(tomask)

        if (zscore) {
          val toz = s"$toPath/$i-z.tiff"
          GeoTiff(tile.zscore, md.crs).writeHdfs(toz)
        }
      }
    }

    if(withS3upload) {
      HdfsUtils.copyPath(path, s"file:///tmp/gt-keras", sc.hadoopConfiguration)
      ZipUtil.pack(new File(s"/tmp/gt-keras"), new File(s"/tmp/gt-keras/gt-keras.zip"))
      HdfsUtils.deletePath(s"file:///tmp/gt-keras", sc.hadoopConfiguration)
      HdfsUtils.copyPath(s"file:///tmp/gt-keras.zip", "s3://geotrellis-test/keras/gt-keras.zip", sc.hadoopConfiguration)
    }
  }
}
