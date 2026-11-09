package com.google.ai.edge.gallery.mediagallery.bubbles

import java.util.Random
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleEngineTest {
  private val dim = 48

  /** [groups] clusters of [per] items around random centres, all sharing one strong direction like real embeddings. */
  private fun items(groups: Int, per: Int, seed: Long = 1, prefix: String = "g"): Pair<Items, IntArray> {
    val r = Random(seed)
    val shared = FloatArray(dim) { if (it == 0) 3f else 0f }
    val centres = Array(groups) { FloatArray(dim) { r.nextGaussian().toFloat() } }
    val n = groups * per
    val q = ByteArray(n * dim)
    val k = FloatArray(n)
    val truth = IntArray(n)
    for (i in 0 until n) {
      val g = i % groups
      truth[i] = g
      val v = FloatArray(dim) { shared[it] + centres[g][it] + 0.25f * r.nextGaussian().toFloat() }
      var m = 0f
      for (x in v) m = maxOf(m, kotlin.math.abs(x))
      for (j in 0 until dim) q[i * dim + j] = (v[j] / m * 127).roundToInt().toByte()
      k[i] = Library.invNorm(q, i * dim, dim)
    }
    return Items(Library(dim, q, k), Array(n) { "$prefix$it" }, BooleanArray(n) { it % 7 == 0 }) to truth
  }

  @Test
  fun rngMatchesTheServer() {
    val r = Rng(7)
    assertEquals(0.011704753153026104, r.next(), 1e-12)
    assertEquals(0.06195825757458806, r.next(), 1e-12)
    assertEquals(0.97690763277933, r.next(), 1e-12)
  }

  @Test
  fun findsTheGroupsDespiteTheSharedDirection() {
    // 400 items: the server's rule gives four bubbles
    val (items, truth) = items(groups = 4, per = 100)
    val snap = BubbleEngine.compute(items, labels = null, names = emptyList())
    assertEquals(4, snap.bubbles.size)
    val index = items.ids.withIndex().associate { (i, id) -> id to truth[i] }
    for (b in snap.bubbles) {
      val ids = b.allMembers().map { it.id }
      assertEquals(100, ids.size)
      assertEquals(1, ids.map { index.getValue(it) }.distinct().size)
      assertEquals(4, b.previews.size)
    }
    // the layout spreads the groups out
    val pos = snap.bubbles.map { floatArrayOf(it.x, it.y, it.z) }
    for (a in pos.indices) for (c in a + 1 until pos.size) {
      val d = sqrt((0..2).sumOf { ((pos[a][it] - pos[c][it]) * (pos[a][it] - pos[c][it])).toDouble() })
      assertTrue("bubbles $a and $c overlap", d > 0.05)
    }
  }

  @Test
  fun newPhotosJoinTheirBubbleAndKeepKeys() {
    val (items, truth) = items(groups = 4, per = 100)
    val snap = BubbleEngine.compute(items, labels = null, names = emptyList())
    // more photos from the same scenes
    val (more, moreTruth) = items(groups = 4, per = 5, seed = 1, prefix = "new")
    val owner = HashMap<Int, String>()
    for (b in snap.bubbles) for (m in b.allMembers()) owner[truth[m.id.drop(1).toInt()]] = b.key
    val removed = setOf("g0", "g1")
    val next = BubbleEngine.extend(snap, more, removed)
    assertEquals(snap.bubbles.map { it.key }.toSet(), next.bubbles.map { it.key }.toSet())
    for (b in next.bubbles) for (m in b.allMembers().filter { it.id.startsWith("new") }) {
      assertEquals(owner.getValue(moreTruth[m.id.drop(3).toInt()]), b.key)
    }
    val all = next.ids()
    assertTrue("g0" !in all && "g1" !in all)
    assertEquals(400 - 2 + 20, all.size)
  }

  @Test
  fun aSavedNameFindsItsBubbleAfterARegroup() {
    val (items, _) = items(groups = 4, per = 60)
    val first = BubbleEngine.compute(items, labels = null, names = emptyList())
    val b = first.bubbles[2]
    val names = listOf(SavedName(b.key, "Urlaub", b.level, b.centroid))
    val again = BubbleEngine.compute(items, labels = null, names = names)
    val named = again.bubbles.filter { it.name == "Urlaub" }
    assertEquals(1, named.size)
    assertEquals(b.allMembers().map { it.id }.toSet(), named[0].allMembers().map { it.id }.toSet())
  }

  @Test
  fun bigGroupsGetSubBubbles() {
    val (items, _) = items(groups = 6, per = 140)
    val snap = BubbleEngine.compute(items, labels = null, names = emptyList())
    assertTrue(snap.bubbles.any { it.children.size >= 2 })
    for (b in snap.bubbles.filter { it.children.isNotEmpty() }) assertTrue(b.members.isEmpty())
  }

  @Test
  fun tooFewItemsGiveNoBubbles() {
    val (items, _) = items(groups = 1, per = 10)
    assertTrue(BubbleEngine.compute(items, labels = null, names = emptyList()).bubbles.isEmpty())
  }
}
