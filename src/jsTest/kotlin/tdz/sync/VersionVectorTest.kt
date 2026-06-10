package tdz.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionVectorTest {
    @Test fun equalVectors() =
        assertEquals(Causal.EQUAL, compareVv(mapOf("a" to 2L), mapOf("a" to 2L)))

    @Test fun emptyVectorsAreEqual() =
        assertEquals(Causal.EQUAL, compareVv(emptyMap(), emptyMap()))

    @Test fun localDominates() =
        assertEquals(Causal.LOCAL_DOMINATES, compareVv(mapOf("a" to 3L, "b" to 1L), mapOf("a" to 2L, "b" to 1L)))

    @Test fun remoteDominates() =
        assertEquals(Causal.REMOTE_DOMINATES, compareVv(mapOf("a" to 1L), mapOf("a" to 1L, "b" to 1L)))

    @Test fun concurrentWhenEachHasMore() =
        assertEquals(Causal.CONCURRENT, compareVv(mapOf("a" to 2L, "b" to 1L), mapOf("a" to 1L, "b" to 2L)))

    @Test fun nonEmptyVsEmpty() {
        assertEquals(Causal.LOCAL_DOMINATES, compareVv(mapOf("a" to 1L), emptyMap()))
        assertEquals(Causal.REMOTE_DOMINATES, compareVv(emptyMap(), mapOf("a" to 1L)))
    }

    @Test fun pointwiseMaxDominatesBothConcurrentParents() {
        val a = mapOf("a" to 2L, "b" to 1L); val b = mapOf("a" to 1L, "b" to 2L)
        val m = pointwiseMax(a, b)
        assertEquals(mapOf("a" to 2L, "b" to 2L), m)
        assertEquals(Causal.LOCAL_DOMINATES, compareVv(m, a))
        assertEquals(Causal.LOCAL_DOMINATES, compareVv(m, b))
    }

    @Test fun bumpIncrementsOwnComponent() {
        assertEquals(mapOf("a" to 3L), bump(mapOf("a" to 2L), "a"))
        assertEquals(mapOf("a" to 2L, "b" to 1L), bump(mapOf("a" to 2L), "b"))
    }

    // (vv json round-trips are covered by SyncStateDocTest / ManifestVvTest via kotlinx.serialization)
}
