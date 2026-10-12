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

package com.google.ai.edge.gallery.morgenschiss

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.ai.edge.gallery.mediagallery.LocalSearch
import com.google.ai.edge.gallery.mediagallery.MediaItem
import com.google.ai.edge.gallery.mediagallery.MediaRepository
import com.google.ai.edge.gallery.mediagallery.PHONE_VISION_TOKENS
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

private const val TAG = "PhoneIndexWorker"
private const val CHANNEL_ID = "phone_index"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PhoneIndexEntryPoint {
  fun api(): MediaSearchApi

  fun idStore(): MediaIdStore

  fun mediaRepository(): MediaRepository

  fun vectors(): VectorStore

  fun localSearch(): LocalSearch
}

/**
 * The phone indexes its own media while charging: newest first (the Mac sync takes the oldest),
 * photos, videos and their scenes. Vectors go into [VectorStore] right away, so they are
 * searchable offline, and to morgenschiss when it is reachable (bubbles and albums live there).
 */
class PhoneIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private val deps = EntryPointAccessors.fromApplication(context, PhoneIndexEntryPoint::class.java)
  private val notifications = context.getSystemService(NotificationManager::class.java)

  override suspend fun doWork(): Result {
    // the periodic and the immediate run would compute the same files twice
    if (!running.tryLock()) return Result.success()
    try {
      return index()
    } finally {
      running.unlock()
    }
  }

  private suspend fun index(): Result {
    val search = deps.localSearch()
    if (!search.isModelReady()) return Result.success()
    val api = deps.api()
    val store = deps.idStore()
    val vectors = deps.vectors()
    val repo = deps.mediaRepository()
    repo.reload()
    val items = repo.library.value.items
    val byId = items.associateBy { it.id }

    // new files have no fingerprint yet (the sync may not have run): same rule as the sync
    val files = items.map { LocalFile(it.id, it.size, it.dateModifiedSec, it.folder, it.isVideo) }
    for (f in SyncPlanner.plan(files, store.all(), null).needFingerprint) {
      if (isStopped) return Result.retry()
      val item = byId.getValue(f.mediaId)
      runCatching { Fingerprint.ofUri(applicationContext.contentResolver, item.uri, item.size) }.getOrNull()?.let {
        store.putFingerprint(f.mediaId, f.size, f.dateModifiedSec, it)
      }
    }

    sendPending(api, store, vectors, byId)

    var done = 0
    val total = nextBatch(store.all(), vectors.fingerprints(), byId, Int.MAX_VALUE).size
    while (!isStopped) {
      // read again every round: the Mac sync works on the same list from the other end
      val have = vectors.fingerprints()
      // a video with its scenes takes minutes: it makes a round of its own, so a stop loses little
      val next = nextBatch(store.all(), have, byId, 16)
      if (next.isEmpty()) break
      val todo = next.let { b -> if (b.first().second.isVideo) b.take(1) else b.takeWhile { !it.second.isVideo } }
      report("Auf dem Handy indexieren", done, maxOf(total, done + todo.size))
      val rows = ArrayList<VectorStore.Row>()
      val broken = ArrayList<Long>()
      for ((row, item) in todo) {
        if (isStopped) break
        val r = embedItem(search, row.fingerprint, item)
        if (r.isEmpty()) broken += row.mediaId else rows += r
      }
      if (rows.isEmpty() && !search.isModelReady()) break
      // unreadable files are skipped from now on, like the server's failed ones
      store.markFailed(broken)
      vectors.put(rows)
      done += todo.size
      sendPending(api, store, vectors, byId)
    }
    report("Fertig", done, done)
    return Result.success(workDataOf(KEY_DONE to done))
  }

  /** Files morgenschiss does not have and the phone has not done yet, newest first; one per fingerprint. */
  private fun nextBatch(rows: Map<Long, MediaIdRow>, have: Set<String>, byId: Map<Long, MediaItem>, n: Int): List<Pair<MediaIdRow, MediaItem>> {
    val seen = HashSet<String>()
    return rows.values
      .asSequence()
      .filter { it.serverFolder == null && !it.serverFailed && it.fingerprint !in have }
      .mapNotNull { row -> byId[row.mediaId]?.let { row to it } }
      .sortedByDescending { it.second.takenAt }
      .filter { seen.add(it.first.fingerprint) }
      .take(n)
      .toList()
  }

  private suspend fun embedItem(search: LocalSearch, fp: String, item: MediaItem): List<VectorStore.Row> {
    val resolver = applicationContext.contentResolver
    val frames =
      runCatching {
          if (item.isVideo) FrameEncoder.framesFor(resolver, item).mapNotNull(::decode)
          else listOf(
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { d, info, _ ->
              val scale = minOf(1f, IMAGE_SIDE.toFloat() / maxOf(info.size.width, info.size.height))
              d.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
              d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
          )
        }
        .getOrElse { emptyList() }
    val v = try { search.embedFrames(frames) } finally { frames.forEach { it.recycle() } } ?: return emptyList()
    val (q, s) = VectorStore.pack(v)
    val out = arrayListOf(VectorStore.Row(fp, fp, null, if (item.isVideo) "video" else "image", VectorStore.SOURCE_PHONE, PHONE_VISION_TOKENS, q, s))
    if (item.isVideo) {
      val scenes = runCatching { FrameEncoder.sceneFrames(resolver, item) }.getOrElse { emptyList() }
      for ((t, b64) in scenes) {
        if (isStopped) break
        val frame = decode(b64) ?: continue
        val sv = try { search.embedFrames(listOf(frame)) } finally { frame.recycle() } ?: continue
        val (sq, ss) = VectorStore.pack(sv)
        out += VectorStore.Row("$fp#${Math.round(t * 1000)}", fp, t, "scene", VectorStore.SOURCE_PHONE, PHONE_VISION_TOKENS, sq, ss)
      }
    }
    return out
  }

  /**
   * Sends what the phone computed. A file counts as indexed (and the Mac sync skips it) once
   * morgenschiss stored it; until then its vectors stay marked and go with the next run.
   */
  private suspend fun sendPending(api: MediaSearchApi, store: MediaIdStore, vectors: VectorStore, byId: Map<Long, MediaItem>) {
    if (!api.client.isLoggedIn) return
    val rows = store.all()
    val idsByFp = rows.values.groupBy({ it.fingerprint }, { it.mediaId })
    while (!isStopped) {
      // ordered by key: a video ("fp") comes before its scenes ("fp#ms")
      val pending = vectors.pending(400)
      if (pending.isEmpty()) return
      val items =
        pending.map { r ->
          val item = idsByFp[r.fingerprint]?.firstNotNullOfOrNull { byId[it] }
          PutVector(
            id = r.fingerprint,
            kind = r.kind,
            tokens = r.tokens,
            q = VectorStore.encode(r.q),
            s = r.s,
            t = r.t,
            mime = item?.mime,
            size = item?.size,
            takenAt = item?.let { Instant.ofEpochMilli(it.takenAt).toString() },
            folder = item?.folder,
            name = item?.name,
          )
        }
      when (val res = api.putVectors(items)) {
        is ApiResult.Ok -> {
          // rejected ones are not sent again either (they would fail the same way)
          vectors.markSent(pending.map { it.key })
          val stored = res.value.stored.filter { it.t == null }.map { it.id }.toSet()
          val media = stored.flatMap { idsByFp[it].orEmpty() }.filter { byId.containsKey(it) }
          store.markIndexed(media) { byId.getValue(it).folder }
          // scenes came with the video, so the Mac sync must not send its own
          store.markScenes(media.filter { byId[it]?.isVideo == true }, MediaIdStore.SCENE_VERSION)
          if (res.value.failed.isNotEmpty()) Log.w(TAG, "${res.value.failed.size} vectors rejected: ${res.value.failed.take(3)}")
        }
        else -> {
          Log.i(TAG, "sending vectors stopped: $res")
          return
        }
      }
    }
  }

  private fun decode(b64: String): Bitmap? =
    runCatching { Base64.decode(b64, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()

  private suspend fun report(phase: String, done: Int, total: Int) {
    setProgress(workDataOf(KEY_PHASE to phase, KEY_DONE to done, KEY_TOTAL to total))
    try {
      setForeground(foregroundInfo(phase, done, total))
    } catch (e: Exception) {
      if (e is CancellationException) throw e
      Log.w(TAG, "no foreground service: ${e.message}")
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Auf dem Handy indexieren", 0, 0)

  private fun foregroundInfo(phase: String, done: Int, total: Int): ForegroundInfo {
    notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Suche auf dem Handy", NotificationManager.IMPORTANCE_LOW))
    val text = if (total > 0) "$phase: $done von $total" else phase
    val n =
      NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setContentTitle("Galerie-Suche")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_search)
        .setOngoing(true)
        .setProgress(total, done, total == 0)
        .build()
    return ForegroundInfo(1702, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  }

  companion object {
    private val running = Mutex()

    /** Same as the bench: the long side the measured timings were taken with. */
    private const val IMAGE_SIDE = 1024
    const val KEY_PHASE = "phase"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val UNIQUE_NOW = "phone_index_now"
    const val UNIQUE_PERIODIC = "phone_index_periodic"

    // indexing runs the GPU for a long time: only while charging
    private val constraints = Constraints.Builder().setRequiresCharging(true).build()

    fun runNow(context: Context) {
      WorkManager.getInstance(context)
        .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<PhoneIndexWorker>().setConstraints(constraints).build())
    }

    fun schedulePeriodic(context: Context) {
      WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
          UNIQUE_PERIODIC,
          ExistingPeriodicWorkPolicy.KEEP,
          PeriodicWorkRequestBuilder<PhoneIndexWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
    }
  }
}
