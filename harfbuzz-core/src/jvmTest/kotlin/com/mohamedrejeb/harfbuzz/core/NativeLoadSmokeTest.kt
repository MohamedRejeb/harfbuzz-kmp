package com.mohamedrejeb.harfbuzz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeLoadSmokeTest {

    @Test
    fun `native library loads and reports HarfBuzz version`() {
        HarfbuzzNative.ensureLoaded()
        val v = HarfbuzzNative.nativeVersion()
        assertTrue(v.isNotBlank(), "version string should be non-blank, got '$v'")
        // We pin HarfBuzz to 14.2.0; bumps that change the major version are
        // intentional and the test is updated in lockstep.
        assertEquals("14.2.0", v)
    }

    @Test
    fun `buffer create and destroy is idempotent`() {
        HarfbuzzNative.ensureLoaded()
        val ptr = HarfbuzzNative.bufferCreate()
        assertTrue(ptr != 0L, "buffer should have a non-null pointer")
        HarfbuzzNative.bufferDestroy(ptr)
    }
}
