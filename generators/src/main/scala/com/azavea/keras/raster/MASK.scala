package com.azavea.keras.raster

import geotrellis.raster._

object MASK extends (MultibandTile => Tile) {
  def apply(tile: MultibandTile): Tile =
    tile
      .convert(DoubleCellType)
      .combine(tile.bandCount - 3 until tile.bandCount) { case Seq(r, g, b) => r + g + b }
      .mapDouble(c => if(c == 0d) 0d else 1d)
      .convert(BitCellType)
}
