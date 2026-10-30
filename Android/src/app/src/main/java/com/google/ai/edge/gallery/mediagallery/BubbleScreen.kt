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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Job
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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

/** Radians per pixel dragged: a drag across the screen turns the view by about 120 degrees. */
private const val TURN_PER_PX = 0.002f
/** Closer than this (scene units) a star shows its picture. */
private const val PICTURE_DIST = 0.16f
/** At most this many pictures at once, the nearest. */
private const val MAX_PICTURES = 14

/**
 * First-person camera at (x, y, z), looking along yaw (around the vertical) and pitch. A point
 * projects to screen x, y and its distance in front of the camera; behind it, null.
 */
private class Camera(x: Float, y: Float, z: Float, yaw: Float, pitch: Float, val focal: Float, val ox: Float, val oy: Float) {
  private val px = x
  private val py = y
  private val pz = z
  private val cp = cos(pitch)
  private val sp = sin(pitch)
  // forward, right and up of the camera in the scene
  val fx = sin(yaw) * cp
  val fy = sp
  val fz = cos(yaw) * cp
  val rx = cos(yaw)
  val rz = -sin(yaw)
  private val ux = -sp * sin(yaw)
  private val uy = cp
  private val uz = -sp * cos(yaw)

  /** x, y on screen and the distance in front; null when behind the camera. */
  fun project(x: Float, y: Float, z: Float): FloatArray? {
    val dx = x - px
    val dy = y - py
    val dz = z - pz
    val d = dx * fx + dy * fy + dz * fz
    if (d < 0.01f) return null
    val sx = dx * rx + dz * rz
    val sy = dx * ux + dy * uy + dz * uz
    return floatArrayOf(ox + sx / d * focal, oy - sy / d * focal, d)
  }
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

/** The outline of one bubble on screen: the hull of its core, pushed out a little. */
private class Shape(val index: Int, val outline: List<Offset>, val centre: Offset?, val depth: Float)

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
  // flattening thousands of members must not happen every frame
  val levelMembers = remember(level) { level.map { it.allMembers() } }
  // the outline wraps a bubble's core (80 % nearest its middle): with every outlier inside, the
  // outlines of neighbouring bubbles covered each other almost completely
  val cores = remember(level) {
    level.mapIndexed { i, b ->
      levelMembers[i].indices.sortedBy { j -> val m = levelMembers[i][j]; hypot(hypot(m.x - b.x, m.y - b.y), m.z - b.z) }
        .take(maxOf(3, (levelMembers[i].size * 0.8f).toInt()))
    }
  }
  val middle = remember(level) {
    val all = levelMembers.flatten()
    floatArrayOf(all.map { it.x }.average().toFloat(), all.map { it.y }.average().toFloat(), all.map { it.z }.average().toFloat())
  }
  val extent = remember(level) {
    val d = levelMembers.flatten().map { hypot(hypot(it.x - middle[0], it.y - middle[1]), it.z - middle[2]) }.sorted()
    (d.getOrNull((d.size * 0.98f).toInt()) ?: 0.5f).coerceAtLeast(0.05f)
  }
  // start: outside the cloud, looking straight at it
  if (viewModel.camX.isNaN()) {
    viewModel.yaw = 0f
    viewModel.pitch = 0f
    viewModel.moveTo(middle[0], middle[1], middle[2] - extent * 2.6f)
  }
  val members = remember(focused, viewModel.items) { focused?.allMembers()?.mapNotNull { m -> viewModel.items[m.id]?.let { m to it } } ?: emptyList() }
  val loader = rememberGalleryImageLoader()
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()
  var flight by remember { mutableStateOf<Job?>(null) }

  // joystick: x = sideways, y = forward/back, both -1..1; moves at a fifth of the cloud per second
  var stick by remember { mutableStateOf(Offset.Zero) }
  LaunchedEffect(stick != Offset.Zero) {
    if (stick == Offset.Zero) return@LaunchedEffect
    flight?.cancel()
    var last = 0L
    while (true) {
      withFrameNanos { now ->
        if (last != 0L) {
          val dt = (now - last) / 1e9f
          val c = Camera(viewModel.camX, viewModel.camY, viewModel.camZ, viewModel.yaw, viewModel.pitch, 1f, 0f, 0f)
          val v = extent * 0.45f * dt
          val fwd = -stick.y * v
          val side = stick.x * v
          viewModel.moveTo(viewModel.camX + c.fx * fwd + c.rx * side, viewModel.camY + c.fy * fwd, viewModel.camZ + c.fz * fwd + c.rz * side)
        }
        last = now
      }
    }
  }

  /** Flies in front of a bubble and turns to look at it. */
  fun flyTo(b: Bubble) {
    flight?.cancel()
    flight = scope.launch {
      val from = floatArrayOf(viewModel.camX, viewModel.camY, viewModel.camZ, viewModel.yaw, viewModel.pitch)
      // stand back so the bubble fills the middle of the view, coming from where we are
      var dx = viewModel.camX - b.x
      var dy = viewModel.camY - b.y
      var dz = viewModel.camZ - b.z
      val len = hypot(hypot(dx, dy), dz).coerceAtLeast(1e-4f)
      val back = b.r.coerceAtLeast(0.05f) * 3f
      dx = dx / len * back; dy = dy / len * back; dz = dz / len * back
      val toYaw = atan2(-dx, -dz)
      val toPitch = atan2(-dy, hypot(dx, dz))
      // the short way round
      var dYaw = toYaw - from[3]
      while (dYaw > Math.PI) dYaw -= (2 * Math.PI).toFloat()
      while (dYaw < -Math.PI) dYaw += (2 * Math.PI).toFloat()
      val anim = Animatable(0f)
      anim.animateTo(1f, tween(900)) {
        val t = value
        viewModel.moveTo(from[0] + (b.x + dx - from[0]) * t, from[1] + (b.y + dy - from[1]) * t, from[2] + (b.z + dz - from[2]) * t)
        viewModel.yaw = from[3] + dYaw * t
        viewModel.pitch = from[4] + (toPitch - from[4]) * t
      }
    }
  }
  val select = { index: Int ->
    viewModel.focusOn(index)
    if (index in level.indices) flyTo(level[index])
  }

  BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0E1216))) {
    val w = constraints.maxWidth.toFloat()
    val h = constraints.maxHeight.toFloat()
    val oy = h * (if (focused == null) 0.45f else if (viewModel.sheetOpen) 0.22f else 0.38f)
    val cam = Camera(viewModel.camX, viewModel.camY, viewModel.camZ, viewModel.yaw, viewModel.pitch, minOf(w, h) * 0.9f, w / 2, oy)
    val pad = with(density) { 8.dp.toPx() }
    val far = extent * 4f
    // every item as a star: screen position and distance; null when behind the camera
    val stars = levelMembers.map { ms -> ms.map { m -> cam.project(m.x, m.y, m.z) } }
    val shapes =
      level.mapIndexed { i, b ->
        val c = cam.project(b.x, b.y, b.z)
        val centre = c?.let { Offset(it[0], it[1]) }
        val core = cores[i].mapNotNull { stars[i][it] }
        // an outline only when (almost) the whole core is in front of us
        val outline =
          if (centre == null || core.size < cores[i].size * 0.9f) emptyList()
          else hull(core.map { Offset(it[0], it[1]) }).map { p -> val d = p - centre; val len = d.getDistance().coerceAtLeast(1f); p + d * (pad / len) }
        Shape(i, outline, centre, c?.get(2) ?: Float.MAX_VALUE)
      }
    val fog = { dist: Float -> (1.15f - dist / far).coerceIn(0.15f, 1f) }
    // the nearest stars show their picture
    val pictures =
      levelMembers.flatMapIndexed { i, ms -> ms.indices.mapNotNull { j -> stars[i][j]?.takeIf { it[2] < PICTURE_DIST && it[0] in 0f..w && it[1] in 0f..h }?.let { Triple(ms[j], it, i) } } }
        .sortedBy { it.second[2] }
        .take(MAX_PICTURES)
    val highlight = members.getOrNull(viewModel.page)?.first
    val currentShapes by rememberUpdatedState(shapes)

    Canvas(
      Modifier.fillMaxSize()
        // drag anywhere looks around
        .pointerInput(Unit) {
          detectDragGestures(onDragStart = { flight?.cancel() }) { change, drag ->
            change.consume()
            viewModel.turn(drag.x * TURN_PER_PX, -drag.y * TURN_PER_PX)
          }
        }
        .pointerInput(level) {
          detectTapGestures(
            // a tap selects the nearest bubble under the finger; a tap on empty space clears it
            onTap = { t ->
              val hit = currentShapes.filter { it.outline.size >= 3 && inside(it.outline, t) }.minByOrNull { it.depth }?.index ?: -1
              select(hit)
            },
            onDoubleTap = { focused?.let { if (it.children.isNotEmpty()) viewModel.enter(it) } },
          )
        }
    ) {
      // far things first, so near ones paint over them
      for (sh in shapes.sortedByDescending { it.depth }) {
        val b = level[sh.index]
        val selected = sh.index == focus
        if (sh.outline.size >= 3) {
          val path = Path().apply {
            moveTo(sh.outline[0].x, sh.outline[0].y)
            for (p in sh.outline.drop(1)) lineTo(p.x, p.y)
            close()
          }
          val f = fog(sh.depth)
          // only the selected bubble has colour; the rest are faint outlines, so the structure shows
          if (selected) drawPath(path, pointColor(b.x, b.y, b.z, "JPEG").copy(alpha = 0.32f))
          else drawPath(path, Color.White.copy(alpha = 0.035f * f))
          drawPath(path, Color.White.copy(alpha = if (selected) 0.85f else 0.16f * f), style = Stroke(width = (if (selected) 1.8.dp else 1.dp).toPx(), join = StrokeJoin.Round))
        }
        val base = (if (selected) 1.6.dp else 1.1.dp).toPx()
        for (q in stars[sh.index]) {
          if (q == null) continue
          // stars grow as you come closer
          val r = (base * (0.35f / q[2]).coerceIn(0.6f, 6f))
          drawCircle(Color.White.copy(alpha = (if (selected) 0.95f else if (focus == null) 0.7f else 0.4f) * fog(q[2])), r, Offset(q[0], q[1]))
        }
      }
      highlight?.let { m ->
        cam.project(m.x, m.y, m.z)?.let { q ->
          drawCircle(Color(0xFFFFD54F), 4.dp.toPx(), Offset(q[0], q[1]))
          drawCircle(Color(0xFFFFD54F), 9.dp.toPx(), Offset(q[0], q[1]), style = Stroke(2.dp.toPx()))
        }
      }
    }

    // stars close by turn into their pictures; tapping one opens it
    for ((m, q, bi) in pictures.asReversed()) {
      val item = viewModel.items[m.id] ?: continue
      val sizeDp = (with(density) { (minOf(w, h) * 0.018f / q[2]).toDp() }).coerceIn(28.dp, 140.dp)
      AsyncImage(
        model = MediaThumb(item.uri, 256),
        imageLoader = loader,
        contentDescription = item.name,
        contentScale = ContentScale.Crop,
        modifier =
          Modifier.offset { IntOffset((q[0] - sizeDp.toPx() / 2).roundToInt(), (q[1] - sizeDp.toPx() / 2).roundToInt()) }
            .size(sizeDp)
            .clip(RoundedCornerShape(8.dp))
            .clickable {
              val list = levelMembers[bi].mapNotNull { viewModel.items[it.id] }
              onOpen(list, list.indexOf(item).coerceAtLeast(0))
            },
      )
    }

    // every other bubble: its best picture in the middle, the name below; big ones first, a
    // badge that would cover another waits until moving frees its spot
    val badgeW = with(density) { 88.dp.toPx() }
    val badgeH = with(density) { 64.dp.toPx() }
    val placed = ArrayList<Offset>()
    val badges = shapes
      .filter { it.index != focus && it.centre != null && it.centre.x in 0f..w && it.centre.y in 0f..h && it.depth > PICTURE_DIST }
      .sortedByDescending { level[it.index].size }
      .filter { sh -> placed.none { abs(it.x - sh.centre!!.x) < badgeW && abs(it.y - sh.centre.y) < badgeH }.also { if (it) placed += sh.centre!! } }
    for (sh in badges.sortedByDescending { it.depth }) {
      val b = level[sh.index]
      val f = fog(sh.depth)
      Column(
        Modifier.offset { IntOffset((sh.centre!!.x - badgeW / 2).roundToInt(), (sh.centre.y - with(density) { 22.dp.toPx() }).roundToInt()) }
          .width(88.dp)
          .clickable { select(sh.index) },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        b.previews.firstNotNullOfOrNull { viewModel.items[it] }?.let { item ->
          AsyncImage(
            model = MediaThumb(item.uri, 192),
            imageLoader = loader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.4f + 0.6f * f,
            modifier = Modifier.size(40.dp).clip(CircleShape),
          )
        }
        Text(
          b.title,
          color = Color.White.copy(alpha = 0.4f + 0.6f * f),
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 2.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
      }
    }

    if (focused != null && focus != null) {
      val sh = shapes[focus]
      if (sh.centre != null && sh.outline.isNotEmpty()) {
        // the selected bubble: a line up from its top to a balloon with its best pictures
        val top = sh.outline.minOf { it.y }
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
      }
    }

    // back to the start, outside the cloud
    FilledTonalIconButton(
      onClick = { flight?.cancel(); viewModel.focusOn(-1); viewModel.resetCamera() },
      modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
    ) {
      Icon(Icons.Filled.ZoomOutMap, "Zurück zum Start")
    }

    // the joystick, above the sheet when one is open
    Joystick(
      onMove = { stick = it },
      modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = if (focused != null) (if (viewModel.sheetOpen) 0.dp else 210.dp) else 36.dp),
      visible = !(focused != null && viewModel.sheetOpen),
    )

    if (focused != null) {
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

/** A thumb stick: drag inside the ring, the knob follows; reports -1..1 per axis, zero on release. */
@Composable
private fun Joystick(onMove: (Offset) -> Unit, modifier: Modifier, visible: Boolean) {
  if (!visible) return
  val density = LocalDensity.current
  val radius = with(density) { 52.dp.toPx() }
  var knob by remember { mutableStateOf(Offset.Zero) }
  Box(
    modifier
      .size(120.dp)
      .background(Color.White.copy(alpha = 0.06f), CircleShape)
      .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
      .semantics { contentDescription = "Steuerung: ziehen zum Fliegen" }
      .pointerInput(Unit) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        detectDragGestures(
          onDragStart = { p -> knob = clampTo(p - centre, radius); onMove(knob / radius) },
          onDragEnd = { knob = Offset.Zero; onMove(Offset.Zero) },
          onDragCancel = { knob = Offset.Zero; onMove(Offset.Zero) },
        ) { change, drag ->
          change.consume()
          knob = clampTo(knob + drag, radius)
          onMove(knob / radius)
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    Box(Modifier.offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }.size(44.dp).background(Color.White.copy(alpha = 0.35f), CircleShape))
  }
}

private fun clampTo(v: Offset, max: Float): Offset {
  val len = v.getDistance()
  return if (len <= max) v else v * (max / len)
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
