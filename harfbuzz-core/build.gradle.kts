import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// ──── Desktop JVM native build ────────────────────────────────────────────
// Drive CMake from Gradle to produce libharfbuzz_jni for the host platform.
// CI matrix is responsible for cross-compiling for other (os, arch) tuples;
// local dev builds for the host only and bundles into the jar resources at
// `native/<os-arch>/`.
data class HostPlatform(val osArch: String, val libName: String)

fun detectHost(): HostPlatform {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osTag = when {
        os.contains("mac") || os.contains("darwin") -> "macos"
        os.contains("windows") -> "windows"
        os.contains("linux") -> "linux"
        else -> error("Unsupported host OS: $os")
    }
    val archTag = when {
        arch == "aarch64" || arch == "arm64" -> "aarch64"
        arch == "x86_64" || arch == "amd64" -> "x86_64"
        else -> error("Unsupported host arch: $arch")
    }
    val libName = when (osTag) {
        "macos" -> "libharfbuzz_jni.dylib"
        "linux" -> "libharfbuzz_jni.so"
        "windows" -> "harfbuzz_jni.dll"
        else -> error("unreachable")
    }
    return HostPlatform("$osTag-$archTag", libName)
}

val host = detectHost()
val nativeRoot = layout.projectDirectory.dir("../native")
val cmakeBuildDir = layout.buildDirectory.dir("cmake-jvm")
val resourcesNativeDir = layout.projectDirectory.dir("src/jvmMain/resources/native/${host.osArch}")

val configureNativeJvm by tasks.registering(Exec::class) {
    workingDir = nativeRoot.asFile
    val outDir = cmakeBuildDir.get().asFile
    outputs.file(File(outDir, "CMakeCache.txt"))
    inputs.file(nativeRoot.file("CMakeLists.txt"))
    inputs.file(nativeRoot.file("jni/harfbuzz_jni.cpp"))
    inputs.file(nativeRoot.file("jni/harfbuzz_jni.h"))
    val args = mutableListOf(
        "cmake",
        "-S", ".",
        "-B", outDir.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
    )
    if (host.osArch.startsWith("macos-")) {
        args += "-DCMAKE_OSX_ARCHITECTURES=" + if (host.osArch.endsWith("aarch64")) "arm64" else "x86_64"
    }
    commandLine(args)
}

val buildNativeJvm by tasks.registering(Exec::class) {
    dependsOn(configureNativeJvm)
    val outDir = cmakeBuildDir.get().asFile
    inputs.file(nativeRoot.file("CMakeLists.txt"))
    inputs.file(nativeRoot.file("jni/harfbuzz_jni.cpp"))
    inputs.file(nativeRoot.file("jni/harfbuzz_jni.h"))
    outputs.file(File(outDir, host.libName))
    commandLine(
        "cmake", "--build", outDir.absolutePath,
        "--target", "harfbuzz_jni",
        "--config", "Release",
        "--parallel",
    )
}

val copyNativeJvm by tasks.registering(Copy::class) {
    dependsOn(buildNativeJvm)
    from(cmakeBuildDir.map { it.file(host.libName) })
    into(resourcesNativeDir)
}

// Emit a sibling <libname>.sha256 file alongside the bundled binary so
// the runtime loader can derive its tmpdir-cache directory key without
// reading and hashing the whole .dylib/.so/.dll on every JVM cold start
// (5–15 MB of I/O + ~30–80 ms of SHA-256 compute on the harfbuzz-bg
// dispatcher otherwise - see NativeLibraryLoader.jvm.kt).
val writeNativeJvmSha by tasks.registering {
    dependsOn(copyNativeJvm)
    val libFile = resourcesNativeDir.file(host.libName)
    val shaFile = resourcesNativeDir.file("${host.libName}.sha256")
    inputs.file(libFile)
    outputs.file(shaFile)
    doLast {
        val bytes = libFile.asFile.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { byte -> "%02x".format(byte) }
        shaFile.asFile.writeText(hex)
    }
}

val cleanNativeJvm by tasks.registering(Delete::class) {
    delete(cmakeBuildDir, resourcesNativeDir)
}
tasks.named("clean") { dependsOn(cleanNativeJvm) }

// ──── Wasm harfbuzzjs build ───────────────────────────────────────────────
// Drives `make` in native/harfbuzzjs/ to produce hb.js + hb.wasm with the
// COLR/CPAL paint API re-enabled. Output is consumed by the kotlin-wasmJs
// target as a local npm dependency (see wasmJsMain dependencies below).
//
// Requires Emscripten (`em++`). Skipped on systems without it - devs not
// touching Wasm can ignore it; CI must have emsdk on PATH.

val harfbuzzjsDir = layout.projectDirectory.dir("../native/harfbuzzjs")

val buildHarfBuzzJs by tasks.registering(Exec::class) {
    workingDir = harfbuzzjsDir.asFile
    inputs.file(harfbuzzjsDir.file("Makefile"))
    inputs.file(harfbuzzjsDir.file("config-override.h"))
    inputs.file(harfbuzzjsDir.file("hb.symbols"))
    inputs.file(harfbuzzjsDir.file("em.runtime"))
    inputs.dir(layout.projectDirectory.dir("../native/harfbuzz/src"))
    outputs.file(harfbuzzjsDir.file("hb.js"))
    outputs.file(harfbuzzjsDir.file("hb.wasm"))
    commandLine("make")
}

val cleanHarfBuzzJs by tasks.registering(Exec::class) {
    workingDir = harfbuzzjsDir.asFile
    commandLine("make", "clean")
}
tasks.named("clean") { dependsOn(cleanHarfBuzzJs) }

// Yarn caches the local-file npm dep by package version, so a hb.wasm
// updated under the same version stays stale in `build/wasm/node_modules`.
// After npm install, sync the latest source files over the cached copy.
// This is cheap (a handful of small files) and idempotent: if the cache
// is already current, nothing changes; if it's stale, the local files win.
val syncLocalHarfbuzzjs by tasks.registering(Copy::class) {
    from(harfbuzzjsDir) {
        include("hb.js", "hb.wasm", "hbjs.js", "hb-worker.js", "index.js", "package.json", "LICENSE")
    }
    into(rootProject.layout.buildDirectory.dir("wasm/node_modules/kotlin-harfbuzzjs"))
}
rootProject.tasks.matching { it.name == "kotlinWasmNpmInstall" }.configureEach {
    finalizedBy(syncLocalHarfbuzzjs)
}

// We don't auto-attach `buildHarfBuzzJs` to the regular wasm pipeline
// because em++ may not be installed on every dev machine. The committed
// hb.js / hb.wasm are good enough for routine use; rerun the task by
// hand (`./gradlew :harfbuzz-core:buildHarfBuzzJs`) after touching the
// HarfBuzz submodule, hb.symbols, or config-override.h. CI runs it in
// the wasm matrix lane.

// Wire the native build into resource processing so the .dylib/.so/.dll
// is bundled in the final jvm jar at `native/<os-arch>/lib<name>.<ext>`.
// writeNativeJvmSha transitively depends on copyNativeJvm, so requesting
// it pulls the binary in too while also emitting the .sha256 sibling.
tasks.matching { it.name == "jvmProcessResources" }.configureEach {
    dependsOn(writeNativeJvmSha)
}

// ──── iOS native build ────────────────────────────────────────────────────
// Cross-compile HarfBuzz to a static .a for each iOS target Kotlin/Native
// ships. cinterop `.def` files reference the resulting .a + HarfBuzz headers.

data class IosTarget(
    val gradleName: String,        // "iosArm64", "iosSimulatorArm64"
    val cmakeBuildSubdir: String,  // "ios-arm64", "ios-simulator-arm64"
    val osxArch: String,           // "arm64"
    val sysroot: String,           // "iphoneos", "iphonesimulator"
)

val iosTargets = listOf(
    IosTarget("iosArm64", "ios-arm64", "arm64", "iphoneos"),
    IosTarget("iosSimulatorArm64", "ios-simulator-arm64", "arm64", "iphonesimulator"),
)

fun cmakeIosBuildDir(t: IosTarget) = layout.buildDirectory.dir("ios/${t.cmakeBuildSubdir}")
fun cmakeIosArchive(t: IosTarget) =
    cmakeIosBuildDir(t).map { it.file("harfbuzz/libharfbuzz.a") }


// Wire the iOS cinterop tasks so they depend on the corresponding native build.
iosTargets.forEach { t ->
    val capitalized = t.gradleName.replaceFirstChar { it.uppercaseChar() }
    tasks.matching { it.name == "cinteropHarfbuzz$capitalized" }.configureEach {
        dependsOn("buildHarfBuzz$capitalized", "generateCinteropDef$capitalized")
    }
}

iosTargets.forEach { t ->
    tasks.register<Exec>("configureHarfBuzz${t.gradleName.replaceFirstChar { it.uppercaseChar() }}") {
        group = "kotlin-harfbuzz native"
        description = "Configure CMake to build HarfBuzz static lib for ${t.gradleName}"
        workingDir = nativeRoot.asFile
        val outDir = cmakeIosBuildDir(t).get().asFile
        inputs.file(nativeRoot.file("CMakeLists.txt"))
        outputs.file(File(outDir, "CMakeCache.txt"))
        // xcrun is invoked by the shell at task execution time so configuration
        // cache stays valid (no external processes during the configure phase).
        commandLine(
            "sh", "-c",
            buildString {
                append("cmake -S . -B '")
                append(outDir.absolutePath)
                append("' -G 'Unix Makefiles'")
                append(" -DCMAKE_SYSTEM_NAME=iOS")
                append(" -DCMAKE_OSX_ARCHITECTURES=${t.osxArch}")
                append(" -DCMAKE_OSX_SYSROOT=\"$(xcrun --sdk ${t.sysroot} --show-sdk-path)\"")
                append(" -DCMAKE_OSX_DEPLOYMENT_TARGET=12.0")
                append(" -DKH_TARGET_IOS=ON")
                append(" -DCMAKE_BUILD_TYPE=Release")
                append(" -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY")
                append(" -DBUILD_TESTING=OFF")
            },
        )
    }

    tasks.register<Exec>("buildHarfBuzz${t.gradleName.replaceFirstChar { it.uppercaseChar() }}") {
        group = "kotlin-harfbuzz native"
        description = "Build HarfBuzz static lib for ${t.gradleName}"
        dependsOn("configureHarfBuzz${t.gradleName.replaceFirstChar { it.uppercaseChar() }}")
        val outDir = cmakeIosBuildDir(t).get().asFile
        inputs.file(nativeRoot.file("CMakeLists.txt"))
        inputs.dir(nativeRoot.dir("harfbuzz/src"))
        outputs.file(cmakeIosArchive(t))
        commandLine(
            "cmake", "--build", outDir.absolutePath,
            "--target", "harfbuzz",
            "--config", "Release",
            "--parallel",
        )
    }

    // Pre-resolve absolute paths so the doLast closure carries strings, not
    // project references - keeps the configuration cache happy.
    val archiveFile = cmakeIosArchive(t).get().asFile
    val archivePath = archiveFile.absolutePath
    val archiveDir = archiveFile.parentFile.absolutePath
    val headersPath = nativeRoot.dir("harfbuzz/src").asFile.absolutePath
    val defFileProvider = layout.buildDirectory.file("cinterop/${t.gradleName}/harfbuzz.def")

    tasks.register("generateCinteropDef${t.gradleName.replaceFirstChar { it.uppercaseChar() }}") {
        group = "kotlin-harfbuzz native"
        description = "Write the cinterop .def file for ${t.gradleName}"
        outputs.file(defFileProvider)
        inputs.property("archivePath", archivePath)
        doLast {
            val outFile = defFileProvider.get().asFile
            outFile.parentFile.mkdirs()
            outFile.writeText(
                """
                |package = com.mohamedrejeb.harfbuzz.native
                |
                |headers = hb.h hb-ot.h
                |headerFilter = hb*.h
                |
                |# HarfBuzz uses `const char *` for raw byte buffers; tell cinterop NOT to
                |# auto-convert these to Kotlin String. We pass binary fonts in via these.
                |noStringConversion = hb_blob_create hb_blob_create_or_fail hb_blob_create_writable
                |
                |compilerOpts = -I${headersPath}
                |linkerOpts = -lc++
                |staticLibraries = libharfbuzz.a
                |libraryPaths = ${archiveDir}
                |""".trimMargin()
            )
        }
    }
}


kotlin {
    explicitApi()

    compilerOptions {
        // Silence the "expect/actual classes are in Beta" warning - the design intentionally uses
        // expect classes for HbBlob/HbFace/HbFont/HbBuffer to keep the public surface clean.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        publishLibraryVariants("release")
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "HarfbuzzCore"
            isStatic = true
        }
        iosTarget.compilations.getByName("main").cinterops.create("harfbuzz") {
            definitionFile.set(
                layout.buildDirectory.file("cinterop/${iosTarget.targetName}/harfbuzz.def")
            )
            packageName = "com.mohamedrejeb.harfbuzz.native"
        }
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutinesCore)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutinesTest)
            }
        }

        // Shared between Android and Desktop JVM - JNI bridge lives here.
        val jvmCommonMain by creating {
            dependsOn(commonMain)
        }
        val androidMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                // androidx.startup pre-warms `libharfbuzz_jni.so` on app
                // process start (before the first composable mounts), so
                // `harfBuzzInit()`'s `System.loadLibrary` cost is paid on
                // a daemon thread instead of in the cold-paint window.
                // Consumers can opt out with `tools:node="remove"` on the
                // initializer's `<meta-data>` element in their manifest.
                implementation(libs.androidx.startup.runtime)
            }
        }
        val jvmMain by getting {
            dependsOn(jvmCommonMain)
        }

        // Wasm: pulls in our locally-built fork of harfbuzzjs (in
        // ../native/harfbuzzjs/) which re-enables the COLR/CPAL paint API.
        // The upstream npm package strips paint+color via HB_TINY, so we
        // build em++ output ourselves - see native/harfbuzzjs/Makefile.
        // copy-webpack-plugin is a build-time only dep used by
        // sample/webpack.config.d/harfbuzzjs.js to ship hb.wasm next to the
        // bundle so the default `fetch('hb.wasm')` resolves.
        val wasmJsMain by getting {
            dependencies {
                implementation(npm(File("$rootDir/native/harfbuzzjs")))
                implementation(devNpm("copy-webpack-plugin", "11.0.0"))
            }
        }

        // Android-only instrumented benchmarks live here. They run on a
        // connected device or emulator and exercise the real
        // `SystemFonts.getAvailableFonts()` path, which is what
        // [AndroidColdInitBenchmarks] is designed to measure.
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.androidx.testExt.junit)
                implementation(libs.androidx.test.runner)
                implementation(libs.kotlinx.coroutinesTest)
            }
        }
    }
}

android {
    namespace = "com.mohamedrejeb.harfbuzz.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()

        // androidx.test runner powers the `connectedDebugAndroidTest`
        // task that hosts [AndroidColdInitBenchmarks]. The runBenchmarks
        // instrumentation arg gates them off-by-default - see the
        // `tasks.withType<Test>` block at the bottom for the JVM
        // counterpart.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (project.hasProperty("runBenchmarks")) {
            testInstrumentationRunnerArguments["runBenchmarks"] = "true"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                )
                cppFlags += listOf("-std=c++17")
                targets += "harfbuzz_jni"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Forward the `-PrunBenchmarks` Gradle property to the JVM test JVM as
// a system property so [ShapingBenchmarks] can opt in. CI runs without
// the flag and `Assume.assumeTrue` skips every benchmark cleanly.
tasks.withType<Test>().configureEach {
    if (project.hasProperty("runBenchmarks")) {
        systemProperty("runBenchmarks", "true")
        // Benchmarks print throughput results; surface them in the
        // gradle log so callers don't have to dig into the test report.
        testLogging {
            showStandardStreams = true
        }
    }
}
