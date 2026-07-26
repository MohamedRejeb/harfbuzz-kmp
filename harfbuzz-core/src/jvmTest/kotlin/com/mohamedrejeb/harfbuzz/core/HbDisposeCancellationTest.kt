package com.mohamedrejeb.harfbuzz.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/**
 * Pins the disposal contract for shaping work: operations that reach the
 * dispatcher after [HbFont.close] must fail with [CancellationException]
 * (a lifecycle race the caller treats as cancellation), not
 * [IllegalStateException], which crashes apps that dispose fonts while
 * layout is in flight - seen in production as "hb object disposed".
 */
class HbDisposeCancellationTest {

    @Test
    fun `shapeParagraph after close fails with CancellationException`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        font.close()
        assertFailsWith<CancellationException> {
            font.shapeParagraph("Hello", sizePx = 24f)
        }
        face.close()
    }
}
