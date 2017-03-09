package com.azavea.server

import geotrellis.raster._

object NDWI extends (MultibandTile => Tile) {
  // accepts 2band multiband tile
  def apply(tile: MultibandTile): Tile =
    tile.convert(DoubleCellType).combineDouble(0, 1) { (g, nir) =>
      (g - nir) / (g + nir)
    }
}
