package com.mohamedrejeb.harfbuzz.core

/**
 * Locates and loads `libharfbuzz_jni.{so,dylib,dll}` for the running JVM.
 * Android and Desktop JVM diverge here:
 *
 *   - Android: AAR bundles `.so` files under `jni/<abi>/`; the platform
 *     linker handles them via `System.loadLibrary`.
 *   - Desktop JVM: jar resources hold one binary per platform under
 *     `native/<os-arch>/lib<name>.<ext>`. We extract to a temp dir on first
 *     use, verify a SHA-256 checksum (idempotent across processes), and
 *     `System.load` the absolute path.
 */
internal object NativeLibraryLoader {
    fun load(name: String) {
        loadNativeLib(name)
    }
}

internal expect fun loadNativeLib(name: String)
