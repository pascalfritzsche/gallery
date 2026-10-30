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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.ai.edge.gallery.morgenschiss.Bubble
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private const val CAMERA = 4f
/** Radians per pixel dragged: a drag across the screen turns the scene by about 200 degrees. */
private const val TURN_PER_PX = 0.0035f

/** A bubble projected onto the screen for the current camera. */
private data class Projected(val index: Int, val x: Float, val y: Float, val r: Float, val depth: Float)

/** Orbit camera around (cx, cy, cz): turned by yaw and pitch, zoomed by scale. */
private class Projector(val cx: Float, val cy: Float, val cz: Float, val scale: Float, val ox: Float, val oy: Float, yaw: Float, pitch: Float) {
  private val cyaw = cos(yaw)
  private val syaw = sin(yaw)
  private val cp = cos(pitch)
  private val sp = sin(pitch)

  /** Returns screen x, screen y, depth (bigger = nearer) and the perspective factor. */
  fun project(x: Float, y: Float, z: Float): FloatArray {
    val px = x - cx
    val py = y - cy
    val pz = z - cz
    val x1 = px * cyaw + pz * syaw
    val z1 = -px * syaw + pz * cyaw
    val y2 = py * cp - z1 * sp
    val z2 = py * sp + z1 * cp
    val f = CAMERA / (CAMERA - z2).coerceAtLeast(0.2f)
    return floatArrayOf(ox + x1 * f * scale, oy - y2 * f * scale, z2, f)
  }
}

/** Next bubble on screen in a direction (dx, dy), within 60 degrees; nearest wins. */
private fun neighbour(from: Projected, all: List<Projected>, dx: Float, dy: Float): Int? {
  val want = atan2(dy, dx)
  return all
    .filter { it.index != from.index }
    .mapNotNull { p ->
      val vx = p.x - from.x
      val vy = p.y - from.y
      var diff = abs(atan2(vy, vx) - want)
      if (diff > Math.PI) diff = (2 * Math.PI - diff).toFloat()
      if (diff > Math.PI / 3) null else p.index to hypot(vx, vy) * (1 + diff)
    }
    .minByOrNull { it.second }
    ?.first
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleScreen(viewModel: BubbleViewModel, onBack: () -> Unit, onOpen: (List<MediaItem>, Int) -> Unit) {
  LaunchedEffect(Unit) { if (viewModel.state !is Loadable.Done) viewModel.load() }
  val parent = viewModel.parent()
  // back: first the selection, then one level up, then out
  BackHandler { if (!viewModel.back()) onBack() }
  var renaming by remember { mutableStateOf<Bubble?>(null) }
  renaming?.let { b -> RenameDialog(b, onDismiss = { renaming = null }) { name -> viewModel.rename(b, name); renaming = null } }
  var albumFor by remember { mutableStateOf<Bubble?>(null) }
  val scope = rememberCoroutineScope()
  val actions = rememberMediaActions(onChanged = {})
  val context = androidx.compose.ui.platform.LocalContext.current
  LaunchedEffect(viewModel.message) {
    viewModel.message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show(); viewModel.message = null }
  }
  albumFor?.let { b ->
    AlbumDialog(b, onDismiss = { albumFor = null }) { name, folder ->
      albumFor = null
      scope.launch {
        // a real folder: the album's files move there now, new ones later from the banner
        viewModel.createAlbum(b, name, folder)?.let { (files, path) ->
          actions.moveEach(files.filter { it.relativePath != path }.associateWith { path })
        }
      }
    }
  }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(parent?.title ?: "Bubbles", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = { if (!viewModel.back()) onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
      )
    }
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (val s = viewModel.state) {
        is Loadable.Done -> {
          val level = viewModel.level()
          if (level.isEmpty()) {
            Text("Noch zu wenige Medien am Server für Bubbles.", modifier = Modifier.padding(24.dp))
          } else {
            BubbleSpace(viewModel, level, onOpen, onRename = { renaming = it }, onAlbum = { albumFor = it })
          }
        }
        is Loadable.Error -> Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        else ->
          Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Bubbles werden berechnet …", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
          }
      }
    }
  }
}

/** Convex hull (Andrew's monotone chain) of screen points, counter-clockwise. */
private fun hull(points: List<Offset>): List<Offset> {
  if (points.size < 3) return points
  val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
  fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
  val lower = ArrayList<Offset>()
  for (p in sorted) {
    while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.lastIndex)
    lower += p
  }
  val upper = ArrayList<Offset>()
  for (p in sorted.asReversed()) {
    while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.lastIndex)
    upper += p
  }
  return lower.dropLast(1) + upper.dropLast(1)
}

private fun inside(poly: List<Offset>, t: Offset): Boolean {
  var hit = false
  var j = poly.lastIndex
  for (i in poly.indices) {
    val a = poly[i]
    val b = poly[j]
    if ((a.y > t.y) != (b.y > t.y) && t.x < (b.x - a.x) * (t.y - a.y) / (b.y - a.y) + a.x) hit = !hit
    j = i
  }
  return hit
}

/** The outline of one bubble on screen: the hull of its items, pushed out a little. */
private class Shape(val index: Int, val outline: List<Offset>, val centre: Offset, val depth: Float)

@Composable
private fun BubbleSpace(
  viewModel: BubbleViewModel,
  level: List<Bubble>,
  onOpen: (List<MediaItem>, Int) -> Unit,
  onRename: (Bubble) -> Unit,
  onAlbum: (Bubble) -> Unit,
) {
  val focus = viewModel.focus.takeIf { it in level.indices }
  val focused = focus?.let { level[it] }
  // flattening thousands of members must not happen every animation frame
  val levelMembers = remember(level) { level.map { it.allMembers() } }
  // the outline wraps a bubble's core (80 % nearest its middle): with every outlier inside, the
  // outlines of neighbouring bubbles covered each other almost completely
  val cores = remember(level) {
    level.mapIndexed { i, b ->
      levelMembers[i].indices.sortedBy { j -> val m = levelMembers[i][j]; hypot(hypot(m.x - b.x, m.y - b.y), m.z - b.z) }
        .take(maxOf(3, (levelMembers[i].size * 0.8f).toInt()))
    }
  }
  // nothing selected: the whole level is in view, each item at its real place
  val middle = remember(level) {
    val all = levelMembers.flatten()
    floatArrayOf(all.map { it.x }.average().toFloat(), all.map { it.y }.average().toFloat(), all.map { it.z }.average().toFloat())
  }
  val extent = remember(level) {
    val d = levelMembers.flatten().map { hypot(hypot(it.x - middle[0], it.y - middle[1]), it.z - middle[2]) }.sorted()
    (d.getOrNull((d.size * 0.98f).toInt()) ?: 0.5f).coerceAtLeast(0.05f)
  }
  val anim = tween<Float>(durationMillis = 650)
  val cx by animateFloatAsState(focused?.x ?: middle[0], anim, label = "cx")
  val cy by animateFloatAsState(focused?.y ?: middle[1], anim, label = "cy")
  val cz by animateFloatAsState(focused?.z ?: middle[2], anim, label = "cz")
  val fit = if (focused == null) 0.45f / (0.36f * extent) else 0.22f / (0.36f * focused.r.coerceAtLeast(0.02f))
  val zoom by animateFloatAsState(fit.coerceIn(0.3f, 10f), anim, label = "zoom")
  val members = remember(focused, viewModel.items) { focused?.allMembers()?.mapNotNull { m -> viewModel.items[m.id]?.let { m to it } } ?: emptyList() }
  val loader = rememberGalleryImageLoader()
  val density = LocalDensity.current

  BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0E1216))) {
    val w = constraints.maxWidth.toFloat()
    val h = constraints.maxHeight.toFloat()
    // the space sits above the sheet when a bubble is selected
    val oy = h * (if (focused == null) 0.5f else if (viewModel.sheetOpen) 0.22f else 0.42f)
    val projector = Projector(cx, cy, cz, minOf(w, h) * 0.36f * zoom * viewModel.zoom, w / 2, oy, viewModel.yaw, viewModel.pitch)
    val pad = with(density) { 8.dp.toPx() }
    val dotPoints = levelMembers.map { ms -> ms.map { m -> projector.project(m.x, m.y, m.z).let { Offset(it[0], it[1]) } } }
    val shapes =
      level.mapIndexed { i, b ->
        val c = projector.project(b.x, b.y, b.z)
        val centre = Offset(c[0], c[1])
        // a little air around the outermost items
        val outline = hull(cores[i].map { dotPoints[i][it] }).map { p -> val d = p - centre; val len = d.getDistance().coerceAtLeast(1f); p + d * (pad / len) }
        Shape(i, outline, centre, c[2])
      }
    val nearest = shapes.maxOf { it.depth }
    val farthest = shapes.minOf { it.depth }
    val fog = { sh: Shape -> if (nearest > farthest) 0.4f + 0.6f * (sh.depth - farthest) / (nearest - farthest) else 1f }
    val highlight = members.getOrNull(viewModel.page)?.first
    // gesture handlers live across recompositions: they must read the current shapes
    val currentShapes by rememberUpdatedState(shapes)
    val currentFocused by rememberUpdatedState(focused)

    Canvas(
      Modifier.fillMaxSize()
        // one finger turns the scene, two fingers zoom
        .pointerInput(Unit) {
          detectTransformGestures { _, pan, zoomChange, _ ->
            viewModel.turn(pan.x * TURN_PER_PX, -pan.y * TURN_PER_PX)
            viewModel.pinch(zoomChange)
          }
        }
        .pointerInput(level) {
          detectTapGestures(
            // a tap selects the nearest bubble under the finger; a tap on empty space clears it
            onTap = { t -> viewModel.focusOn(currentShapes.filter { it.outline.size >= 3 && inside(it.outline, t) }.maxByOrNull { it.depth }?.index ?: -1) },
            onDoubleTap = { currentFocused?.let { if (it.children.isNotEmpty()) viewModel.enter(it) } },
          )
        }
    ) {
      for (sh in shapes.sortedBy { it.depth }) {
        val b = level[sh.index]
        val selected = sh.index == focus
        if (sh.outline.size >= 3) {
          val path = Path().apply {
            moveTo(sh.outline[0].x, sh.outline[0].y)
            for (p in sh.outline.drop(1)) lineTo(p.x, p.y)
            close()
          }
          // only the selected bubble has colour; the rest are faint outlines, so the structure shows
          if (selected) drawPath(path, pointColor(b.x, b.y, b.z, "JPEG").copy(alpha = 0.32f))
          else drawPath(path, Color.White.copy(alpha = 0.035f * fog(sh)))
          drawPath(
            path,
            Color.White.copy(alpha = if (selected) 0.85f else 0.16f * fog(sh)),
            style = Stroke(width = (if (selected) 1.8.dp else 1.dp).toPx(), join = StrokeJoin.Round),
          )
        }
        val dotR = (if (selected) 2.2.dp else 1.4.dp).toPx()
        val dotColor = Color.White.copy(alpha = if (selected) 0.9f else if (focus == null) 0.55f * fog(sh) else 0.3f * fog(sh))
        for (q in dotPoints[sh.index]) drawCircle(dotColor, dotR, q)
      }
      highlight?.let { m ->
        val q = projector.project(m.x, m.y, m.z)
        drawCircle(Color(0xFFFFD54F), 4.dp.toPx(), Offset(q[0], q[1]))
        drawCircle(Color(0xFFFFD54F), 9.dp.toPx(), Offset(q[0], q[1]), style = Stroke(2.dp.toPx()))
      }
    }

    // every other bubble: its best picture in the middle, the name below; big ones first, a
    // badge that would cover another waits until turning or zooming frees its spot
    val badgeW = with(density) { 88.dp.toPx() }
    val badgeH = with(density) { 64.dp.toPx() }
    val placed = ArrayList<Offset>()
    val badges = shapes
      .filter { it.index != focus && it.centre.x in 0f..w && it.centre.y in 0f..h }
      .sortedByDescending { level[it.index].size }
      .filter { sh -> placed.none { abs(it.x - sh.centre.x) < badgeW && abs(it.y - sh.centre.y) < badgeH }.also { if (it) placed += sh.centre } }
    for (sh in badges.sortedBy { it.depth }) {
      val b = level[sh.index]
      Column(
        Modifier.offset { IntOffset((sh.centre.x - badgeW / 2).roundToInt(), (sh.centre.y - with(density) { 22.dp.toPx() }).roundToInt()) }
          .width(88.dp)
          .clickable { viewModel.focusOn(sh.index) },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        b.previews.firstNotNullOfOrNull { viewModel.items[it] }?.let { item ->
          AsyncImage(
            model = MediaThumb(item.uri, 192),
            imageLoader = loader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.55f + 0.45f * fog(sh),
            modifier = Modifier.size(40.dp).clip(CircleShape),
          )
        }
        Text(
          b.title,
          color = Color.White.copy(alpha = 0.55f + 0.45f * fog(sh)),
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 2.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
      }
    }

    if (focused != null && focus != null) {
      val sh = shapes[focus]
      // the selected bubble: a line up from its top to a balloon with its best pictures
      val top = (sh.outline.minOfOrNull { it.y } ?: sh.centre.y)
      val lineLen = with(density) { 24.dp.toPx() }
      Canvas(Modifier.fillMaxSize()) {
        drawLine(Color.White.copy(alpha = 0.6f), Offset(sh.centre.x, top), Offset(sh.centre.x, top - lineLen), strokeWidth = 1.5.dp.toPx())
      }
      Callout(
        bubble = focused,
        items = viewModel.items,
        loader = loader,
        focused = true,
        modifier = Modifier.offset { IntOffset((sh.centre.x - with(density) { 64.dp.toPx() }).roundToInt(), (top - lineLen - with(density) { 64.dp.toPx() }).roundToInt()) },
      )

      // arrows jump to the next bubble in that direction, as seen from the current angle
      val centres = shapes.map { Projected(it.index, it.centre.x, it.centre.y, 0f, it.depth) }
      val dirs = listOf(
        Triple(Icons.Filled.KeyboardArrowLeft, -1f to 0f, Alignment.CenterStart),
        Triple(Icons.Filled.KeyboardArrowRight, 1f to 0f, Alignment.CenterEnd),
        Triple(Icons.Filled.KeyboardArrowUp, 0f to -1f, Alignment.TopCenter),
        Triple(Icons.Filled.KeyboardArrowDown, 0f to 1f, Alignment.BottomCenter),
      )
      Box(Modifier.fillMaxWidth().fillMaxHeight(if (viewModel.sheetOpen) 0.45f else 0.78f).padding(8.dp)) {
        FilledTonalIconButton(onClick = { viewModel.focusOn(-1) }, modifier = Modifier.align(Alignment.TopEnd)) {
          Icon(Icons.Filled.ZoomOutMap, "Alle Bubbles zeigen")
        }
        for ((icon, d, align) in dirs) {
          val target = neighbour(centres[focus], centres, d.first, d.second)
          if (target != null) {
            FilledTonalIconButton(onClick = { viewModel.focusOn(target) }, modifier = Modifier.align(align)) { Icon(icon, "Nächste Bubble") }
          }
        }
      }

      BubbleSheet(
        bubble = focused,
        members = members.map { it.second },
        viewModel = viewModel,
        loader = loader,
        onOpen = onOpen,
        onRename = { onRename(focused) },
        onAlbum = { onAlbum(focused) },
        modifier = Modifier.align(Alignment.BottomCenter),
      )
    }
  }
}

@Composable
private fun Callout(bubble: Bubble, items: Map<String, MediaItem>, loader: coil.ImageLoader, focused: Boolean, modifier: Modifier) {
  Column(
    modifier
      .width(128.dp)
      .background(Color.Black.copy(alpha = if (focused) 0.8f else 0.6f), RoundedCornerShape(12.dp))
      .padding(4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      bubble.previews.take(3).mapNotNull { items[it] }.forEach { item ->
        AsyncImage(
          model = MediaThumb(item.uri, 192),
          imageLoader = loader,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)),
        )
      }
    }
    Text(bubble.title, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BubbleSheet(
  bubble: Bubble,
  members: List<MediaItem>,
  viewModel: BubbleViewModel,
  loader: coil.ImageLoader,
  onOpen: (List<MediaItem>, Int) -> Unit,
  onRename: () -> Unit,
  onAlbum: () -> Unit,
  modifier: Modifier,
) {
  val open = viewModel.sheetOpen
  Column(
    modifier
      .fillMaxWidth()
      .then(if (open) Modifier.fillMaxHeight(0.62f) else Modifier)
      .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
      .pointerInput(Unit) {
        // drag up opens the preview, drag down closes it
        var dy = 0f
        detectVerticalDragGestures(
          onDragStart = { dy = 0f },
          onDragEnd = { if (dy < -40) viewModel.sheetOpen = true else if (dy > 40) viewModel.sheetOpen = false },
          onVerticalDrag = { _, d -> dy += d },
        )
      }
      .navigationBarsPadding()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape).clickable { viewModel.sheetOpen = !open })
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(bubble.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          "${bubble.size} Medien" + (if (bubble.videos > 0) ", davon ${bubble.videos} Videos" else "") +
            (if (bubble.children.isNotEmpty()) " · ${bubble.children.size} Unter-Bubbles" else ""),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, "Umbenennen") }
      IconButton(onClick = onAlbum) { Icon(Icons.Filled.CreateNewFolder, "Als Album oder Ordner speichern") }
      if (bubble.children.isNotEmpty()) {
        IconButton(onClick = { viewModel.enter(bubble) }) { Icon(Icons.Filled.ZoomIn, "Unter-Bubbles zeigen") }
      }
    }
    if (bubble.tags.isNotEmpty()) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        bubble.tags.forEach { AssistChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
      }
    }
    if (!open) {
      Text("Hochziehen für die Bilder", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (members.isNotEmpty()) key(bubble.key) {
      // a new bubble starts its preview at the first picture
      val pager = rememberPagerState(initialPage = viewModel.page.coerceIn(0, members.lastIndex)) { members.size }
      LaunchedEffect(pager, bubble.key) { snapshotFlow { pager.currentPage }.collect { viewModel.page = it } }
      HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f), pageSpacing = 8.dp, key = { members[it].id }) { i ->
        AsyncImage(
          model = MediaThumb(members[i].uri, 1024),
          imageLoader = loader,
          contentDescription = members[i].name,
          contentScale = ContentScale.Fit,
          modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color.Black).clickable { onOpen(members, i) },
        )
      }
      Text("${pager.currentPage + 1} / ${members.size}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
  }
}

@Composable
private fun RenameDialog(bubble: Bubble, onDismiss: () -> Unit, onSave: (String) -> Unit) {
  var name by remember { mutableStateOf(bubble.name ?: bubble.suggested ?: "") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Bubble benennen") },
    text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text("Name") }) },
    confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Speichern") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
  )
}

@Composable
private fun AlbumDialog(bubble: Bubble, onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
  var name by remember { mutableStateOf(bubble.name ?: bubble.suggested ?: "") }
  var folder by remember { mutableStateOf(false) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Album aus dieser Bubble") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text("Name") })
        Text("Neue Fotos, die so aussehen, kommen automatisch dazu.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { folder = !folder }) {
          androidx.compose.material3.Checkbox(checked = folder, onCheckedChange = { folder = it })
          Column {
            Text("Als echten Ordner", style = MaterialTheme.typography.bodyMedium)
            Text(
              "Die Dateien werden nach Pictures/${name.trim().ifEmpty { "…" }}/ verschoben und sind so auch in anderen Apps ein Ordner.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    },
    confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), folder) }) { Text("Anlegen") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
  )
}
