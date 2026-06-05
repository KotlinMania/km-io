/*
 * Copyright 2017-2023 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

@file:OptIn(ExperimentalSwiftExportDsl::class, ExperimentalWasmDsl::class)

import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.swiftexport.ExperimentalSwiftExportDsl

plugins {
    id("kotlinx-io-multiplatform")
    id("kotlinx-io-publish")
    id("kotlinx-io-dokka")
    id("kotlinx-io-android-compat")
    id("kotlinx-io-compatibility")
    alias(libs.plugins.kover)
}

kotlin {
    val xcf = XCFramework("KmIo")

    macosArm64 {
        binaries.framework {
            baseName = "KmIo"
            isStatic = true
            xcf.add(this)
        }
    }
    iosArm64 {
        binaries.framework {
            baseName = "KmIo"
            isStatic = true
            xcf.add(this)
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "KmIo"
            isStatic = true
            xcf.add(this)
        }
    }
    iosX64 {
        binaries.framework {
            baseName = "KmIo"
            isStatic = true
            xcf.add(this)
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
                filter.setExcludePatterns("*SmokeFileTest*")
            }
        }
    }
    wasmJs {
        browser {
            testTask {
                filter.setExcludePatterns("*SmokeFileTest*")
            }
        }
        nodejs()
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

    swiftExport {
        moduleName = "KmIo"
        flattenPackage = "io.github.kotlinmania.io"
        export(project(":km-io-bytestring")) {
            moduleName = "KmIoByteString"
            flattenPackage = "io.github.kotlinmania.io.bytestring"
        }
        configure {
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }
}

tasks.named("wasmWasiNodeTest") {
    // TODO: remove once https://youtrack.jetbrains.com/issue/KT-65179 solved
    val rootName = rootProject.name
    val moduleName = project.name
    doFirst {
        val layout = project.layout
        val templateFile = layout.projectDirectory.file("wasmWasi/test/test-driver.mjs.template").asFile

        // The Kotlin/Wasm test runner names the generated mjs entry-point
        // `<rootProject.name>-<project.name>-test.mjs`. Before the kotlinx-io
        // -> km-io rebrand this was the hard-coded
        // `kotlinx-io-kotlinx-io-core-test.mjs`; afterwards it is
        // `km-io-km-io-core-test.mjs`. Compute it from the project metadata so
        // future renames don't leave the patch writing to a stale, unloaded
        // file while the real driver runs unpatched.
        val driverFile = layout.buildDirectory.file(
            "compileSync/wasmWasi/test/testDevelopmentExecutable/kotlin/$rootName-$moduleName-test.mjs"
        )

        fun File.mkdirsAndEscape(): String {
            mkdirs()
            return absolutePath.replace("\\", "\\\\")
        }

        val tmpDir = temporaryDir.resolve("km-io-core-wasi-test").mkdirsAndEscape()
        val tmpDir2 = temporaryDir.resolve("km-io-core-wasi-test-2").mkdirsAndEscape()

        val newDriver = templateFile.readText()
            .replace("<SYSTEM_TEMP_DIR>", tmpDir, false)
            .replace("<SYSTEM_TEMP_DIR2>", tmpDir2, false)
            .replace("<WASM_FILE>", "$rootName-$moduleName-test.wasm", false)

        driverFile.get().asFile.writeText(newDriver)
    }
}

fun patchSwiftPackage(packageDir: File) {
    val swiftExportArchiveName = "KmIo"
    val packageSwift = packageDir.resolve("Package.swift")
    if (packageSwift.exists()) {
        val text = packageSwift.readText()
        if (!text.contains("platforms:")) {
            packageSwift.writeText(
                text.replaceFirst(
                    Regex("(name:\\s*\"[^\"]*\",)"),
                    "\$1\n    platforms: [.macOS(.v14)],",
                ),
            )
        }
    }

    val sourcesDir = packageDir.resolve("Sources")
    if (!sourcesDir.exists()) return
    sourcesDir
        .walkTopDown()
        .filter { it.isFile && it.extension == "swift" }
        .forEach { swiftFile ->
            val text = swiftFile.readText()
            val patched =
                text
                    .replace("Foundation.NSInputStream", "Foundation.InputStream")
                    .replace("Foundation.NSOutputStream", "Foundation.OutputStream")
            if (patched != text) {
                swiftFile.writeText(patched)
            }
        }
    packageDir
        .walkTopDown()
        .filter { it.isFile && it.name == "module.modulemap" }
        .forEach { moduleMap ->
            val text = moduleMap.readText()
            val patched =
                Regex("""link "[^"]+"""").replace(text) {
                    "link \"$swiftExportArchiveName\""
                }
            if (patched != text) {
                moduleMap.writeText(patched)
            }
        }
}

fun patchSwiftExportGeneratedKotlin(filesDir: File) {
    if (!filesDir.exists()) return
    val unitBridgeReturn =
        Regex("""(?m)^([ \t]*)val _result = run \{ ([^\n{}]*) \}\R\1return run \{ _result; true \}""")
    val swiftUnitCallback =
        Regex("""(?m)^([ \t]*)val _result = kotlinFun\((.*)\)\s*\R\1run<Unit> \{ _result \}\s*$""")
    val patchedSwiftUnitCallback =
        Regex("""(?m)^([ \t]*)kotlinFun\((.*)\)\s*\R\1Unit\s*$""")

    filesDir
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .forEach { kotlinFile ->
            var text = kotlinFile.readText()
            var patched =
                unitBridgeReturn.replace(text) { match ->
                    val indent = match.groupValues[1]
                    val expression = match.groupValues[2]
                    "$indent run { $expression }\n${indent}return true"
                }
            patched =
                swiftUnitCallback.replace(patched) { match ->
                    val indent = match.groupValues[1]
                    val expression = match.groupValues[2]
                    "$indent kotlinFun($expression).let { Unit }"
                }
            patched =
                patchedSwiftUnitCallback.replace(patched) { match ->
                    val indent = match.groupValues[1]
                    val expression = match.groupValues[2]
                    "$indent kotlinFun($expression).let { Unit }"
                }
            if (
                patched.contains("writeToInternalBuffer") &&
                !patched.contains("@file:kotlin.OptIn(io.github.kotlinmania.io.DelicateIoApi::class)")
            ) {
                patched =
                    if (patched.startsWith("@file:")) {
                        patched.replaceFirst(
                            Regex("""\A((?:@file:[^\n]*\n)+)"""),
                            "$1@file:kotlin.OptIn(io.github.kotlinmania.io.DelicateIoApi::class)\n",
                        )
                    } else {
                        "@file:kotlin.OptIn(io.github.kotlinmania.io.DelicateIoApi::class)\n$patched"
                    }
            }
            if (patched != text) {
                kotlinFile.writeText(patched)
            }
        }
}

val patchMacosArm64DebugSwiftPackage =
    tasks.register("patchMacosArm64DebugSwiftPackage") {
        dependsOn("macosArm64DebugGenerateSPMPackage")
        doLast {
            patchSwiftPackage(
                layout.buildDirectory
                    .dir("SPMPackage/macosArm64/Debug")
                    .get()
                    .asFile,
            )
        }
    }

val patchMacosArm64DebugSwiftExportKotlin =
    tasks.register("patchMacosArm64DebugSwiftExportKotlin") {
        dependsOn("macosArm64DebugSwiftExport")
        doLast {
            patchSwiftExportGeneratedKotlin(
                layout.buildDirectory
                    .dir("SwiftExport/macosArm64/Debug/files")
                    .get()
                    .asFile,
            )
        }
    }

tasks.configureEach {
    if (name == "macosArm64DebugBuildSPMPackage") {
        dependsOn(patchMacosArm64DebugSwiftPackage)
    }
    if (name == "compileSwiftExportMainKotlinMacosArm64") {
        dependsOn(patchMacosArm64DebugSwiftExportKotlin)
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
    val codeqlKotlinVersion = providers.gradleProperty("codeql.kotlin.version").getOrElse(libs.versions.kotlin.get())
    codeqlKotlinc("org.jetbrains.kotlin:kotlin-compiler-embeddable:$codeqlKotlinVersion")
    codeqlSourceClasspath("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
    codeqlSourceClasspath(project(":km-io-bytestring"))
}

val codeqlLanguageVersion =
    providers
        .gradleProperty("kotlin.languageVersion")
        .getOrElse(libs.versions.kotlin.get().split('.').take(2).joinToString("."))
val codeqlApiVersion = providers.gradleProperty("kotlin.apiVersion").getOrElse(codeqlLanguageVersion)
val codeqlJvmTarget = providers.gradleProperty("jvm.toolchain").getOrElse("21")

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
            "-jvm-target", codeqlJvmTarget,
            "-no-stdlib",
            "-no-reflect",
            "-language-version", codeqlLanguageVersion,
            "-api-version", codeqlApiVersion,
            "-Xmulti-platform",
            "-Xcommon-sources=${commonSourceFiles.joinToString(",") { it.absolutePath }}",
            "-Xexpect-actual-classes",
            "-Xreturn-value-checker=full",
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
    "assembleAndroidDeviceTest",
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
    "mingwX64Binaries",
    "mingwX64TestBinaries",
    "tvosArm64Binaries",
    "tvosArm64TestBinaries",
    "tvosSimulatorArm64Binaries",
    "tvosSimulatorArm64TestBinaries",
    "watchosArm64Binaries",
    "watchosArm64TestBinaries",
    "watchosDeviceArm64Binaries",
    "watchosDeviceArm64TestBinaries",
    "watchosSimulatorArm64Binaries",
    "watchosSimulatorArm64TestBinaries",
)

tasks.named("build") {
    dependsOn(fullTargetBuildTasks)
}
