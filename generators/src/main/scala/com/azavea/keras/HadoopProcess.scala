package com.azavea.keras

import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.hadoop._

import org.apache.spark.SparkContext

case class HadoopProcess(path: String)(implicit val sc: SparkContext) extends Process {
  val attributeStore: AttributeStore = HadoopAttributeStore(path)
  val reader: FilteringLayerReader[LayerId] = HadoopLayerReader(path)
}
