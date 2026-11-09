/*
 * Copyright 2026 Pascal Fritzsche
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

package com.google.ai.edge.gallery.mediagallery

import com.google.ai.edge.gallery.ui.theme.MsStatusError
import com.google.ai.edge.gallery.ui.theme.MsDataSeq5
import com.google.ai.edge.gallery.ui.theme.MsDataSeq4
import com.google.ai.edge.gallery.ui.theme.MsDataSeq3
import com.google.ai.edge.gallery.ui.theme.MsDataSeq2
import com.google.ai.edge.gallery.ui.theme.MsChart3
import com.google.ai.edge.gallery.ui.theme.MsChart2
import androidx.compose.ui.graphics.Color

/** Formats other than JPEG mix this colour into their position colour. */
private val formatColors =
  linkedMapOf(
    "PNG" to MsDataSeq4,
    "HEIC" to MsChart3,
    "WebP" to MsDataSeq2,
    "GIF" to MsDataSeq5,
    "MP4" to MsStatusError,
    "Video" to MsDataSeq3,
    "Andere" to MsChart2,
  )

fun formatOf(mime: String?, kind: String?): String {
  val m = mime.orEmpty().lowercase()
  return when {
    "jpeg" in m || "jpg" in m -> "JPEG"
    "png" in m -> "PNG"
    "heic" in m || "heif" in m -> "HEIC"
    "webp" in m -> "WebP"
    "gif" in m -> "GIF"
    "mp4" in m -> "MP4"
    m.startsWith("video") || kind == "video" -> "Video"
    else -> "Andere"
  }
}

/**
 * Colour from the position in the cube (x -> red, y -> green, z -> blue), so neighbours share a
 * hue and clusters stand out. Non-JPEG points take the average with their format colour.
 */
fun pointColor(x: Float, y: Float, z: Float, format: String): Color {
  fun channel(v: Float) = 0.2f + 0.8f * ((v.coerceIn(-1f, 1f) + 1f) / 2f)
  val pos = Color(channel(x), channel(y), channel(z))
  val fmt = formatColors[format] ?: return pos
  return Color((pos.red + fmt.red) / 2, (pos.green + fmt.green) / 2, (pos.blue + fmt.blue) / 2)
}
