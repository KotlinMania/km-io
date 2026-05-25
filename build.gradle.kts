/*
 * Copyright 2017-2023 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations

plugins {
    id("kotlinx-io-publish") apply false
    id("kotlinx-io-dokka")
    alias(libs.plugins.kover)
}

allprojects {
    properties["DeployVersion"]?.let { version = it }
    repositories {
        mavenCentral()
        google()
    }
}

dependencies {
    kover(project(":km-io-core"))
    kover(project(":km-io-bytestring"))

    dokka(project(":km-io-bytestring"))
    dokka(project(":km-io-core"))
}

kover {
    reports {
        verify {
            rule {
                minBound(95, CoverageUnit.LINE)

                // we allow lower branch coverage, because not all checks in the internal code lead to errors
                minBound(80, CoverageUnit.BRANCH)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Project-local Android SDK auto-installer.
//
// The Android Gradle plugin resolves the SDK location while Gradle builds the
// task graph, before any task executes, so a project-local Android SDK must
// already be installed by the time configuration reaches an android target.
// Every kotlinmania repo installs its own SDK under .android-sdk so CI runs
// don't depend on a runner-provided SDK and developer machines don't pollute
// a shared SDK with whatever this project pins.
//
// The installer below is the canonical pattern shared with anyhow-kotlin,
// dropped into the multi-module km-io root so the single rootProject-level
// local.properties is what every km-io-* subproject's android target reads.
val androidCommandLineToolsRevision = "14742923"
val projectCompileSdk = "34"
val projectAndroidBuildTools = "36.0.0"
val isWindowsHost = System.getProperty("os.name").lowercase().contains("windows")
val androidSdkOsName =
    when {
        isWindowsHost -> "win"
        System.getProperty("os.name").lowercase().contains("mac") -> "mac"
        System.getProperty("os.name").lowercase().contains("linux") -> "linux"
        else -> throw GradleException("Unsupported Android SDK setup OS: ${System.getProperty("os.name")}")
    }
val projectAndroidSdkDir = rootProject.layout.projectDirectory.dir(".android-sdk").asFile
val androidSdkManager = projectAndroidSdkDir.resolve(
    if (isWindowsHost) {
        "cmdline-tools/latest/bin/sdkmanager.bat"
    } else {
        "cmdline-tools/latest/bin/sdkmanager"
    },
)
val androidSdkInstallMarker = projectAndroidSdkDir.resolve(".install-complete")

fun writeAndroidLocalProperties() {
    val sdkDirPropertyValue = projectAndroidSdkDir.absolutePath.replace("\\", "/")
    rootProject.layout.projectDirectory.file("local.properties").asFile.writeText("sdk.dir=$sdkDirPropertyValue\n")
}

fun sdkManagerCommand(vararg args: String): List<String> =
    if (isWindowsHost) {
        listOf("cmd", "/c", androidSdkManager.absolutePath) + args
    } else {
        listOf(androidSdkManager.absolutePath) + args
    }

fun downloadAndroidCommandLineTools() {
    val zipName = "commandlinetools-$androidSdkOsName-${androidCommandLineToolsRevision}_latest.zip"
    val url = "https://dl.google.com/android/repository/$zipName"
    val tmpDir = projectAndroidSdkDir.resolve(".tmp/commandline-tools")
    val zipFile = tmpDir.resolve(zipName)
    val latestDir = projectAndroidSdkDir.resolve("cmdline-tools/latest")

    println("setup-android-sdk: downloading $url")
    tmpDir.deleteRecursively()
    tmpDir.mkdirs()

    try {
        URI(url).toURL().openStream().use { input ->
            Files.copy(input, zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        latestDir.deleteRecursively()
        latestDir.mkdirs()
        val canonicalLatestDir = latestDir.canonicalFile.toPath()

        ZipInputStream(zipFile.inputStream().buffered()).use { zipInput ->
            generateSequence { zipInput.nextEntry }.forEach { entry ->
                val relativeName = entry.name.removePrefix("cmdline-tools/").trimStart('/')
                if (relativeName.isNotEmpty()) {
                    val target = latestDir.resolve(relativeName).canonicalFile
                    if (!target.toPath().startsWith(canonicalLatestDir)) {
                        throw GradleException("Refusing to extract Android SDK entry outside $latestDir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile.mkdirs()
                        Files.copy(zipInput, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        if (!isWindowsHost && relativeName.startsWith("bin/")) {
                            target.setExecutable(true)
                        }
                    }
                }
                zipInput.closeEntry()
            }
        }

        if (!isWindowsHost) {
            androidSdkManager.setExecutable(true)
        }
    } finally {
        tmpDir.deleteRecursively()
    }
}

fun installProjectAndroidSdk(execOperations: ExecOperations) {
    if (androidSdkInstallMarker.exists() && androidSdkManager.exists()) {
        writeAndroidLocalProperties()
        println("setup-android-sdk: SDK already installed at $projectAndroidSdkDir")
        return
    }

    if (!androidSdkManager.exists()) {
        downloadAndroidCommandLineTools()
    }

    println("setup-android-sdk: accepting licenses")
    val licenseAnswers = "y\n".repeat(200).toByteArray(Charsets.UTF_8)
    val licenseResult = execOperations.exec {
        commandLine(sdkManagerCommand("--sdk_root=${projectAndroidSdkDir.absolutePath}", "--licenses"))
        standardInput = ByteArrayInputStream(licenseAnswers)
        isIgnoreExitValue = true
    }
    if (licenseResult.exitValue != 0) {
        throw GradleException("Android SDK license acceptance failed with exit code ${licenseResult.exitValue}")
    }

    println("setup-android-sdk: installing platform-tools, android-$projectCompileSdk, build-tools;$projectAndroidBuildTools")
    val installLog = projectAndroidSdkDir.resolve("sdkmanager-install.log")
    installLog.parentFile.mkdirs()
    installLog.outputStream().use { output ->
        val installResult = execOperations.exec {
            commandLine(
                sdkManagerCommand(
                    "--sdk_root=${projectAndroidSdkDir.absolutePath}",
                    "platform-tools",
                    "platforms;android-$projectCompileSdk",
                    "build-tools;$projectAndroidBuildTools",
                ),
            )
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        if (installResult.exitValue != 0) {
            throw GradleException(
                "Android SDK package install failed with exit code ${installResult.exitValue}. " +
                    "Install log:\n${installLog.readText()}",
            )
        }
    }
    println("setup-android-sdk: install log at $installLog")

    writeAndroidLocalProperties()
    androidSdkInstallMarker.writeText("")
    println("setup-android-sdk: done")
    println("  SDK at:     $projectAndroidSdkDir")
    println("  configured: local.properties -> $projectAndroidSdkDir")
}

val androidSdkExecOperations = serviceOf<ExecOperations>()
installProjectAndroidSdk(androidSdkExecOperations)

tasks.register("setupAndroidSdk") {
    group = "setup"
    description = "Downloads and configures the project-local Android SDK."
    doLast {
        installProjectAndroidSdk(androidSdkExecOperations)
    }
}

val swiftExportBuildDir = project(":km-io-core").layout.buildDirectory.dir("swift-test")
val swiftExportPackageDir = project(":km-io-core").layout.buildDirectory.dir("SPMPackage/macosArm64/Debug")
val swiftExportEnvironment = mapOf(
    "BUILT_PRODUCTS_DIR" to swiftExportBuildDir.get().asFile.absolutePath,
    "TARGET_BUILD_DIR" to swiftExportBuildDir.get().asFile.absolutePath,
    "SDK_NAME" to "macosx",
    "CONFIGURATION" to "Debug",
    "ARCHS" to "arm64",
    "FRAMEWORKS_FOLDER_PATH" to "Frameworks",
    "MACOSX_DEPLOYMENT_TARGET" to "14.0",
    "DEPLOYMENT_TARGET_SETTING_NAME" to "MACOSX_DEPLOYMENT_TARGET",
)

val buildSwiftExportForSwiftTest = tasks.register<Exec>("buildSwiftExportForSwiftTest") {
    group = "verification"
    description = "Builds the Kotlin Swift Export SPM package for the local Swift test harness."
    commandLine(
        "./gradlew",
        "--no-daemon",
        "--console=plain",
        "--no-configuration-cache",
        ":km-io-core:embedSwiftExportForXcode",
    )
    environment(swiftExportEnvironment)
    outputs.dir(swiftExportBuildDir)
    outputs.dir(swiftExportPackageDir)
    outputs.upToDateWhen { false }
}

val swiftExportTest = tasks.register<Exec>("swiftExportTest") {
    group = "verification"
    description = "Runs swift test against the Kotlin Swift Export package."
    dependsOn(buildSwiftExportForSwiftTest)
    workingDir(layout.projectDirectory.dir("swift-test-harness"))
    commandLine("swift", "test")
    outputs.upToDateWhen { false }
}

tasks.register("test") {
    group = "verification"
    description = "Runs Swift Export tests for the locally generated Swift package."
    dependsOn(swiftExportTest)
}

tasks.named("build") {
    dependsOn(swiftExportTest)
}
