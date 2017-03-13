package com.azavea.server

import geotrellis.raster._
import geotrellis.raster.render._

object Render {

  val nameToBand =
    Map(
      "R" -> 0,
      "G" -> 1,
      "B" -> 2,
      "IR" -> 3,
      "L0" -> 4,
      "L1" -> 5,
      "L2" -> 6,
      "LNB0" -> 7,
      "LNB1" -> 8,
      "LNB2" -> 9,
      "DSM" -> 10
    )

  val ndviColorBreaks =
    ColorMap.fromStringDouble("0.05:ffffe5aa;0.1:f7fcb9ff;0.2:d9f0a3ff;0.3:addd8eff;0.4:78c679ff;0.5:41ab5dff;0.6:238443ff;0.7:006837ff;1:004529ff").get

  val ndwiColorBreaks =
    ColorMap.fromStringDouble("0:aacdff44;0.1:70abffff;0.2:3086ffff;0.3:1269e2ff;0.4:094aa5ff;1:012c69ff").get

  val ndviDiffColorBreaks =
    ColorMap.fromStringDouble("-0.6:FF4040FF;-0.5:FF5353FF;-0.4:FF6666FF;-0.3:FF7979FF;-0.2:FF8C8CFF;-0.1:FF9F9FFF;0:709AB244;0.1:81D3BBFF;0.2:67CAAEFF;0.3:4EC2A0FF;0.4:35B993FF;0.5:1CB085FF;0.6:03A878FF").get

  val waterDiffColorBreaks =
    ColorMap.fromStringDouble("0.2:aacdff44;0.3:1269e2ff;0.4:094aa5ff;1:012c69ff").get

  def image(tile: MultibandTile): Png = {
    val (red, green, blue) =
      if(tile.cellType == UShortCellType) {
        // Landsat

        // magic numbers. Fiddled with until visually it looked ok. ¯\_(ツ)_/¯
        val (min, max) = (4000, 15176)

        def clamp(z: Int) = {
          if(isData(z)) { if(z > max) { max } else if(z < min) { min } else { z } }
          else { z }
        }
        val red = tile.band(0).convert(IntCellType).map(clamp _).normalize(min, max, 0, 255)
        val green = tile.band(1).convert(IntCellType).map(clamp _).normalize(min, max, 0, 255)
        val blue = tile.band(2).convert(IntCellType).map(clamp _).normalize(min, max, 0, 255)

        (red, green, blue)
      } else {
        // Planet Labs
        (tile.band(0).combine(tile.band(3)) { (z, m) => if(m == 0) 0 else z },
          tile.band(1).combine(tile.band(3)) { (z, m) => if(m == 0) 0 else z },
          tile.band(2).combine(tile.band(3)) { (z, m) => if(m == 0) 0 else z })
      }


    def clampColor(c: Int): Int =
      if(isNoData(c)) { c }
      else {
        if(c < 0) { 0 }
        else if(c > 255) { 255 }
        else c
      }

    // -255 to 255
    val brightness = 15
    def brightnessCorrect(v: Int): Int =
      if(v > 0) { v + brightness }
      else { v }

    // 0.01 to 7.99
    val gamma = 0.8
    val gammaCorrection = 1 / gamma
    def gammaCorrect(v: Int): Int =
      (255 * math.pow(v / 255.0, gammaCorrection)).toInt

    // -255 to 255
    val contrast: Double = 30.0
    val contrastFactor = (259 * (contrast + 255)) / (255 * (259 - contrast))
    def contrastCorrect(v: Int): Int =
      ((contrastFactor * (v - 128)) + 128).toInt

    def adjust(c: Int): Int = {
      if(isData(c)) {
        var cc = c
        cc = clampColor(brightnessCorrect(cc))
        cc = clampColor(gammaCorrect(cc))
        cc = clampColor(contrastCorrect(cc))
        cc
      } else {
        c
      }
    }

    val adjRed = red.map(adjust _)
    val adjGreen = green.map(adjust _)
    val adjBlue = blue.map(adjust _)

    ArrayMultibandTile(adjRed, adjGreen, adjBlue).renderPng
  }

  def ndvi(tile: MultibandTile): Png =
    NDVI(tile).renderPng(ndviColorBreaks)

  def ndvi(tile1: MultibandTile, tile2: MultibandTile): Png =
    (NDVI(tile1) - NDVI(tile2)).renderPng(ndviDiffColorBreaks)

  def ndwi(tile: MultibandTile): Png =
    NDWI(tile).renderPng(ndwiColorBreaks)

  def ndwi(tile1: MultibandTile, tile2: MultibandTile): Png =
    (NDWI(tile1) - NDWI(tile2)).renderPng(waterDiffColorBreaks)
}
