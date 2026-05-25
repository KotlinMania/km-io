/*
 * Copyright 2010-2024 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity

plugins {
    id("kotlinx-io-multiplatform")
    id("kotlinx-io-publish")
    id("kotlinx-io-dokka")
    id("kotlinx-io-compatibility")
    alias(libs.plugins.kover)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":km-io-core"))
            api(project(":km-io-bytestring"))
            api(libs.okio)
        }
    }

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
            }
        }
    }

    // Each Kotlin Multiplatform module sets its own Android namespace; the
    // shared kotlinx-io-multiplatform convention configures compileSdk/minSdk.
    android {
        namespace = "io.github.kotlinmania.io.okio"
    }
}

dokka {
    dokkaSourceSets.configureEach {
        externalDocumentationLinks.register("okio") {
            url("https://square.github.io/okio/3.x/okio/")
            packageListUrl("https://square.github.io/okio/3.x/okio/okio/package-list")
        }
    }
}

// ---------------------------------------------------------------------------
// CodeQL Java/Kotlin extraction task — see bytestring/build.gradle.kts for
// the same pattern. Sources live under common/src and jvm/src because the
// kotlinx-io-multiplatform convention uses a <platform>/src layout.
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
    codeqlSourceClasspath(project(":km-io-core"))
    codeqlSourceClasspath(project(":km-io-bytestring"))
    codeqlSourceClasspath(libs.okio)
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
                "io/github/kotlinmania/io/okio/codeql/_CodeqlEmptySource.kt",
            )
            sentinelFile.parentFile.mkdirs()
            sentinelFile.writeText(
                """
                // Auto-generated. Present so codeqlCompileJvm has at least
                // one Kotlin source to feed kotlinc; replaced by real
                // commonMain content once porting begins.
                package io.github.kotlinmania.io.okio.codeql

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
    "assembleAndroidHostTest",
    "assembleAndroidDeviceTest",
    "assembleUnitTest",
    "assembleAndroidTest",
    "testAndroidHostTest",
    "jvmMainClasses",
    "jvmTestClasses",
    "jvmTest",
    "jsMainClasses",
    "jsTestClasses",
    "jsBrowserTest",
    "jsNodeTest",
    "jsTest",
    "wasmJsMainClasses",
    "wasmJsTestClasses",
    "wasmJsBrowserTest",
    "wasmJsNodeTest",
    "wasmJsTest",
    "wasmWasiMainClasses",
    "wasmWasiTestClasses",
    "wasmWasiNodeTest",
    "wasmWasiTest",
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
    // Okio 3.17.0 does not publish androidNative or linuxArm32Hfp variants.
    // Keep km-io-okio on the largest resolvable target set until that upstream
    // artifact exists or KotlinMania owns an equivalent Okio-compatible port.
    dependsOn(fullTargetBuildTasks.mapNotNull { tasks.findByName(it) })
}
