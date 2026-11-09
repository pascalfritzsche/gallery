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

import com.google.ai.edge.gallery.morgenschiss.Bubble
import com.google.ai.edge.gallery.morgenschiss.BubbleMember
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

// Port of the server's services/mediasearch/bubbles.mjs, plus what the server never needed: a
// stored result that new photos are added to without regrouping everything.

private const val SAMPLE = 3000
const val MIN_SIZE = 15
private const val SUB_MIN_PARENT = 60
// centred space: halves of one group measured 0.91, distinct groups below 0.72
private const val MERGE_ABOVE = 0.8f
// a saved name sticks only to a bubble this close to the one it was given to
private const val NAME_MATCH = 0.95f

/** int8 with one scale, the server's packVector format. */
@Serializable
data class Packed(val q: String, val s: Float) {
  fun unpack(): FloatArray {
    val b = Base64.getDecoder().decode(q)
    return FloatArray(b.size) { b[it] / 127f * s }
  }

  companion object {
    fun of(v: FloatArray): Packed {
      var m = 0f
      for (x in v) m = max(m, abs(x))
      val scale = if (m > 0) m else 1f
      return Packed(Base64.getEncoder().encodeToString(ByteArray(v.size) { (v[it] / scale * 127).roundToInt().toByte() }), scale)
    }
  }
}

@Serializable data class StoredMember(val id: String, val score: Float, val x: Float, val y: Float, val z: Float, val video: Boolean = false)

@Serializable
data class StoredBubble(
  val key: String,
  val level: String,
  val name: String? = null,
  /** Key of the saved name this bubble carries. */
  val sid: String? = null,
  val tags: List<String> = emptyList(),
  val x: Float = 0f,
  val y: Float = 0f,
  val z: Float = 0f,
  val r: Float = 0.02f,
  val previews: List<String> = emptyList(),
  /** Empty for a parent: its members are those of its children. */
  val members: List<StoredMember> = emptyList(),
  val children: List<StoredBubble> = emptyList(),
  /** Mean of the raw member vectors: names, tags, albums and scores use it. */
  val centroid: Packed,
  /** Centre in the centred space the grouping ran in: new photos are assigned with it. */
  val centered: Packed,
) {
  fun allMembers(): List<StoredMember> = if (children.isEmpty()) members else children.flatMap { it.allMembers() }

  fun toApi(): Bubble {
    val all = allMembers()
    return Bubble(
      key = key,
      name = name,
      suggested = tags.firstOrNull(),
      tags = tags,
      size = all.size,
      videos = all.count { it.video },
      x = x, y = y, z = z, r = r,
      previews = previews,
      members = members.map { BubbleMember(it.id, it.score.toDouble(), it.x, it.y, it.z) },
      children = children.map { it.toApi() },
    )
  }
}

@Serializable data class StoredBasis(val landmarks: List<Packed>, val rowMean: List<Double>, val pinv: List<List<Double>?>, val scale: Double) {
  fun basis() = MdsBasis(landmarks.map { it.unpack() }, rowMean.toDoubleArray(), pinv.map { it?.toDoubleArray() })
}

@Serializable
data class BubbleSnapshot(
  val version: Int = 1,
  /** Items when the grouping last ran; how far the library has moved since decides a regroup. */
  val builtFrom: Int,
  /** Photos added or removed since then. */
  val drift: Int = 0,
  val mean: Packed? = null,
  val basis: StoredBasis? = null,
  /** False when there were no label vectors yet: the tags still have to come. */
  val labeled: Boolean,
  val bubbles: List<StoredBubble>,
) {
  fun ids(): Set<String> = bubbles.flatMap { it.allMembers() }.mapTo(HashSet()) { it.id }

  fun toApi(): List<Bubble> = bubbles.map { it.toApi() }
}

@Serializable data class SavedName(val sid: String, val name: String, val level: String, val centroid: Packed)

/** Items to group: unit vectors as int8 rows, ids (fingerprints) and whether each is a video. */
class Items(val lib: Library, val ids: Array<String>, val video: BooleanArray)

object BubbleEngine {
  fun keyOf(level: String, ids: List<String>): String {
    val h = MessageDigest.getInstance("SHA-1").digest((level + ":" + ids.take(12).joinToString(",")).toByteArray())
    return "b_" + h.joinToString("") { "%02x".format(it) }.take(12)
  }

  fun tagsFor(centroid: FloatArray, labels: List<Pair<String, FloatArray>>?): List<String> =
    labels.orEmpty().map { (name, v) -> name to dot(centroid, v) }.sortedByDescending { it.second }.take(5).map { it.first }

  private fun group(space: CenteredSpace, idx: IntArray, k: Int, minSize: Int, seed: Int, cancelled: () -> Boolean): List<Group> {
    val n = idx.size
    val fitOn =
      if (n > SAMPLE) {
        val rand = Rng(seed)
        val pool = IntArray(n) { it }
        IntArray(SAMPLE) { i ->
          val j = i + (rand.next() * (n - i)).toInt()
          val t = pool[i]; pool[i] = pool[j]; pool[j] = t
          idx[pool[i]]
        }
      } else idx
    val centroids = kmeans(space, fitOn, k, seed = seed, cancelled = cancelled)
    val assign = IntArray(n) { p ->
      var bi = 0
      var bs = Float.NEGATIVE_INFINITY
      for (c in centroids.indices) {
        val s = space.dot(idx[p], centroids[c])
        if (s > bs) { bs = s; bi = c }
      }
      bi
    }
    return consolidate(space, idx, assign, centroids, minSize, MERGE_ABOVE)
  }

  private fun rawCentroid(lib: Library, members: IntArray): FloatArray {
    val sum = DoubleArray(lib.dim)
    val raw = RawSpace(lib)
    for (i in members) raw.add(i, sum)
    return normalize(sum)
  }

  /** The whole grouping. Embedding vectors share one strong direction, so clustering runs on them minus the library mean. */
  fun compute(
    items: Items,
    labels: List<Pair<String, FloatArray>>?,
    names: List<SavedName>,
    cancelled: () -> Boolean = { false },
  ): BubbleSnapshot {
    val lib = items.lib
    if (lib.n < MIN_SIZE) return BubbleSnapshot(builtFrom = lib.n, labeled = labels != null, bubbles = emptyList())
    val mean = lib.mean()
    val space = CenteredSpace(lib, mean)
    val all = IntArray(lib.n) { it }
    val tops = group(space, all, chooseK(lib.n), max(MIN_SIZE, (lib.n * 0.004).roundToInt()), 7, cancelled)
    val subsOf = tops.map { t ->
      if (t.members.size < SUB_MIN_PARENT) emptyList()
      else group(space, t.members, (t.members.size / 150.0).roundToInt().coerceIn(2, 6), 10, 11, cancelled).takeIf { it.size > 1 }.orEmpty()
    }
    // stored as int8 like everything else, and placed with exactly what is stored, so new photos land consistently
    val rawOf = HashMap<Group, FloatArray>()
    for (g in tops + subsOf.flatten()) rawOf[g] = Packed.of(rawCentroid(lib, g.members)).unpack()
    val landmarks = (tops + subsOf.flatten()).map { rawOf.getValue(it) }
    val basis = MdsBasis.of(landmarks)
    val raw = Array(lib.n) { i -> basis.placeDots(FloatArray(landmarks.size) { lib.dot(i, landmarks[it]) }) }
    val norms = raw.map { hypot(hypot(it[0], it[1]), it[2]) }.sorted()
    val scale = 0.85 / (norms[(norms.size * 0.98).toInt().coerceAtMost(norms.lastIndex)].takeIf { it > 0 } ?: 1.0)
    val coords = Array(lib.n) { i -> FloatArray(3) { (raw[i][it] * scale).toFloat() } }

    fun build(g: Group, level: String): StoredBubble {
      val c = rawOf.getValue(g)
      val members = g.members.map { gi -> StoredMember(items.ids[gi], lib.dot(gi, c), coords[gi][0], coords[gi][1], coords[gi][2], items.video[gi]) }
      return shaped(StoredBubble(key = "", level = level, tags = tagsFor(c, labels), members = members, centroid = Packed.of(c), centered = Packed.of(g.centroid)))
        .let { it.copy(key = keyOf(level, it.members.map { m -> m.id })) }
    }

    val bubbles = tops.mapIndexed { i, t ->
      val children = subsOf[i].map { build(it, "sub") }
      val top = build(t, "top")
      // same items as the children, so position and previews stay the top bubble's own
      if (children.isEmpty()) top else top.copy(members = emptyList(), children = children)
    }
    return BubbleSnapshot(
      builtFrom = lib.n,
      mean = Packed.of(mean),
      basis = StoredBasis(landmarks.map { Packed.of(it) }, basis.rowMean.toList(), basis.pinv.map { it?.toList() }, scale),
      labeled = labels != null,
      bubbles = assignNames(bubbles, names),
    )
  }

  /**
   * Position, radius and previews from the members: the bubble is where its items are, the
   * radius covers 90 % of them. A parent takes its children's members.
   */
  fun shaped(b: StoredBubble): StoredBubble {
    val children = b.children.map { shaped(it) }.filter { it.allMembers().isNotEmpty() }
    val members = (if (children.isEmpty()) b.members else children.flatMap { it.allMembers() }).sortedByDescending { it.score }
    if (members.isEmpty()) return b.copy(members = emptyList(), children = emptyList(), previews = emptyList())
    val px = members.map { it.x }.average().toFloat()
    val py = members.map { it.y }.average().toFloat()
    val pz = members.map { it.z }.average().toFloat()
    val dists = members.map { hypot(hypot(it.x - px, it.y - py), it.z - pz) }.sorted()
    val r = max(0.02f, dists[(dists.size * 0.9).toInt().coerceAtMost(dists.lastIndex)])
    return b.copy(
      x = px, y = py, z = pz, r = r,
      previews = members.take(4).map { it.id },
      members = if (children.isEmpty()) members else emptyList(),
      children = children,
    )
  }

  /**
   * New photos join the bubble they are closest to (in the same space the grouping used) at the
   * spot the stored layout gives them; removed ones leave. Centres and keys stay, so names and
   * the camera keep their place.
   */
  fun extend(snap: BubbleSnapshot, added: Items, removed: Set<String>): BubbleSnapshot {
    val mean = snap.mean?.unpack()
    val stored = snap.basis
    if (mean == null || stored == null || snap.bubbles.isEmpty()) return snap
    val basis = stored.basis()
    val tops = snap.bubbles.map { it to it.centered.unpack() }
    val leafCentres = HashMap<String, Pair<FloatArray, FloatArray>>()
    for (t in snap.bubbles) for (l in t.children.ifEmpty { listOf(t) }) leafCentres[l.key] = l.centered.unpack() to l.centroid.unpack()
    val joins = HashMap<String, MutableList<StoredMember>>()
    for (i in 0 until added.lib.n) {
      if (added.lib.k[i] == 0f) continue
      val u = added.lib.unit(i)
      val c = normalize(FloatArray(u.size) { u[it] - mean[it] })
      val top = tops.maxBy { dot(c, it.second) }.first
      val leaf = top.children.ifEmpty { listOf(top) }.maxBy { dot(c, leafCentres.getValue(it.key).first) }
      val p = basis.place(u)
      joins.getOrPut(leaf.key) { ArrayList() } +=
        StoredMember(added.ids[i], dot(u, leafCentres.getValue(leaf.key).second), (p[0] * stored.scale).toFloat(), (p[1] * stored.scale).toFloat(), (p[2] * stored.scale).toFloat(), added.video[i])
    }
    fun update(b: StoredBubble): StoredBubble =
      if (b.children.isNotEmpty()) b.copy(children = b.children.map { update(it) })
      else b.copy(members = b.members.filter { it.id !in removed } + joins[b.key].orEmpty())
    val bubbles = snap.bubbles.map { shaped(update(it)) }.filter { it.allMembers().isNotEmpty() }
    return snap.copy(bubbles = bubbles)
  }

  /** Tags for a result computed before the label vectors were there. */
  fun relabel(snap: BubbleSnapshot, labels: List<Pair<String, FloatArray>>): BubbleSnapshot {
    fun tag(b: StoredBubble): StoredBubble = b.copy(tags = tagsFor(b.centroid.unpack(), labels), children = b.children.map { tag(it) })
    return snap.copy(labeled = true, bubbles = snap.bubbles.map { tag(it) })
  }

  /** Best pairs first, each saved name and each bubble once, only within the same level. */
  fun assignNames(bubbles: List<StoredBubble>, saved: List<SavedName>): List<StoredBubble> {
    val all = bubbles + bubbles.flatMap { it.children }
    val vs = saved.map { it to normalize(it.centroid.unpack()) }
    val pairs = ArrayList<Triple<StoredBubble, SavedName, Float>>()
    for (b in all) {
      val c = b.centroid.unpack()
      for ((s, v) in vs) {
        if (s.level != b.level) continue
        val sc = dot(c, v)
        if (sc > NAME_MATCH) pairs += Triple(b, s, sc)
      }
    }
    val chosen = HashMap<String, SavedName>()
    val used = HashSet<String>()
    for ((b, s) in pairs.sortedByDescending { it.third }) {
      if (b.key in chosen || s.sid in used) continue
      chosen[b.key] = s
      used += s.sid
    }
    fun apply(b: StoredBubble): StoredBubble {
      val s = chosen[b.key]
      return b.copy(name = s?.name, sid = s?.sid, children = b.children.map { apply(it) })
    }
    return bubbles.map { apply(it) }
  }

  fun find(bubbles: List<StoredBubble>, key: String): StoredBubble? {
    for (b in bubbles) {
      if (b.key == key) return b
      b.children.firstOrNull { it.key == key }?.let { return it }
    }
    return null
  }

  fun withName(bubbles: List<StoredBubble>, key: String, name: String?, sid: String?): List<StoredBubble> =
    bubbles.map { b -> if (b.key == key) b.copy(name = name, sid = sid) else b.copy(children = withName(b.children, key, name, sid)) }
}
