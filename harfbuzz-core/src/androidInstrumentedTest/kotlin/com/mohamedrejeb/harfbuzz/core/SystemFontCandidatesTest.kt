package com.mohamedrejeb.harfbuzz.core

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the process-wide `SystemFonts.getAvailableFonts()` snapshot:
 * the walk (seconds of `Font.hashCode` buffer hashing on API 29/30
 * low-end devices - the measureLines ANR) must run at most once per
 * process, no matter how many resolver cache keys are built.
 */
@RunWith(AndroidJUnit4::class)
class SystemFontCandidatesTest {

    @Test
    fun snapshotIsWalkedOnceAndReused() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val first = SystemFontCandidates.get()
        val second = SystemFontCandidates.get()
        assertSame(first, second, "SystemFonts walk must be memoised process-wide")
        assertTrue(first.isNotEmpty(), "device should expose at least one system font")
    }

    @Test
    fun warmUpIsIdempotentAndSafe() {
        SystemFontCandidates.warmUp()
        SystemFontCandidates.warmUp()
    }
}
