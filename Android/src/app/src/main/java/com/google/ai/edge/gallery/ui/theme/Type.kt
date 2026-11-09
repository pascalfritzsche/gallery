/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R

// Fonts from the Interface (DESIGN.md "Typografie"): Sora for headings, IBM Plex Sans for
// text, IBM Plex Mono for numbers. Sora and Plex Sans are variable fonts.
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
  Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val displayFontFamily =
  FontFamily(
    variable(R.font.sora_latin, 400),
    variable(R.font.sora_latin, 600),
    variable(R.font.sora_latin, 700),
  )

val appFontFamily =
  FontFamily(
    variable(R.font.ibm_plex_sans_latin, 300),
    variable(R.font.ibm_plex_sans_latin, 400),
    variable(R.font.ibm_plex_sans_latin, 500),
    variable(R.font.ibm_plex_sans_latin, 600),
    variable(R.font.ibm_plex_sans_latin, 700),
  )

val monoFontFamily = FontFamily(Font(R.font.ibm_plex_mono_500_latin, FontWeight.Medium))

val baseline = Typography()

private fun TextStyle.display() = copy(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold)

val AppTypography =
  Typography(
    displayLarge = baseline.displayLarge.display(),
    displayMedium = baseline.displayMedium.display(),
    displaySmall = baseline.displaySmall.display(),
    headlineLarge = baseline.headlineLarge.display(),
    headlineMedium = baseline.headlineMedium.display(),
    headlineSmall = baseline.headlineSmall.display(),
    titleLarge = baseline.titleLarge.copy(fontFamily = appFontFamily, fontWeight = FontWeight.SemiBold),
    titleMedium = baseline.titleMedium.copy(fontFamily = appFontFamily, fontWeight = FontWeight.SemiBold),
    titleSmall = baseline.titleSmall.copy(fontFamily = appFontFamily, fontWeight = FontWeight.SemiBold),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = appFontFamily),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = appFontFamily),
    bodySmall = baseline.bodySmall.copy(fontFamily = appFontFamily),
    labelLarge = baseline.labelLarge.copy(fontFamily = appFontFamily),
    labelMedium = baseline.labelMedium.copy(fontFamily = appFontFamily),
    labelSmall = baseline.labelSmall.copy(fontFamily = appFontFamily),
  )

/** Times, counts and measured values (DESIGN.md: IBM Plex Mono 500). */
val numberStyle = baseline.labelMedium.copy(fontFamily = monoFontFamily, fontWeight = FontWeight.Medium)

val titleMediumNarrow =
  baseline.titleMedium.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp)

val titleSmaller =
  baseline.titleSmall.copy(
    fontFamily = appFontFamily,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
  )

val labelSmallNarrow = baseline.labelSmall.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp)

val labelSmallNarrowMedium =
  baseline.labelSmall.copy(
    fontFamily = appFontFamily,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.0.sp,
  )

val bodySmallNarrow = baseline.bodySmall.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp)

val bodySmallMediumNarrow =
  baseline.bodySmall.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp, fontSize = 14.sp)

val bodySmallMediumNarrowBold =
  baseline.bodySmall.copy(
    fontFamily = appFontFamily,
    letterSpacing = 0.0.sp,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
  )

val homePageTitleStyle =
  baseline.displayMedium.copy(
    fontFamily = displayFontFamily,
    fontSize = 48.sp,
    lineHeight = 48.sp,
    letterSpacing = -1.sp,
    fontWeight = FontWeight.Bold,
  )

val bodyLargeNarrow = baseline.bodyLarge.copy(fontFamily = appFontFamily, letterSpacing = 0.2.sp)
val bodyMediumMedium = baseline.bodyMedium.copy(fontFamily = appFontFamily, fontWeight = FontWeight.Medium)

val headlineLargeMedium = baseline.headlineLarge.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.SemiBold)

val emptyStateTitle = baseline.headlineSmall.copy(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, fontSize = 37.sp, lineHeight = 50.sp)
val emptyStateContent = baseline.headlineSmall.copy(fontFamily = appFontFamily, fontSize = 16.sp, lineHeight = 22.sp)
