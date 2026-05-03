package com.mohamedrejeb.harfbuzz.compose

/**
 * Loads test font bytes from `src/jvmTest/resources/`. Mirrors the
 * `harfbuzz-core` jvmTest helper of the same name - kept module-local
 * so commonTest doesn't have to grow a Compose Resources dependency
 * just for fixture loading.
 */
internal object TestFonts {
    private const val ROBOTO_REGULAR_PATH = "/fonts/Roboto-Regular.ttf"
    private const val NOTO_NASKH_ARABIC_MEDIUM_PATH = "/fonts/NotoNaskhArabic-Medium.ttf"

    fun robotoRegular(): ByteArray = loadResource(ROBOTO_REGULAR_PATH)

    fun notoNaskhArabicMedium(): ByteArray = loadResource(NOTO_NASKH_ARABIC_MEDIUM_PATH)

    private fun loadResource(path: String): ByteArray {
        val stream = TestFonts::class.java.getResourceAsStream(path)
            ?: error("Missing test font on classpath: $path")
        return stream.use { it.readBytes() }
    }
}
