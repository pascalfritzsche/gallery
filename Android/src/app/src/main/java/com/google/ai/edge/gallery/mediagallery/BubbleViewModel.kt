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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.mediagallery.bubbles.BubbleRepository
import com.google.ai.edge.gallery.mediagallery.bubbles.BubbleSnapshot
import com.google.ai.edge.gallery.morgenschiss.Bubble
import com.google.ai.edge.gallery.morgenschiss.MediaIdStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bubble view: which level is shown (top or inside a bubble), which bubble has the focus and
 * which item the preview shows. Kept here so opening a photo and coming back changes nothing.
 */
@HiltViewModel
class BubbleViewModel
@Inject
constructor(
  private val bubbles: BubbleRepository,
  private val idStore: MediaIdStore,
  private val repository: MediaRepository,
  private val albumRepo: AlbumRepository,
) :
  ViewModel() {
  var state by mutableStateOf<Loadable<List<Bubble>>>(Loadable.Idle)
    private set

  /** fingerprint -> local item (first copy). */
  var items by mutableStateOf<Map<String, MediaItem>>(emptyMap())
    private set

  /** Keys of the bubbles entered, outermost first. */
  var path by mutableStateOf<List<String>>(emptyList())
    private set

  /** Selected bubble of the level; -1 = none, the whole level in view. */
  var focus by mutableIntStateOf(-1)
  var sheetOpen by mutableStateOf(false)
  /** Index of the previewed member of the focused bubble. */
  var page by mutableIntStateOf(0)

  // the first-person camera: a position in the scene and where it looks; kept here like the
  // focus, so opening a photo and coming back puts you where you were. NaN = not placed yet.
  var camX by mutableFloatStateOf(Float.NaN)
  var camY by mutableFloatStateOf(0f)
  var camZ by mutableFloatStateOf(0f)
  var yaw by mutableFloatStateOf(0f)
  var pitch by mutableFloatStateOf(0f)

  fun turn(dYaw: Float, dPitch: Float) {
    yaw += dYaw
    // past straight up or down the picture flips
    pitch = (pitch + dPitch).coerceIn(-1.45f, 1.45f)
  }

  fun moveTo(x: Float, y: Float, z: Float) {
    camX = x
    camY = y
    camZ = z
  }

  /** Back to the start: outside the cloud, looking at it. */
  fun resetCamera() {
    camX = Float.NaN
  }

  /** True while the stored bubbles are brought up to date in the background. */
  var updating by mutableStateOf(false)
    private set

  /** Stored bubbles at once, then new photos added (or everything regrouped on [regroup]). */
  fun load(regroup: Boolean = false) {
    if (updating) return
    updating = true
    viewModelScope.launch {
      try {
        loadItems()
        if (state !is Loadable.Done) bubbles.cached()?.let { show(it) } ?: run { state = Loadable.Loading }
        show(bubbles.refresh(regroup))
      } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
      } catch (t: Throwable) {
        android.util.Log.e("BubbleViewModel", "grouping failed", t)
        if (state !is Loadable.Done) state = Loadable.Error("Die Bubbles ließen sich nicht berechnen.")
      } finally {
        updating = false
      }
    }
  }

  private suspend fun loadItems() {
    items = withContext(Dispatchers.IO) {
      val lib = repository.library.value
      idStore.all().values.mapNotNull { row -> lib.item(row.mediaId)?.let { row.fingerprint to it } }.toMap()
    }
  }

  /** Keeps the level and focus when the bubbles there still exist (keys stay when photos are only added). */
  private fun show(snap: BubbleSnapshot) {
    val next = snap.toApi()
    val focusKey = level().getOrNull(focus)?.key
    state = Loadable.Done(next)
    var list = next
    for (key in path) list = list.firstOrNull { it.key == key }?.children ?: run { path = emptyList(); focus = -1; page = 0; camX = Float.NaN; return }
    focus = list.indexOfFirst { it.key == focusKey }
    if (focus < 0) page = 0
  }

  /** Bubbles of the current level. */
  fun level(): List<Bubble> {
    var list = (state as? Loadable.Done)?.value ?: return emptyList()
    for (key in path) list = list.firstOrNull { it.key == key }?.children ?: return emptyList()
    return list
  }

  fun parent(): Bubble? {
    var list = (state as? Loadable.Done)?.value ?: return null
    var found: Bubble? = null
    for (key in path) {
      found = list.firstOrNull { it.key == key } ?: return null
      list = found.children
    }
    return found
  }

  /** -1 clears the selection. */
  fun focusOn(index: Int) {
    if (index == focus) return
    focus = index
    page = 0
    if (index < 0) sheetOpen = false
  }

  /** Back: clears the selection, else one level up; false when there is nothing left to undo. */
  fun back(): Boolean {
    if (focus >= 0) {
      focusOn(-1)
      return true
    }
    return up()
  }

  fun enter(bubble: Bubble) {
    if (bubble.children.isEmpty()) return
    path = path + bubble.key
    camX = Float.NaN
    focus = -1
    page = 0
  }

  /** Back one level; false when already at the top. */
  fun up(): Boolean {
    val key = path.lastOrNull() ?: return false
    path = path.dropLast(1)
    camX = Float.NaN
    focus = level().indexOfFirst { it.key == key }.coerceAtLeast(0)
    page = 0
    return true
  }

  /** Shown after creating an album (success or error). */
  var message by mutableStateOf<String?>(null)

  /**
   * Creates the album. For a folder album returns the local files that are its members (the
   * server's best 90 %, each file only in its best album) and the folder they belong in.
   */
  suspend fun createAlbum(bubble: Bubble, name: String, folder: Boolean): Pair<List<MediaItem>, String>? {
    val core = bubbles.albumCore(bubble.key)
    if (core == null) {
      message = "Die Bubble ist nicht mehr da, bitte neu laden."
      return null
    }
    val (album, error) = albumRepo.create(bubble.key, core, name.trim(), folder)
    message = error ?: if (folder) "Ordner \"${album!!.name}\" angelegt." else "Album \"${album!!.name}\" angelegt."
    if (album == null || !folder) return null
    val fps = albumRepo.members(album.id) ?: return null
    return fps.mapNotNull { items[it] } to MediaActions.newFolderPath(album.name)
  }

  fun rename(bubble: Bubble, name: String) {
    viewModelScope.launch { bubbles.rename(bubble.key, name)?.let { show(it) } }
  }
}

/** Members of a bubble; a parent's members are those of its children, best match first. */
fun Bubble.allMembers(): List<com.google.ai.edge.gallery.morgenschiss.BubbleMember> =
  if (members.isNotEmpty() || children.isEmpty()) members else children.flatMap { it.allMembers() }.sortedByDescending { it.score }
