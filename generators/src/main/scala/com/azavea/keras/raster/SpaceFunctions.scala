package com.azavea.keras.raster

import geotrellis.raster.CellGrid

trait SpaceFunctions[T <: CellGrid] {
  val that: T
  def rotate90(n: Int): T
  def flipVertical: T
  def flipHorizontal: T
  def zscore: T
}
