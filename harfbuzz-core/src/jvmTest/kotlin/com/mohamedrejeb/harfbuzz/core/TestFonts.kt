package com.mohamedrejeb.harfbuzz.core

/**
 * Tiny test-time helper that loads font bytes from `src/jvmTest/resources/`.
 * Used by tests that need a known-good font without depending on whatever
 * happens to be installed on the host (CI runners are bare-bones).
 */
internal object TestFonts {
    private const val ROBOTO_REGULAR_PATH = "/fonts/Roboto-Regular.ttf"

    fun robotoRegular(): ByteArray {
        val stream = TestFonts::class.java.getResourceAsStream(ROBOTO_REGULAR_PATH)
            ?: error("Missing test font on classpath: $ROBOTO_REGULAR_PATH")
        return stream.use { it.readBytes() }
    }
}
