package com.mohamedrejeb.harfbuzz.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Item K - verifies the identity-IntArray cache reuses one array per
 * length and evicts at the configured cap. The cache is process-wide,
 * so each test resets it via [IdentityIntArrayCache.clearForTest] in
 * setUp / tearDown to keep cases isolated.
 */
class IdentityIntArrayTest {

    @BeforeTest
    fun reset() {
        IdentityIntArrayCache.clearForTest()
    }

    @AfterTest
    fun cleanUp() {
        IdentityIntArrayCache.clearForTest()
    }

    @Test
    fun `same length returns the same instance across calls`() {
        val a = identityIntArray(80)
        val b = identityIntArray(80)
        assertSame(a, b, "second call for length=80 must return the cached array")
        assertEquals(80, a.size)
        for (i in 0 until 80) assertEquals(i, a[i])
    }

    @Test
    fun `length zero returns the canonical empty array`() {
        val a = identityIntArray(0)
        val b = identityIntArray(0)
        assertSame(a, b)
        assertEquals(0, a.size)
    }

    @Test
    fun `negative length collapses to the empty array`() {
        val a = identityIntArray(-1)
        assertEquals(0, a.size)
    }

    @Test
    fun `cache bounded at 64 entries and FIFO-evicts older lengths`() {
        // Fill the cache with 64 distinct lengths.
        for (n in 1..64) identityIntArray(n)
        assertEquals(64, IdentityIntArrayCache.sizeForTest())

        // 65th distinct length triggers eviction of the oldest entry (length=1).
        identityIntArray(65)
        assertEquals(64, IdentityIntArrayCache.sizeForTest())

        // Re-requesting length=1 succeeds (returns a fresh array since the
        // old one was evicted, but the contract - identity values - holds).
        val resurrected = identityIntArray(1)
        assertEquals(1, resurrected.size)
        assertEquals(0, resurrected[0])
    }

    @Test
    fun `cache size doesn't grow on hits`() {
        identityIntArray(10)
        identityIntArray(10)
        identityIntArray(10)
        assertTrue(IdentityIntArrayCache.sizeForTest() == 1)
    }
}
