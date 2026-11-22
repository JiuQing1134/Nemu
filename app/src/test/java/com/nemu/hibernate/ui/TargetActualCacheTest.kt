package com.nemu.hibernate.ui

import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.Target
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetActualCacheTest {
    private fun target(packageName: String) = Target(
        packageName = packageName,
        label = packageName,
        risk = RiskLevel.GREEN
    )

    private fun state(suspended: Boolean) =
        TargetActualUiState(suspended = suspended, packageEnabled = true)

    @Test
    fun nonForcedReadReusesFreshSnapshot() = runBlocking {
        var now = 1_000L
        var reads = 0
        val cache = TargetActualCache(
            ttlMillis = 10_000L,
            clock = { now }
        )
        val targets = listOf(target("a"), target("b"))
        val first = cache.read(targets, force = false) {
            reads++
            mapOf("a" to state(true), "b" to state(false))
        }
        now += 5_000L
        val second = cache.read(targets, force = false) {
            reads++
            error("fresh cache must not read")
        }
        assertEquals(1, reads)
        assertEquals(first, second)
    }

    @Test
    fun forcedReadAlwaysProbesEvenWhenCacheIsFresh() = runBlocking {
        var now = 1_000L
        var reads = 0
        val cache = TargetActualCache(ttlMillis = 10_000L, clock = { now })
        val targets = listOf(target("a"))
        cache.read(targets, force = true) {
            reads++
            mapOf("a" to state(true))
        }
        now += 100L
        val second = cache.read(targets, force = true) {
            reads++
            mapOf("a" to state(false))
        }
        assertEquals(2, reads)
        assertEquals(false, second.getValue("a").suspended)
    }

    @Test
    fun ttlExpiryForcesReadEvenWithoutForce() = runBlocking {
        var now = 1_000L
        var reads = 0
        val cache = TargetActualCache(
            ttlMillis = 10_000L,
            clock = { now }
        )
        val targets = listOf(target("a"))
        cache.read(targets, force = false) {
            reads++
            mapOf("a" to state(true))
        }
        now += 10_001L
        cache.read(targets, force = false) {
            reads++
            mapOf("a" to state(false))
        }
        assertEquals(2, reads)
    }

    @Test
    fun partialRefreshMergesIntoFullSnapshot() = runBlocking {
        var now = 1_000L
        val cache = TargetActualCache(clock = { now })
        val all = listOf(target("a"), target("b"), target("c"))
        cache.read(all, force = true) {
            all.associate { it.packageName to state(false) }
        }
        now += 2_000L
        val merged = cache.read(listOf(target("b")), force = true) {
            mapOf("b" to state(true))
        }
        assertEquals(setOf("a", "b", "c"), merged.keys)
        assertEquals(true, merged.getValue("b").suspended)
        assertEquals(false, merged.getValue("a").suspended)
    }

    @Test
    fun failedShardIsRemovedInsteadOfKeptStale() = runBlocking {
        var now = 1_000L
        val cache = TargetActualCache(clock = { now })
        val targets = listOf(target("a"), target("b"))
        cache.read(targets, force = true) {
            mapOf("a" to state(true), "b" to state(true))
        }
        now += 2_000L
        val after = cache.read(targets, force = true) {
            mapOf("a" to state(false))
        }
        assertEquals(setOf("a"), after.keys)
        assertTrue("b" !in after)
    }

    @Test
    fun newlyRequestedPackageIsReadEvenWhenCacheIsFresh() = runBlocking {
        var now = 1_000L
        var reads = 0
        val cache = TargetActualCache(
            ttlMillis = 10_000L,
            clock = { now }
        )
        cache.read(listOf(target("a")), force = false) {
            reads++
            mapOf("a" to state(false))
        }
        now += 100L
        val after = cache.read(listOf(target("new")), force = false) {
            reads++
            mapOf("new" to state(true))
        }
        assertEquals(2, reads)
        assertEquals(setOf("a", "new"), after.keys)
    }

    @Test
    fun peekNeverExecutesARead() = runBlocking {
        val cache = TargetActualCache()
        assertTrue(cache.peek().isEmpty())
        val targets = listOf(target("a"))
        cache.read(targets, force = true) { mapOf("a" to state(true)) }
        assertEquals(true, cache.peek().getValue("a").suspended)
    }
}
