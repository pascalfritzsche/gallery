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

package com.google.ai.edge.gallery.mediagallery.bubbles

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.mediagallery.LocalSearch
import com.google.ai.edge.gallery.morgenschiss.VectorStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "BubbleRepository"
// regroup once this share of the library came or went since the last grouping
private const val REGROUP_SHARE = 0.2
private const val MAX_NAME = 60
private const val KEEP = 0.9
private const val MIN_THRESHOLD = 0.3f

/** Centre and score threshold of a bubble, what the server stores for an album. */
data class AlbumCore(val centroid: Packed, val threshold: Float)

@Serializable private data class StoredLabels(val names: List<String>, val vectors: List<Packed>)

/**
 * Bubbles computed on the phone and kept in a file: shown at once, new photos are added to them,
 * and they are only regrouped from scratch when the library changed a lot or on request.
 */
@Singleton
class BubbleRepository
@Inject
constructor(
  @ApplicationContext context: Context,
  private val vectors: VectorStore,
  private val localSearch: LocalSearch,
) {
  private val dir = File(context.filesDir, "bubbles").apply { mkdirs() }
  private val snapshotFile = File(dir, "snapshot.json")
  private val namesFile = File(dir, "names.json")
  private val labelsFile = File(dir, "labels.json")
  private val json = Json { ignoreUnknownKeys = true }
  private val lock = Mutex()

  @Volatile private var snapshot: BubbleSnapshot? = null

  /** The stored result, or null before the first grouping. */
  suspend fun cached(): BubbleSnapshot? =
    snapshot
      ?: withContext(Dispatchers.IO) {
        runCatching { if (snapshotFile.isFile) json.decodeFromString<BubbleSnapshot>(snapshotFile.readText()) else null }
          .onFailure { Log.w(TAG, "stored bubbles unreadable", it) }
          .getOrNull()
          ?.takeIf { it.version == 1 }
          ?.also { snapshot = it }
      }

  /**
   * Brings the stored result up to date with the vectors on the phone. [regroup] forces a full
   * grouping; otherwise new photos are added and removed ones leave.
   */
  suspend fun refresh(regroup: Boolean = false): BubbleSnapshot =
    lock.withLock {
      val old = cached()
      val labels = labels()
      val result =
        withContext(Dispatchers.Default) {
          val current = vectors.overview()
          val ids = current.ids.toHashSet()
          val have = old?.ids().orEmpty()
          val added = ids - have
          val removed = have - ids
          val drift = (old?.drift ?: 0) + added.size + removed.size
          val full =
            regroup || old == null || old.basis == null && ids.size >= MIN_SIZE ||
              drift >= max(1.0, REGROUP_SHARE * old.builtFrom)
          when {
            full -> {
              val t0 = System.currentTimeMillis()
              BubbleEngine.compute(Items(Library(DIMS_Q, current.q, current.k), current.ids, current.video), labels, savedNames())
                .also { Log.i(TAG, "grouped ${ids.size} items in ${System.currentTimeMillis() - t0} ms") }
            }
            added.isEmpty() && removed.isEmpty() -> old!!
            else -> {
              val sub = vectors.overview(only = added)
              BubbleEngine.extend(old!!, Items(Library(DIMS_Q, sub.q, sub.k), sub.ids, sub.video), removed).copy(drift = drift)
            }
          }.let { if (!it.labeled && labels != null) BubbleEngine.relabel(it, labels) else it }
        }
      if (result !== old) save(result)
      result
    }

  /** Saves a name with the bubble's centre, so it finds the bubble again after a regroup. Empty removes it. */
  suspend fun rename(key: String, name: String): BubbleSnapshot? =
    lock.withLock {
      val snap = cached() ?: return null
      val b = BubbleEngine.find(snap.bubbles, key) ?: return null
      val clean = name.trim().replace(Regex("[\\p{Cc}\\p{Cf}]"), "").take(MAX_NAME)
      val names = savedNames().filter { it.sid != b.sid } + listOfNotNull(clean.takeIf { it.isNotEmpty() }?.let { SavedName(key, it, b.level, b.centroid) })
      withContext(Dispatchers.IO) { namesFile.writeText(json.encodeToString(names)) }
      val next = snap.copy(bubbles = BubbleEngine.withName(snap.bubbles, key, clean.ifEmpty { null }, if (clean.isEmpty()) null else key))
      save(next)
      next
    }

  /**
   * The album the server stores for a bubble: its centre and the score that keeps its best 90 %
   * (at least 0.3), the same rule the server applied to its own bubbles.
   */
  suspend fun albumCore(key: String): AlbumCore? =
    withContext(Dispatchers.Default) {
      val b = cached()?.let { BubbleEngine.find(it.bubbles, key) } ?: return@withContext null
      val c = b.centroid.unpack()
      val ov = vectors.overview(only = b.allMembers().mapTo(HashSet()) { it.id })
      val lib = Library(DIMS_Q, ov.q, ov.k)
      val scores = (0 until lib.n).map { lib.dot(it, c) }.sortedDescending()
      if (scores.isEmpty()) return@withContext null
      AlbumCore(b.centroid, max(MIN_THRESHOLD, scores[(KEEP * (scores.size - 1)).toInt()]))
    }

  fun clear() {
    snapshot = null
    snapshotFile.delete()
  }

  private suspend fun save(s: BubbleSnapshot) {
    snapshot = s
    withContext(Dispatchers.IO) {
      val tmp = File(dir, "snapshot.json.tmp")
      tmp.writeText(json.encodeToString(s))
      tmp.renameTo(snapshotFile)
    }
  }

  private suspend fun savedNames(): List<SavedName> =
    withContext(Dispatchers.IO) { runCatching { if (namesFile.isFile) json.decodeFromString<List<SavedName>>(namesFile.readText()) else null }.getOrNull().orEmpty() }

  /** Label vectors from the phone's model, embedded once; null while there is no model. */
  private suspend fun labels(): List<Pair<String, FloatArray>>? {
    val stored =
      withContext(Dispatchers.IO) { runCatching { if (labelsFile.isFile) json.decodeFromString<StoredLabels>(labelsFile.readText()) else null }.getOrNull() }
    if (stored != null && stored.names == BUBBLE_LABELS.map { it.first }) return stored.names.zip(stored.vectors.map { normalize(it.unpack()) })
    val v = localSearch.embedTexts(BUBBLE_LABELS.map { it.second }) ?: return null
    val names = BUBBLE_LABELS.map { it.first }
    withContext(Dispatchers.IO) { labelsFile.writeText(json.encodeToString(StoredLabels(names, v.map { Packed.of(it) }))) }
    return names.zip(v.map { normalize(it) })
  }
}

private const val DIMS_Q = com.google.ai.edge.gallery.morgenschiss.DIMS
