package com.azavea.server

import geotrellis.raster._

object NDVI extends (MultibandTile => Tile) {
  // accepts 2 band multiband tile
  def apply(tile: MultibandTile): Tile =
    tile.convert(DoubleCellType).combineDouble(0, 1) { (r, nir) =>
      (nir - r) / (nir + r)
    }
}
