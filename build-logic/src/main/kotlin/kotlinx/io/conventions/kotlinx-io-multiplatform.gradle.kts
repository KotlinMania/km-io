/*
 * Copyright 2017-2025 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

import kotlinx.io.build.configureJava9ModuleInfoCompilation
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import kotlin.jvm.optionals.getOrNull

plugins {
    kotlin("multiplatform")
    kotlin("plugin.power-assert")
    id("com.android.kotlin.multiplatform.library")
    id("kotlinx-io-clean")
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        allWarningsAsErrors = true
        freeCompilerArgs.add("-Xexpect-actual-classes")
        freeCompilerArgs.add("-Xreturn-value-checker=full")
    }

    val versionCatalog: VersionCatalog = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
    jvmToolchain {
        val javaVersion = versionCatalog.findVersion("java").getOrNull()?.requiredVersion
            ?: throw GradleException("Version 'java' is not specified in the version catalog")
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }

    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
        compilerOptions {
            jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
        }

        val mrjToolchain = versionCatalog.findVersion("multi.release.toolchain").getOrNull()?.requiredVersion
            ?: throw GradleException("Version 'multi.release.toolchain' is not specified in the version catalog")

        // N.B.: it seems like modules don't work well with "regular" multi-release compilation,
        // so if we need to compile some Kotlin classes for a specific JDK release, a separate compilation is needed.
        //
        // The km-io rebrand renamed each JPMS module from `kotlinx.io.<suffix>`
        // to `io.github.kotlinmania.io.<suffix>` (see module-info.java in each
        // module's jvm/module/). The Gradle module names stayed `km-io-<suffix>`,
        // so the `--patch-module` name must be re-computed from the suffix
        // rather than the project name verbatim or javac thinks the module is
        // empty and the build fails with "package is empty or does not exist".
        val jpmsModuleName = "io.github.kotlinmania.io." +
            project.name.removePrefix("km-io-")
        configureJava9ModuleInfoCompilation(
            sourceSetName = project.sourceSets.create("java9ModuleInfo") {
                java.srcDir("jvm/module")
            }.name,
            parentCompilation = compilations.getByName("main"),
            moduleName = jpmsModuleName,
            toolchainVersion = JavaLanguageVersion.of(mrjToolchain)
        )
    }

    js {
        browser {
            testTask {
                filter.setExcludePatterns("*SmokeFileTest*")
            }
        }
        nodejs {
            testTask {
                filter.setExcludePatterns("*SmokeFileTest*")
            }
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    // The com.android.kotlin.multiplatform.library plugin contributes an
    // `android` target to KMP. Each module sets its own namespace from its
    // own build.gradle.kts because the namespace is unique per module.
    android {
        compileSdk = 34
        minSdk = 24
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }
    }

    nativeTargets()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }

    explicitApi()
    sourceSets.configureEach {
        configureSourceSet()
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("native") {
                group("nativeNonApple") {
                    group("mingw")
                    group("unix") {
                        group("linux")
                        group("androidNative")
                    }
                }

                group("nativeNonAndroid") {
                    group("apple")
                    group("mingw")
                    group("linux")
                }
            }
            group("nodeFilesystemShared") {
                withJs()
                withWasmJs()
            }
            group("wasm") {
                withWasmJs()
                withWasmWasi()
            }
        }
    }

    // The Android target gets its own actuals tree under android/src. Sharing
    // jvm/src between jvmMain and androidMain ran into two structural issues
    // at once: KMP forbids the same file appearing in two source sets, and
    // when we routed jvm/src through an intermediate jvmAndroidMain group
    // the legacy `compileJava9ModuleInfoJava` task lost track of the JVM
    // classes via `parentCompilation.output.allOutputs`. The minimal
    // Android-specific actuals (typealiases to the JVM equivalents, plus
    // a thin file-system implementation) live next to the JVM actuals and
    // cost roughly one file per `actual class` exposed by commonMain.
    sourceSets.findByName("androidMain")?.kotlin?.srcDir("android/src")
    // androidHostTest and androidDeviceTest both compile against commonTest,
    // so both need the Android-target `actual` declarations that mirror the
    // JVM ones. They share a single `android/test/` directory; if device
    // tests need device-only code in the future, add it next to the shared
    // actuals and gate it with appropriate runtime checks. The AGP source
    // sets resolve `androidDeviceTest` only when the `android` block is
    // configured with `withDeviceTestBuilder { ... }`, so `findByName` is
    // the right call shape.
    sourceSets.findByName("androidHostTest")?.kotlin?.srcDir("android/test")
    sourceSets.findByName("androidDeviceTest")?.kotlin?.srcDir("android/test")

    tasks {
        val jvmJar by existing(Jar::class) {
            manifest {
                attributes(
                    "Multi-Release" to true,
                    "Implementation-Vendor" to "JetBrains",
                    "Implementation-Title" to project.name,
                    "Implementation-Version" to project.version,
                )
            }
            from(project.sourceSets["java9ModuleInfo"].output)
        }
    }
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    // assertFails* are not included as p-a does not help with them yet
    val kotlinTestFunctions = listOf(
        "assertTrue", "assertFalse",
        "assertNull", "assertNotNull",
        "assertSame", "assertNotSame",
        "assertEquals", "assertNotEquals",
        "assertIs", "assertIsNot",
        "assertIsOfType", "assertIsNotOfType",
        "assertContains",
        "assertContentEquals",
        "expect"
    ).map { "kotlin.test.$it"}

    functions.addAll(kotlinTestFunctions)
}

fun KotlinSourceSet.configureSourceSet() {
    val srcDir = if (name.endsWith("Main")) "src" else "test"
    val platform = name.dropLast(4)
    // The AGP `android` target source sets (androidMain, androidHostTest,
    // androidDeviceTest) follow the standard src/<X>Main/kotlin layout —
    // their srcDir is wired in the convention's kotlin block. The
    // androidNative* native targets keep using the legacy <platform>/src
    // layout shared with every other native target.
    if (name == "androidMain" || name == "androidHostTest" || name == "androidDeviceTest") {
        languageSettings { progressiveMode = true }
        return
    }
    kotlin.srcDir("$platform/$srcDir")
    if (name == "jvmMain") {
        resources.srcDir("$platform/resources")
    } else if (name == "jvmTest") {
        resources.srcDir("$platform/test-resources")
    }
    languageSettings {
        progressiveMode = true
    }
}

private fun KotlinMultiplatformExtension.nativeTargets() {
    val configureAllTargets = project.findProperty("kotlinx.io.okio.compat.targets")?.toString()?.toBoolean() != true

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    tvosX64()
    tvosArm64()
    tvosSimulatorArm64()

    watchosArm32()
    watchosArm64()
    watchosX64()
    watchosSimulatorArm64()
    watchosDeviceArm64()

    if (configureAllTargets) {
        androidNativeArm32()
        androidNativeArm64()
        androidNativeX64()
        androidNativeX86()
    }

    linuxX64()
    linuxArm64()
    if (configureAllTargets) {
        @Suppress("DEPRECATION") // https://github.com/Kotlin/kotlinx-io/issues/303
        linuxArm32Hfp()
    }

    macosX64()
    macosArm64()

    mingwX64()
}
