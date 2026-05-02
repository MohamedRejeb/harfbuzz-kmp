package com.mohamedrejeb.harfbuzz.compose

/**
 * Loads test font bytes from `src/jvmTest/resources/`. Mirrors the
 * `harfbuzz-core` jvmTest helper of the same name - kept module-local
 * so commonTest doesn't have to grow a Compose Resources dependency
 * just for fixture loading.
 */
internal object TestFonts {
    private const val ROBOTO_REGULAR_PATH = "/fonts/Roboto-Regular.ttf"

    fun robotoRegular(): ByteArray {
        val stream = TestFonts::class.java.getResourceAsStream(ROBOTO_REGULAR_PATH)
            ?: error("Missing test font on classpath: $ROBOTO_REGULAR_PATH")
        return stream.use { it.readBytes() }
    }
}
