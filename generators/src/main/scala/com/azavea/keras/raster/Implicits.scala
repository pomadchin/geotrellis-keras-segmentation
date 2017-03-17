package com.azavea.keras.raster

import geotrellis.raster._
import geotrellis.vector.Extent

import spire.syntax.cfor._

import scala.concurrent.forkjoin.ThreadLocalRandom

object Implicits extends Implicits

trait Implicits {
  implicit class withRndExtentFunctions(that: Extent) {
    def randomSquare(height: Double, width: Double) = {
      val newXMin = {
        if(that.xmin == that.xmax - width) that.xmin
        else ThreadLocalRandom.current().nextDouble(that.xmin, that.xmax - width)
      }
      val newYMin = {
        if(that.ymin == that.ymax - height) that.ymin
        else ThreadLocalRandom.current().nextDouble(that.ymin, that.ymax - height)
      }
      val newXMax = newXMin + width
      val newYMax = newYMin + height

      Extent(xmin = newXMin, xmax = newXMax, ymin = newYMin, ymax = newYMax)
    }
  }

  implicit class withTileSpaceFunctions(val that: Tile) extends SpaceFunctions[Tile]{
    def rotate90(n: Int = 1): Tile = {
      val (rows, cols) = that.rows -> that.cols
      if (n % 4 == 0) that
      else if (n % 2 == 0) {
        val tile = ArrayTile.alloc(that.cellType, that.cols, that.rows)
        if (!that.cellType.isFloatingPoint) {
          cfor(0)(_ < cols, _ + 1) { col =>
            cfor(0)(_ < rows, _ + 1) { row =>
              tile.set(cols - 1 - col, rows - 1 - row, that.get(col, row))
            }
          }
        } else {
          cfor(0)(_ < cols, _ + 1) { col =>
            cfor(0)(_ < rows, _ + 1) { row =>
              tile.setDouble(cols - 1 - col, rows - 1 - row, that.getDouble(col, row))
            }
          }
        }
        tile
      } else if (n % 3 == 0) {
        val tile = ArrayTile.alloc(that.cellType, that.rows, that.cols)

        if (!that.cellType.isFloatingPoint) {
          cfor(0)(_ < cols, _ + 1) { col =>
            cfor(0)(_ < rows, _ + 1) { row =>
              tile.set(rows - 1 - row, col, that.get(col, row))
            }
          }
        } else {
          cfor(0)(_ < cols, _ + 1) { col =>
            cfor(0)(_ < rows, _ + 1) { row =>
              tile.setDouble(rows - 1 - row, col, that.getDouble(col, row))
            }
          }
        }

        tile
      } else {
        val tile = ArrayTile.alloc(that.cellType, that.rows, that.cols)
        if (!that.cellType.isFloatingPoint) {
          cfor(0)(_ < cols, _ + 1) { col =>
            cfor(0)(_ < rows, _ + 1) { row =>
              tile.set(row, cols - 1 - col, that.get(col, row))
            }
          }
        } else {
          cfor(0)(_ < cols, _ + 1) { col =>
            cfor(0)(_ < rows, _ + 1) { row =>
              tile.setDouble(row, cols - 1 - col, that.getDouble(col, row))
            }
          }
        }

        tile
      }
    }

    def flipVertical: Tile = {
      val (rows, cols) = that.rows -> that.cols
      val tile = ArrayTile.alloc(that.cellType, cols, rows)

      if (!that.cellType.isFloatingPoint) {
        cfor(0)(_ < cols, _ + 1) { col =>
          cfor(0)(_ < rows, _ + 1) { row =>
            tile.set(cols - 1 - col, row, that.get(col, row))
            tile.set(col, row, that.get(cols - col - 1, row))
          }
        }
      } else {
        cfor(0)(_ < cols, _ + 1) { col =>
          cfor(0)(_ < rows, _ + 1) { row =>
            tile.setDouble(cols - 1 - col, row, that.getDouble(col, row))
            tile.setDouble(col, row, that.getDouble(cols - col - 1, row))
          }
        }
      }

      tile
    }

    def flipHorizontal: Tile = {
      val (rows, cols) = that.rows -> that.cols
      val tile = ArrayTile.alloc(that.cellType, cols, rows)

      if (!that.cellType.isFloatingPoint) {
        cfor(0)(_ < cols, _ + 1) { col =>
          cfor(0)(_ < rows, _ + 1) { row =>
            tile.set(col, rows - 1 - row, that.get(col, row))
            tile.set(col, row, that.get(col, rows - 1 - row))
          }
        }
      } else {
        cfor(0)(_ < cols, _ + 1) { col =>
          cfor(0)(_ < rows, _ + 1) { row =>
            tile.setDouble(col, rows - 1 - row, tile.getDouble(col, row))
            tile.setDouble(col, row, tile.getDouble(col, rows - 1 - row))
          }
        }
      }

      tile
    }

    def zscore: Tile = {
      if(!that.cellType.isFloatingPoint) {
        val stats = that.statistics.getOrElse(sys.error("No stats for a tile."))
        that.mapIfSet(z => ((z - stats.mean) / stats.stddev).toInt)
      } else {
        val stats = that.statisticsDouble.getOrElse(sys.error("No stats for a tile."))
        that.mapIfSetDouble(z => ((z - stats.mean) / stats.stddev).toInt)
      }
    }
  }

  implicit class withMultibandTileSpaceFunctions(val that: MultibandTile) extends SpaceFunctions[MultibandTile] {
    def rotate90(n: Int = 1): MultibandTile = that.mapBands { (_, tile) => tile.rotate90(n) }
    def flipVertical: MultibandTile = that.mapBands { (_, tile) => tile.flipVertical }
    def flipHorizontal: MultibandTile = that.mapBands { (_, tile) => tile.flipHorizontal }
    def zscore: MultibandTile = that.mapBands { (_, tile) => tile.zscore }
  }

  implicit class withRasterSpaceFunctions[T <: CellGrid: ? => SpaceFunctions[T]](that: Raster[T]) {
    def rotate90(n: Int = 1): Raster[T] = Raster(that.tile.rotate90(n), that.extent)
    def flipVertical: Raster[T] = Raster(that.tile.flipVertical, that.extent)
    def flipHorizontal: Raster[T] = Raster(that.tile.flipHorizontal, that.extent)
    def zscore: Raster[T] = Raster(that.tile.zscore, that.extent)
  }
}
