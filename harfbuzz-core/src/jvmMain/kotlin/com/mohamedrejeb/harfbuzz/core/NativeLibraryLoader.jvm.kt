package com.mohamedrejeb.harfbuzz.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Desktop JVM native library loader.
 *
 * Tries [System.loadLibrary] first, otherwise resolves the running platform's
 * `(os, arch)` to a resource path under
 * `native/<os-arch>/lib<name>.<ext>` inside the consumer's classpath, then
 * extracts the file to `${java.io.tmpdir}/kotlin-harfbuzz/<sha256>/<filename>`
 * and calls [System.load] on the absolute path. Idempotent across processes
 * via the SHA-256-keyed cache directory - multiple JVMs picking up the same
 * artifact share the extracted file.
 */
internal actual fun loadNativeLib(name: String) {
    // Tmpdir-extracted dylibs fail macOS signature checks in signed builds,
    // so prefer java.library.path.
    try {
        System.loadLibrary(name)
        return
    } catch (_: UnsatisfiedLinkError) {
    }

    val (osArch, ext) = detectPlatform()
    val libFilename = platformLibFilename(name, ext)
    val resourcePath = "native/$osArch/$libFilename"

    val classloader = NativeLibraryLoaderJvm::class.java.classLoader
        ?: throw HbNativeLoadException(
            "No classloader available to locate '$resourcePath'.",
        )

    // Fast path: when the build emitted a sibling `<libname>.sha256` file
    // (writeNativeJvmSha task in build.gradle.kts), the cache directory key
    // is already known without reading the binary. On a warm tmpdir cache
    // this collapses to one classpath read + one File.exists() + System.load
    // - saving the 5-15 MB readAllBytes + ~30-80 ms SHA-256 compute the slow
    // path pays on every JVM cold start.
    tryLoadFromKnownSha(classloader, resourcePath, libFilename)?.let { absolutePath ->
        System.load(absolutePath)
        return
    }

    val stream = classloader.getResourceAsStream(resourcePath)
        ?: throw HbNativeLoadException(
            "Native library resource not found: '$resourcePath'. " +
                "Either the platform isn't supported or the resources weren't bundled into the jar.",
        )

    val bytes = stream.use { it.readAllBytes() }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = digest.joinToString("") { "%02x".format(it) }

    val cacheDir = File(System.getProperty("java.io.tmpdir"), "kotlin-harfbuzz/$hex")
    if (!cacheDir.exists()) cacheDir.mkdirs()
    val target = File(cacheDir, libFilename)

    if (!target.exists() || target.length() != bytes.size.toLong()) {
        // Atomic move from a sibling temp file so concurrent loaders never see
        // a half-written .dylib/.so/.dll.
        val tmp = File.createTempFile(name, ".part", cacheDir)
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: IOException) {
                // Some filesystems don't support ATOMIC_MOVE; fall back to a
                // non-atomic move.
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    System.load(target.absolutePath)
}

/**
 * Look for a build-emitted `<resourcePath>.sha256` file on the classpath
 * and, if present + valid, return the absolute path of the corresponding
 * cached binary in `${java.io.tmpdir}/kotlin-harfbuzz/<sha>/<filename>`
 * - but only if that cached file already exists. Returns `null` to defer
 * to the slow path when:
 *  - the .sha256 sibling isn't bundled (older builds, downstream repackagers),
 *  - the file content isn't a 64-char hex string (corrupted bundle),
 *  - the cached binary doesn't exist yet (first run on this machine).
 *
 * Errors are swallowed: any failure here just falls through to the slow
 * path, which has its own diagnostics.
 */
private fun tryLoadFromKnownSha(
    classloader: ClassLoader,
    resourcePath: String,
    libFilename: String,
): String? = runCatching {
    val shaResource = "$resourcePath.sha256"
    val sha = classloader.getResourceAsStream(shaResource)?.use {
        it.readBytes().decodeToString().trim()
    } ?: return@runCatching null
    if (!sha.matches(SHA_256_HEX)) return@runCatching null
    val target = File(
        File(System.getProperty("java.io.tmpdir"), "kotlin-harfbuzz/$sha"),
        libFilename,
    )
    if (target.exists()) target.absolutePath else null
}.getOrNull()

private val SHA_256_HEX = Regex("^[a-fA-F0-9]{64}$")

private object NativeLibraryLoaderJvm

private fun detectPlatform(): Pair<String, String> {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val osArch = System.getProperty("os.arch").orEmpty().lowercase()

    val os = when {
        osName.contains("mac") || osName.contains("darwin") -> "macos"
        osName.contains("windows") -> "windows"
        osName.contains("linux") -> "linux"
        else -> throw HbNativeLoadException("Unsupported OS: '$osName'")
    }

    val arch = when {
        osArch == "aarch64" || osArch == "arm64" -> "aarch64"
        osArch == "x86_64" || osArch == "amd64" -> "x86_64"
        osArch == "x86" || osArch == "i386" -> "x86"
        else -> throw HbNativeLoadException("Unsupported arch on $os: '$osArch'")
    }

    val ext = when (os) {
        "macos" -> "dylib"
        "linux" -> "so"
        "windows" -> "dll"
        else -> error("unreachable")
    }

    return "$os-$arch" to ext
}

private fun platformLibFilename(name: String, ext: String): String =
    when (ext) {
        "dll" -> "$name.dll"
        else -> "lib$name.$ext"
    }
