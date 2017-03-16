package com.azavea.keras

import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.file._

import org.apache.spark.SparkContext

case class FileProcess(path: String)(implicit val sc: SparkContext) extends Process {
  val attributeStore: AttributeStore = FileAttributeStore(path)
  val reader: FilteringLayerReader[LayerId] = FileLayerReader(path)
}
