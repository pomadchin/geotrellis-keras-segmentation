/*
 * Copyright 2016 Azavea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.azavea.keras.raster

import geotrellis.vector.Extent

import scala.concurrent.forkjoin.ThreadLocalRandom

object Implicits extends Implicits

trait Implicits extends transform.Implicits {
  implicit class withRndExtentFunctions(that: Extent) {
    def random(height: Double, width: Double) = {
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
}
