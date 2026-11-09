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

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Port of the server's services/mediasearch/cluster.mjs: spherical k-means, the merge step and
// landmark MDS. Pure math on index lists, so the phone can group its library without the Mac.

/** Deterministic PRNG (mulberry32), same sequence as the server's. */
class Rng(seed: Int) {
  private var a = seed

  fun next(): Double {
    a += 0x6d2b79f5
    var t = a
    t = (t xor (t ushr 15)) * (t or 1)
    t = t xor (t + (t xor (t ushr 7)) * (t or 61))
    return ((t xor (t ushr 14)).toLong() and 0xffffffffL) / 4294967296.0
  }
}

fun dot(a: FloatArray, b: FloatArray): Float {
  var s = 0f
  for (i in a.indices) s += a[i] * b[i]
  return s
}

fun normalize(v: DoubleArray): FloatArray {
  var n = 0.0
  for (x in v) n += x * x
  n = sqrt(n).takeIf { it > 0 } ?: 1.0
  return FloatArray(v.size) { (v[it] / n).toFloat() }
}

fun normalize(v: FloatArray): FloatArray = normalize(DoubleArray(v.size) { v[it].toDouble() })

/**
 * The library as int8 rows (as stored in VectorStore); row i as a unit vector is q[i] * k[i].
 * 768 bytes per item instead of 3 KB floats keeps a large library in memory.
 */
class Library(val dim: Int, val q: ByteArray, val k: FloatArray) {
  val n get() = k.size

  fun dot(i: Int, c: FloatArray): Float {
    var s = 0f
    val off = i * dim
    for (j in 0 until dim) s += q[off + j] * c[j]
    return s * k[i]
  }

  fun unit(i: Int): FloatArray = FloatArray(dim) { q[i * dim + it] * k[i] }

  fun mean(): FloatArray {
    val m = DoubleArray(dim)
    for (i in 0 until n) {
      val off = i * dim
      val ki = k[i].toDouble()
      for (j in 0 until dim) m[j] += q[off + j] * ki
    }
    return FloatArray(dim) { (m[it] / max(1, n)).toFloat() }
  }

  companion object {
    /** 1 / length of an int8 row, 0 for an empty one. */
    fun invNorm(q: ByteArray, off: Int, dim: Int): Float {
      var s = 0L
      for (j in off until off + dim) s += q[j] * q[j]
      return if (s > 0) (1.0 / sqrt(s.toDouble())).toFloat() else 0f
    }
  }
}

/** The vectors k-means works on: plain unit rows, or rows minus a mean, renormalized. */
interface Space {
  val dim: Int

  fun dot(i: Int, c: FloatArray): Float

  /** sum += vector i */
  fun add(i: Int, sum: DoubleArray)

  fun copy(i: Int): FloatArray
}

class RawSpace(private val lib: Library) : Space {
  override val dim get() = lib.dim

  override fun dot(i: Int, c: FloatArray) = lib.dot(i, c)

  override fun add(i: Int, sum: DoubleArray) {
    val off = i * lib.dim
    val ki = lib.k[i].toDouble()
    for (j in 0 until lib.dim) sum[j] += lib.q[off + j] * ki
  }

  override fun copy(i: Int) = lib.unit(i)
}

/**
 * normalize(v - mean) without a second copy of the library: (v - m)·c = v·c - m·c, and the
 * length of v - m follows from v·m (v has unit length).
 */
class CenteredSpace(private val lib: Library, val mean: FloatArray) : Space {
  private val mm = dot(mean, mean)
  private val inv = FloatArray(lib.n) { i -> (1.0 / sqrt(max(1e-12, (1 - 2 * lib.dot(i, mean) + mm).toDouble()))).toFloat() }

  override val dim get() = lib.dim

  override fun dot(i: Int, c: FloatArray) = (lib.dot(i, c) - dot(mean, c)) * inv[i]

  override fun add(i: Int, sum: DoubleArray) {
    val off = i * lib.dim
    val ki = lib.k[i].toDouble()
    val w = inv[i].toDouble()
    for (j in 0 until lib.dim) sum[j] += (lib.q[off + j] * ki - mean[j]) * w
  }

  override fun copy(i: Int): FloatArray {
    val u = lib.unit(i)
    return FloatArray(u.size) { (u[it] - mean[it]) * inv[i] }
  }

  /** The same transform for a vector outside the library (a new photo). */
  fun center(u: FloatArray): FloatArray = normalize(FloatArray(u.size) { u[it] - mean[it] })
}

class Group(val members: IntArray, val centroid: FloatArray)

/** Spherical k-means++ on the items [idx] of [space]; returns the centroids. */
fun kmeans(space: Space, idx: IntArray, kWanted: Int, iters: Int = 15, seed: Int = 7, cancelled: () -> Boolean = { false }): List<FloatArray> {
  val n = idx.size
  val k = max(1, min(kWanted, n))
  val rand = Rng(seed)
  val centroids = arrayListOf(space.copy(idx[(rand.next() * n).toInt()]))
  val best = DoubleArray(n) { Double.POSITIVE_INFINITY }
  while (centroids.size < k) {
    val c = centroids.last()
    var total = 0.0
    for (p in 0 until n) {
      val d = 1.0 - space.dot(idx[p], c)
      if (d < best[p]) best[p] = d
      val b = max(0.0, best[p])
      total += b * b
    }
    var pick = rand.next() * total
    var chosen = n - 1
    for (p in 0 until n) {
      val b = max(0.0, best[p])
      pick -= b * b
      if (pick <= 0) {
        chosen = p
        break
      }
    }
    centroids += space.copy(idx[chosen])
  }
  val assign = IntArray(n)
  for (it in 0 until iters) {
    if (cancelled()) break
    var moved = 0
    for (p in 0 until n) {
      var bi = 0
      var bs = Float.NEGATIVE_INFINITY
      for (c in 0 until k) {
        val s = space.dot(idx[p], centroids[c])
        if (s > bs) {
          bs = s
          bi = c
        }
      }
      if (assign[p] != bi || it == 0) moved++
      assign[p] = bi
    }
    val sums = Array(k) { DoubleArray(space.dim) }
    val counts = IntArray(k)
    for (p in 0 until n) {
      space.add(idx[p], sums[assign[p]])
      counts[assign[p]]++
    }
    for (c in 0 until k) if (counts[c] > 0) centroids[c] = normalize(sums[c])
    if (it > 0 && moved == 0) break
  }
  return centroids
}

/**
 * Groups below [minSize] and groups whose centres are closer than [mergeAbove] join their nearest
 * neighbour. [idx] are the items, [assign] their cluster. Largest group first.
 */
fun consolidate(space: Space, idx: IntArray, assign: IntArray, centroids: List<FloatArray>, minSize: Int, mergeAbove: Float): List<Group> {
  class G(val members: ArrayList<Int>, val sum: DoubleArray, var centroid: FloatArray)
  var groups: MutableList<G> = centroids.map { G(ArrayList(), DoubleArray(space.dim), it) }.toMutableList()
  for (p in idx.indices) {
    val g = groups[assign[p]]
    g.members += idx[p]
    space.add(idx[p], g.sum)
  }
  groups = groups.filter { it.members.isNotEmpty() }.toMutableList()
  for (g in groups) g.centroid = normalize(g.sum)
  while (groups.size > 1) {
    var victim = -1
    for (g in groups.indices) {
      if (groups[g].members.size < minSize && (victim < 0 || groups[g].members.size < groups[victim].members.size)) victim = g
    }
    var target = -1
    if (victim < 0) {
      var bs = mergeAbove
      for (a in groups.indices) for (b in a + 1 until groups.size) {
        val s = dot(groups[a].centroid, groups[b].centroid)
        if (s > bs) {
          bs = s
          victim = if (groups[a].members.size < groups[b].members.size) a else b
          target = if (victim == a) b else a
        }
      }
      if (victim < 0) break
    } else {
      var bs = Float.NEGATIVE_INFINITY
      for (g in groups.indices) {
        if (g == victim) continue
        val s = dot(groups[g].centroid, groups[victim].centroid)
        if (s > bs) {
          bs = s
          target = g
        }
      }
    }
    if (target < 0) break
    val t = groups[target]
    val v = groups[victim]
    t.members.addAll(v.members)
    for (j in t.sum.indices) t.sum[j] += v.sum[j]
    t.centroid = normalize(t.sum)
    groups.removeAt(victim)
  }
  return groups.map { Group(it.members.toIntArray(), it.centroid) }.sortedByDescending { it.members.size }
}

/** About one bubble per 400 items, 4 to 24. */
fun chooseK(n: Int, per: Int = 400, lo: Int = 4, hi: Int = 24): Int = max(min(lo, n), min(hi, (n.toDouble() / per).roundToInt()))

/**
 * Landmark MDS (de Silva and Tenenbaum): classical MDS of the landmarks; any vector is then
 * placed from its squared distances to them. Kept so new photos land in the same space.
 */
class MdsBasis(val landmarks: List<FloatArray>, val rowMean: DoubleArray, val pinv: List<DoubleArray?>) {
  /** Unscaled 3D position of a unit vector, given its dot with each landmark. */
  fun placeDots(dots: FloatArray): DoubleArray {
    val m = landmarks.size
    if (m < 2) return DoubleArray(3)
    val d = DoubleArray(m) { j -> max(0.0, 2.0 * (1 - dots[j])) - rowMean[j] }
    return DoubleArray(3) { c ->
      val row = pinv.getOrNull(c) ?: return@DoubleArray 0.0
      var s = 0.0
      for (j in 0 until m) s += row[j] * d[j]
      -0.5 * s
    }
  }

  fun place(u: FloatArray): DoubleArray = placeDots(FloatArray(landmarks.size) { dot(u, landmarks[it]) })

  companion object {
    fun of(landmarks: List<FloatArray>): MdsBasis {
      val m = landmarks.size
      if (m < 2) return MdsBasis(landmarks, DoubleArray(m), listOf(null, null, null))
      val d2 = Array(m) { a -> DoubleArray(m) { b -> max(0.0, 2.0 * (1 - dot(landmarks[a], landmarks[b]))) } }
      val rowMean = DoubleArray(m) { a -> d2[a].sum() / m }
      val all = rowMean.sum() / m
      val bm = Array(m) { a -> DoubleArray(m) { b -> -0.5 * (d2[a][b] - rowMean[a] - rowMean[b] + all) } }
      val comps = ArrayList<Pair<DoubleArray, Double>>()
      for (k in 0 until 3) {
        var v = DoubleArray(m) { sin(it * (k + 2) + 1.0) }
        var lambda = 0.0
        repeat(200) {
          val next = DoubleArray(m) { r -> var s = 0.0; for (j in 0 until m) s += bm[r][j] * v[j]; s }
          for ((cv, _) in comps) {
            var p = 0.0
            for (j in 0 until m) p += next[j] * cv[j]
            for (j in 0 until m) next[j] -= p * cv[j]
          }
          var norm = 0.0
          for (x in next) norm += x * x
          norm = sqrt(norm).takeIf { it > 0 } ?: 1.0
          lambda = norm
          v = DoubleArray(m) { next[it] / norm }
        }
        comps += v to lambda
      }
      val pinv = comps.map { (v, l) -> if (l > 1e-9) DoubleArray(m) { v[it] / sqrt(l) } else null }
      return MdsBasis(landmarks, rowMean, pinv)
    }
  }
}
