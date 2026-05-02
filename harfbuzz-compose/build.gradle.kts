import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    explicitApi()

    compilerOptions {
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
            baseName = "HarfbuzzCompose"
            isStatic = true
        }
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(projects.harfbuzzCore)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        // androidMain pulls in AndroidSVG to render OT-SVG glyph
        // documents - see SvgGlyphRenderer.android.kt for why we don't
        // share the JVM/iOS skiko SVGDOM path.
        androidMain.dependencies {
            implementation(libs.androidsvg)
        }

        // The JVM test source set materialises Compose `Path` objects
        // when exercising `buildMeasuredText` end-to-end, so it needs
        // skiko on the runtime classpath. `compose.desktop.currentOs`
        // brings in the correct host-specific skiko native bundle.
        //
        // The compose.uiTest / uiTestJUnit4 / uiTestDesktop trio drives
        // a real Compose Recomposer end-to-end so layout-modifier tests
        // (e.g. RememberTextBoundsTest) can observe materialised
        // measurements via `createComposeRule()`.
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.compose.uiTest)
            implementation(libs.compose.uiTestJUnit4)
            implementation(libs.compose.uiTestDesktop)
        }

        // Android-only instrumented benchmarks live here. They run on a
        // connected device or emulator and exercise buildMeasuredText
        // end-to-end through the real Android pipeline (asset-backed
        // font, Compose Path materialisation). Mirrors the equivalent
        // setup in :harfbuzz-core which hosts AndroidColdInitBenchmarks.
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.androidx.testExt.junit)
                implementation(libs.androidx.test.runner)
                implementation(libs.kotlinx.coroutinesTest)
            }
        }

        // Compose Multiplatform's JVM, iOS, and Wasm targets all draw
        // through skiko, which exposes `org.jetbrains.skia.*` for direct
        // Skia access (Bitmap, Data, Surface). Android uses system Skia
        // and doesn't have skiko on its classpath, so anything that
        // calls into skiko lives in a shared intermediate set that
        // androidMain doesn't pull from.
        //
        // jvmIosMain is a further intermediate that holds skiko code we
        // do NOT want on Wasm - specifically the SVGDOM-based glyph
        // rasteriser. On Wasm we replace SVGDOM with a much faster path
        // through the browser's native SVG renderer (createImageBitmap),
        // so wasmJsMain provides its own actual for renderSvgGlyph.
        val skikoMain by creating {
            dependsOn(commonMain.get())
        }
        val jvmIosMain by creating {
            dependsOn(skikoMain)
        }
        jvmMain.get().dependsOn(jvmIosMain)
        iosMain.get().dependsOn(jvmIosMain)
        wasmJsMain.get().dependsOn(skikoMain)
    }
}

android {
    namespace = "com.mohamedrejeb.harfbuzz.compose"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()

        // androidx.test runner powers connectedDebugAndroidTest. The
        // runBenchmarks instrumentation arg gates every benchmark off
        // by default - see the tasks.withType<Test> block below for
        // the JVM counterpart.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (project.hasProperty("runBenchmarks")) {
            testInstrumentationRunnerArguments["runBenchmarks"] = "true"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Forward the `-PrunBenchmarks` Gradle property to the JVM test JVM so
// the Compose-layer benchmarks (MeasuredTextCacheBenchmark, ...) can
// opt in. Mirrors the same wiring in :harfbuzz-core. CI runs without
// the flag and `Assume.assumeTrue` skips every benchmark cleanly.
tasks.withType<Test>().configureEach {
    if (project.hasProperty("runBenchmarks")) {
        systemProperty("runBenchmarks", "true")
        testLogging {
            showStandardStreams = true
        }
    }
}
