package com.mohamedrejeb.harfbuzz.core

/**
 * Tiny test-time helper that loads font bytes from `src/jvmTest/resources/`.
 * Used by tests that need a known-good font without depending on whatever
 * happens to be installed on the host (CI runners are bare-bones).
 */
internal object TestFonts {
    private const val ROBOTO_REGULAR_PATH = "/fonts/Roboto-Regular.ttf"
    private const val NOTO_NASKH_ARABIC_MEDIUM_PATH = "/fonts/NotoNaskhArabic-Medium.ttf"

    fun robotoRegular(): ByteArray = readResource(ROBOTO_REGULAR_PATH)

    /** Arabic coverage for joining-form and authored-font-run tests. */
    fun notoNaskhArabicMedium(): ByteArray = readResource(NOTO_NASKH_ARABIC_MEDIUM_PATH)

    private fun readResource(path: String): ByteArray {
        val stream = TestFonts::class.java.getResourceAsStream(path)
            ?: error("Missing test font on classpath: $path")
        return stream.use { it.readBytes() }
    }
}
