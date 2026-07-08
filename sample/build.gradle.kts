import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Sample"
            isStatic = true
        }
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(projects.harfbuzzCore)
            implementation(projects.harfbuzzCompose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.uiTest)
            implementation(libs.compose.uiTestJUnit4)
            implementation(libs.junit)
        }
    }
}

// One-shot generator for the app's font-picker thumbnails. Renders every
// font named in thumbgen/thumb-names.tsv to build/font-thumbs/out/.
// Optional: -PthumbFilter=Ps1,Ps2 to render a subset for spot checks.
tasks.register<JavaExec>("generateFontThumbnails") {
    group = "thumbgen"
    description = "Render font-picker thumbnails from thumbgen/thumb-names.tsv"
    val jvmMainCompilation = kotlin.targets.getByName("jvm")
        .compilations.getByName("main")
    classpath = files(
        jvmMainCompilation.output.allOutputs,
        jvmMainCompilation.runtimeDependencyFiles,
    )
    mainClass.set("com.mohamedrejeb.harfbuzz.tools.FontThumbnailGeneratorKt")
    workingDir = projectDir
    args = buildList {
        add(findProperty("thumbJobs")?.toString() ?: "thumbgen/thumb-names.tsv")
        add(findProperty("thumbOut")?.toString() ?: "build/font-thumbs")
        findProperty("thumbFilter")?.let { add(it.toString()) }
    }
}

// Forward the regeneration flag from the Gradle JVM into the forked test
// JVM so `-Dkotlin.harfbuzz.regenerate.goldens=true` actually reaches the
// screenshot harness.
tasks.withType<Test>().configureEach {
    systemProperty(
        "kotlin.harfbuzz.regenerate.goldens",
        System.getProperty("kotlin.harfbuzz.regenerate.goldens", "false"),
    )
}

android {
    namespace = "com.mohamedrejeb.harfbuzz"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mohamedrejeb.harfbuzz"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.mohamedrejeb.harfbuzz.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.mohamedrejeb.harfbuzz"
            packageVersion = "1.0.0"
        }
    }
}
