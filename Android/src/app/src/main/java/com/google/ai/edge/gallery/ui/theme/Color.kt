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

import androidx.compose.ui.graphics.Color

// Morgenschiss design tokens, mirrored from Interface client/Apps/Main/colors.min.css
// (see DESIGN.md there). One dark scheme, no themes. Translucent surface tokens are
// flattened onto the page background so sheets and dialogs stay opaque.

// Backgrounds
val MsBgPage = Color(0xFF0A0A0B) // --color-bg-page
val MsBgPageAlt = Color(0xFF0E0E10) // --color-bg-page-alt
val MsBgPageDeep = Color(0xFF050505) // --color-bg-page-deep
val MsBgPopover = Color(0xFF121212) // --color-bg-popover
val MsBackdrop = Color(0xB8050506) // --color-backdrop

// Surfaces (rgba(255,255,255,a) over --color-bg-page)
val MsSurfacePanelAlt = Color(0xFF141415) // --color-surface-panel-alt (.04)
val MsSurfacePanel = Color(0xFF19191A) // --color-surface-panel (.06)
val MsSurfaceItemMuted = Color(0xFF1E1E1F) // --color-surface-item-muted (.08)
val MsSurfaceItem = Color(0xFF2C2C2D) // --color-surface-item (.14)
val MsSurfaceItemStrong = Color(0xFF343435) // --color-surface-item-strong (.17)
val MsSurfaceItemBright = Color(0xFF3B3B3C) // --color-surface-item-bright (.20)

// Borders
val MsBorderStrong = Color(0x29FFFFFF) // --color-border-strong
val MsBorderMuted = Color(0x1AFFFFFF) // --color-border-muted
val MsBorderFaint = Color(0x14FFFFFF) // --color-border-faint
val MsBorderDashed = Color(0x38FFFFFF) // --color-border-dashed

// Text
val MsTextPrimary = Color(0xFFF4F4F5) // --color-text-primary
val MsTextSecondary = Color(0xB8FFFFFF) // --color-text-secondary
val MsTextMuted = Color(0x8CFFFFFF) // --color-text-muted

// Accent (ice platinum) and titanium
val MsAccent = Color(0xFF9EC3D1) // --color-accent-primary
val MsAccentSoft = Color(0xFFCFE1E8) // --color-accent-primary-soft
val MsAccentContrast = Color(0xFF0B1417) // --color-accent-primary-contrast
val MsAccentSurface = Color(0xFF22282B) // accent at .16 over the page
val MsTitanium = Color(0xFFC4C8CF) // --color-accent-secondary
val MsFocusRing = Color(0xFF88C0D0) // --color-focus-ring
val MsFavorite = Color(0xFFC9B27E) // --color-favorite, only the filled star

// Status
val MsStatusOk = Color(0xFF22C55E) // --color-status-ok
val MsStatusOkText = Color(0xFF4ADE80) // --color-status-ok-text
val MsStatusError = Color(0xFFEF4444) // --color-status-error
val MsStatusErrorText = Color(0xFFFCA5A5) // --color-status-error-text
val MsStatusErrorSurface = Color(0xFF2F1314) // error at .16 over the page

// Data
val MsChart1 = Color(0xFF9EC3D1) // --chart-1
val MsChart2 = Color(0xFFC4C8CF) // --chart-2
val MsChart3 = Color(0xFF79C79B) // --chart-3
val MsChart4 = Color(0xFFC9B27E) // --chart-4
val MsDataSeq1 = Color(0xFF2F5D7C) // --data-seq-1
val MsDataSeq2 = Color(0xFF5F93AB) // --data-seq-2
val MsDataSeq3 = Color(0xFFB9D3C8) // --data-seq-3
val MsDataSeq4 = Color(0xFFE0B872) // --data-seq-4
val MsDataSeq5 = Color(0xFFD9694F) // --data-seq-5
val MsStatusWarn = Color(0xFFF59E0B) // --color-status-warn
val MsStatusWarnText = Color(0xFFFCD34D) // --color-status-warn-text
val MsStatusWarnSurface = Color(0xFF322412) // warn at .16 over the page
