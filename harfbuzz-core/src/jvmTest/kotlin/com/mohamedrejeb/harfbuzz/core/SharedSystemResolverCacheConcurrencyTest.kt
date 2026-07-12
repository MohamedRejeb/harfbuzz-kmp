package com.mohamedrejeb.harfbuzz.core

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression test for the process-wide resolver cache's thread-safety.
 *
 * Production stacks shape from the harfbuzz-bg dispatcher AND straight
 * from the main thread (seen in Crashlytics ANR traces entering
 * [sharedSystemResolverFor] via measureLines on main), so cache lookups
 * race. The old plain-HashMap cache could structurally corrupt under
 * concurrent put; the copy-on-write map must never throw and must
 * converge to a single resolver instance per key.
 */
class SharedSystemResolverCacheConcurrencyTest {

    @AfterTest
    fun tearDown() {
        clearSharedSystemResolverCacheForTest()
    }

    @Test
    fun `concurrent lookups never throw and converge to one resolver per key`() {
        val threads = 16
        val iterations = 200
        val keys = (0 until 8).map { i ->
            SystemFallback.Match(style = FontStyleHint(weight = 100 * (i + 1)))
        }
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        try {
            val tasks = (0 until threads).map { t ->
                pool.submit {
                    start.await()
                    try {
                        repeat(iterations) { i ->
                            val match = keys[(t + i) % keys.size]
                            assertNotNull(sharedSystemResolverFor(match))
                        }
                    } catch (e: Throwable) {
                        failures.add(e)
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertTrue(failures.isEmpty(), "concurrent lookups threw: ${failures.firstOrNull()}")
        // Steady state after the race: same key resolves to same instance.
        for (match in keys) {
            assertSame(
                sharedSystemResolverFor(match),
                sharedSystemResolverFor(match),
                "cache did not converge for $match",
            )
        }
    }
}
