package com.azavea.keras

import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.s3._

import org.apache.spark.SparkContext

case class S3Process(bucket: String, key: String)(implicit val sc: SparkContext) extends Process {
  val attributeStore: AttributeStore = S3AttributeStore(bucket, key)
  val reader: FilteringLayerReader[LayerId] = S3LayerReader(bucket, key)
}
