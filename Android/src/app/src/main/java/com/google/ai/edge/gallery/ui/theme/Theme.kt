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

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// One scheme for the whole app, like the Interface (no light theme, no theme picker).
private val msScheme =
  darkColorScheme(
    primary = MsAccent,
    onPrimary = MsAccentContrast,
    primaryContainer = MsAccentSurface,
    onPrimaryContainer = MsAccentSoft,
    inversePrimary = MsDataSeq1,
    secondary = MsTitanium,
    onSecondary = MsAccentContrast,
    secondaryContainer = MsAccentSurface,
    onSecondaryContainer = MsAccentSoft,
    tertiary = MsChart3,
    onTertiary = MsAccentContrast,
    tertiaryContainer = MsSurfaceItem,
    onTertiaryContainer = MsTextPrimary,
    error = MsStatusErrorText,
    onError = MsBgPage,
    errorContainer = MsStatusErrorSurface,
    onErrorContainer = MsStatusErrorText,
    background = MsBgPage,
    onBackground = MsTextPrimary,
    surface = MsBgPage,
    onSurface = MsTextPrimary,
    surfaceVariant = MsSurfaceItemMuted,
    onSurfaceVariant = MsTextSecondary,
    // No tonal tint: elevated surfaces stay neutral, not blue.
    surfaceTint = Color.Transparent,
    inverseSurface = MsTextPrimary,
    inverseOnSurface = MsBgPage,
    outline = MsBorderStrong,
    outlineVariant = MsBorderMuted,
    scrim = MsBgPageDeep,
    surfaceBright = MsSurfaceItemBright,
    surfaceDim = MsBgPage,
    surfaceContainerLowest = MsBgPageDeep,
    surfaceContainerLow = MsSurfacePanelAlt,
    surfaceContainer = MsSurfacePanel,
    surfaceContainerHigh = MsBgPopover,
    surfaceContainerHighest = MsSurfaceItem,
  )

// Radii from DESIGN.md: icon button 10, input 12, card 18, panel/tile 20, global 24.
val MsShapes =
  Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
  )

@Immutable
data class CustomColors(
  val appTitleGradientColors: List<Color> = listOf(),
  val tabHeaderBgColor: Color = Color.Transparent,
  val taskCardBgColor: Color = Color.Transparent,
  val taskBgColors: List<Color> = listOf(),
  val taskBgGradientColors: List<List<Color>> = listOf(),
  val taskIconColors: List<Color> = listOf(),
  val taskIconShapeBgColor: Color = Color.Transparent,
  val homeBottomGradient: List<Color> = listOf(),
  val userBubbleBgColor: Color = Color.Transparent,
  val agentBubbleBgColor: Color = Color.Transparent,
  val linkColor: Color = Color.Transparent,
  val successColor: Color = Color.Transparent,
  val positiveStrokeColor: Color = Color.Transparent,
  val negativeStrokeColor: Color = Color.Transparent,
  val lassoStrokeColor: Color = Color.Transparent,
  val recordButtonBgColor: Color = Color.Transparent,
  val waveFormBgColor: Color = Color.Transparent,
  val modelInfoIconColor: Color = Color.Transparent,
  val warningContainerColor: Color = Color.Transparent,
  val warningTextColor: Color = Color.Transparent,
  val errorContainerColor: Color = Color.Transparent,
  val errorTextColor: Color = Color.Transparent,
  val newFeatureContainerColor: Color = Color.Transparent,
  val newFeatureTextColor: Color = Color.Transparent,
  val bgStarColor: Color = Color.Transparent,
  val promoBannerBgBrush: Brush = SolidColor(Color.Transparent),
  val promoBannerIconBgBrush: Brush = SolidColor(Color.Transparent),
)

val LocalCustomColors = staticCompositionLocalOf { CustomColors() }

// No gradients (DESIGN.md rule 5): every former gradient pair is one flat token twice.
private val taskIcons = listOf(MsChart1, MsChart2, MsChart3, MsDataSeq2)

val msCustomColors =
  CustomColors(
    appTitleGradientColors = listOf(MsTextPrimary, MsTextPrimary),
    tabHeaderBgColor = MsAccent,
    taskCardBgColor = MsSurfacePanel,
    taskBgColors = listOf(MsSurfacePanel, MsSurfacePanel, MsSurfacePanel, MsSurfacePanel),
    taskBgGradientColors = taskIcons.map { listOf(it, it) },
    taskIconColors = taskIcons,
    taskIconShapeBgColor = MsSurfaceItemMuted,
    homeBottomGradient = listOf(Color.Transparent, Color.Transparent),
    agentBubbleBgColor = MsSurfacePanel,
    userBubbleBgColor = MsAccentSurface,
    linkColor = MsAccent,
    successColor = MsStatusOkText,
    positiveStrokeColor = MsStatusOk,
    negativeStrokeColor = MsStatusError,
    lassoStrokeColor = MsAccent,
    recordButtonBgColor = MsStatusError,
    waveFormBgColor = MsTextMuted,
    modelInfoIconColor = MsTextMuted,
    warningContainerColor = MsStatusWarnSurface,
    warningTextColor = MsStatusWarnText,
    errorContainerColor = MsStatusErrorSurface,
    errorTextColor = MsStatusErrorText,
    newFeatureContainerColor = MsAccentSurface,
    newFeatureTextColor = MsAccentSoft,
    bgStarColor = MsBorderFaint,
    promoBannerBgBrush = SolidColor(MsSurfacePanel),
    promoBannerIconBgBrush = SolidColor(MsSurfaceItemMuted),
  )

val MaterialTheme.customColors: CustomColors
  @Composable @ReadOnlyComposable get() = LocalCustomColors.current

/** Light status and navigation bar icons on the dark page background. */
@Composable
fun StatusBarColorController() {
  val view = LocalView.current
  val currentWindow = (view.context as? Activity)?.window

  if (currentWindow != null) {
    SideEffect {
      WindowCompat.setDecorFitsSystemWindows(currentWindow, false)
      val controller = WindowCompat.getInsetsController(currentWindow, view)
      controller.isAppearanceLightStatusBars = false
      controller.isAppearanceLightNavigationBars = false
    }
  }
}

@Composable
fun GalleryTheme(content: @Composable () -> Unit) {
  val view = LocalView.current

  StatusBarColorController()

  CompositionLocalProvider(LocalCustomColors provides msCustomColors) {
    MaterialTheme(
      colorScheme = msScheme,
      typography = AppTypography,
      shapes = MsShapes,
      content = content,
    )
  }

  LaunchedEffect(Unit) {
    val window = (view.context as Activity).window

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
  }
}
