/*
 * Copyright 2017-2023 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

@file:OptIn(ExperimentalWasmDsl::class)

import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("kotlinx-io-multiplatform")
    id("kotlinx-io-publish")
    id("kotlinx-io-dokka")
    id("kotlinx-io-android-compat")
    id("kotlinx-io-compatibility")
    alias(libs.plugins.kover)
}

kotlin {
    js {
        nodejs {
            testTask {
                useMocha {
                    timeout = "300s"
                }
            }
        }
        browser {
            testTask {
                useMocha {
                    timeout = "300s"
                }
                filter.setExcludePatterns("io.github.kotlinmania.io.files.*")
            }
        }
    }
    wasmJs {
        nodejs()
        /*
        browser {
            testTask {
                useKarma {
                    // how to configure browsers available in CI?
                }
                filter.setExcludePatterns("io.github.kotlinmania.io.files.*")
            }
        }
         */
    }
    wasmWasi {
        nodejs {
            testTask {
                // fd_readdir is unsupported on Windows:
                // https://github.com/nodejs/node/blob/6f4d6011ea1b448cf21f5d363c44e4a4c56ca34c/deps/uvwasi/src/uvwasi.c#L19
                if (OperatingSystem.current().isWindows) {
                    filter.setExcludePatterns("*SmokeFileTest.listDirectory")
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":km-io-bytestring"))
        }
        appleTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }

    // Each Kotlin Multiplatform module sets its own Android namespace; the
    // shared kotlinx-io-multiplatform convention configures compileSdk/minSdk.
    android {
        namespace = "io.github.kotlinmania.io"
    }
}

tasks.named("wasmWasiNodeTest") {
    // TODO: remove once https://youtrack.jetbrains.com/issue/KT-65179 solved
    doFirst {
        val layout = project.layout
        val templateFile = layout.projectDirectory.file("wasmWasi/test/test-driver.mjs.template").asFile

        val driverFile = layout.buildDirectory.file(
            "compileSync/wasmWasi/test/testDevelopmentExecutable/kotlin/kotlinx-io-kotlinx-io-core-test.mjs"
        )

        fun File.mkdirsAndEscape(): String {
            mkdirs()
            return absolutePath.replace("\\", "\\\\")
        }

        val tmpDir = temporaryDir.resolve("kotlinx-io-core-wasi-test").mkdirsAndEscape()
        val tmpDir2 = temporaryDir.resolve("kotlinx-io-core-wasi-test-2").mkdirsAndEscape()

        val newDriver = templateFile.readText()
            .replace("<SYSTEM_TEMP_DIR>", tmpDir, false)
            .replace("<SYSTEM_TEMP_DIR2>", tmpDir2, false)

        driverFile.get().asFile.writeText(newDriver)
    }
}

animalsniffer {
    annotation = "io.github.kotlinmania.io.files.AnimalSnifferIgnore"
}

// ---------------------------------------------------------------------------
// CodeQL Java/Kotlin extraction task — see bytestring/build.gradle.kts for
// the same pattern. The km-io modules use a custom <platform>/src layout,
// so the source roots below point at common/src and jvm/src.
val codeqlKotlinc: Configuration by configurations.creating {
    description = "Kotlin compiler (CodeQL extraction target only — not published)"
    isCanBeResolved = true
    isCanBeConsumed = false
}

val codeqlSourceClasspath: Configuration by configurations.creating {
    description = "Runtime classpath for CodeQL extraction of commonMain sources"
    isCanBeResolved = true
    isCanBeConsumed = false
}

val codeqlAndroidAar: Configuration by configurations.creating {
    description = "Android AAR artifacts for CodeQL classpath extraction (classes.jar only)"
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    codeqlKotlinc("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
    codeqlSourceClasspath("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
    codeqlSourceClasspath(project(":km-io-bytestring"))
}

val codeqlCompileJvm = tasks.register<JavaExec>("codeqlCompileJvm") {
    description =
        "Compile commonMain Kotlin sources with kotlinc for CodeQL Java/Kotlin extraction."
    group = "verification"

    classpath(codeqlKotlinc)
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")

    val outDir = layout.buildDirectory.dir("classes/kotlin/codeql-jvm")
    val aarExtractDir = layout.buildDirectory.dir("codeql/android-aar")
    val commonSources = fileTree("common/src") { include("**/*.kt") }
    val platformSources = fileTree("jvm/src") { include("**/*.kt") }
    val sources = files(commonSources, platformSources)
    val sentinelDir = layout.buildDirectory.dir("generated/codeql-empty-source")
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(codeqlSourceClasspath).withNormalizer(ClasspathNormalizer::class.java)
    inputs.files(codeqlAndroidAar).withNormalizer(ClasspathNormalizer::class.java)
    outputs.dir(outDir)
    outputs.dir(aarExtractDir)
    outputs.dir(sentinelDir)

    doFirst {
        outDir.get().asFile.mkdirs()
        val extractedJars = mutableListOf<File>()
        for (aar in codeqlAndroidAar.resolve()) {
            val extractTarget = aarExtractDir.get().asFile.resolve(aar.nameWithoutExtension)
            extractTarget.mkdirs()
            copy {
                from(zipTree(aar))
                include("classes.jar")
                into(extractTarget)
            }
            val classesJar = extractTarget.resolve("classes.jar")
            if (classesJar.exists()) {
                extractedJars += classesJar
            }
        }
        val fullClasspath =
            (codeqlSourceClasspath.resolve() + extractedJars)
                .joinToString(File.pathSeparator) { it.absolutePath }
        val commonSourceFiles = commonSources.files.toMutableList()
        val sourceFiles = sources.files.toMutableList()
        if (sourceFiles.isEmpty()) {
            val sentinelFile = sentinelDir.get().asFile.resolve(
                "io/github/kotlinmania/io/codeql/_CodeqlEmptySource.kt",
            )
            sentinelFile.parentFile.mkdirs()
            sentinelFile.writeText(
                """
                // Auto-generated. Present so codeqlCompileJvm has at least
                // one Kotlin source to feed kotlinc; replaced by real
                // commonMain content once porting begins.
                package io.github.kotlinmania.io.codeql

                private object _CodeqlEmptySource
                """.trimIndent(),
            )
            commonSourceFiles += sentinelFile
            sourceFiles += sentinelFile
        }
        args = listOf(
            "-d", outDir.get().asFile.absolutePath,
            "-classpath", fullClasspath,
            "-jvm-target", "17",
            "-no-stdlib",
            "-no-reflect",
            "-language-version", "2.3",
            "-api-version", "2.3",
            "-Xmulti-platform",
            "-Xcommon-sources=${commonSourceFiles.joinToString(",") { it.absolutePath }}",
            "-Xexpect-actual-classes",
        ) + sourceFiles.map { it.absolutePath }
    }
}

// ---------------------------------------------------------------------------
// Same fullTargetBuildTasks wiring as the other km-io modules; see
// bytestring/build.gradle.kts for the canonical comment.
val fullTargetBuildTasks = listOf(
    "compileAndroidMain",
    "compileAndroidHostTest",
    "compileAndroidDeviceTest",
    "assembleAndroidMain",
    "assembleUnitTest",
    "assembleAndroidTest",
    "jvmMainClasses",
    "jvmTestClasses",
    "jsMainClasses",
    "jsTestClasses",
    "wasmJsMainClasses",
    "wasmJsTestClasses",
    "wasmWasiMainClasses",
    "wasmWasiTestClasses",
    "androidNativeArm32Binaries",
    "androidNativeArm32TestBinaries",
    "androidNativeArm64Binaries",
    "androidNativeArm64TestBinaries",
    "androidNativeX64Binaries",
    "androidNativeX64TestBinaries",
    "androidNativeX86Binaries",
    "androidNativeX86TestBinaries",
    "iosArm64Binaries",
    "iosArm64TestBinaries",
    "iosSimulatorArm64Binaries",
    "iosSimulatorArm64TestBinaries",
    "iosX64Binaries",
    "iosX64TestBinaries",
    "linuxArm64Binaries",
    "linuxArm64TestBinaries",
    "linuxX64Binaries",
    "linuxX64TestBinaries",
    "macosArm64Binaries",
    "macosArm64TestBinaries",
    "macosX64Binaries",
    "macosX64TestBinaries",
    "mingwX64Binaries",
    "mingwX64TestBinaries",
    "tvosArm64Binaries",
    "tvosArm64TestBinaries",
    "tvosSimulatorArm64Binaries",
    "tvosSimulatorArm64TestBinaries",
    "tvosX64Binaries",
    "tvosX64TestBinaries",
    "watchosArm32Binaries",
    "watchosArm32TestBinaries",
    "watchosArm64Binaries",
    "watchosArm64TestBinaries",
    "watchosDeviceArm64Binaries",
    "watchosDeviceArm64TestBinaries",
    "watchosSimulatorArm64Binaries",
    "watchosSimulatorArm64TestBinaries",
    "watchosX64Binaries",
    "watchosX64TestBinaries",
)

tasks.named("build") {
    dependsOn(fullTargetBuildTasks)
}
