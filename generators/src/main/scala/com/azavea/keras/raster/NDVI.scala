package com.azavea.keras.raster

import geotrellis.raster._

object NDVI extends (MultibandTile => Tile) {
  def apply(tile: MultibandTile): Tile =
    tile.convert(DoubleCellType).combineDouble(0, 3) { (r, nir) =>
      (nir - r) / (nir + r)
    }
}
