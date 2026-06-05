/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

import kotlinx.benchmark.gradle.JvmBenchmarkTarget
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlinx.benchmark.plugin)
    id("kotlinx-io-clean")
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }

    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":km-io-core"))
                implementation(libs.kotlinx.benchmark.runtime)
            }
        }

    }
}

val nativeBenchmarksEnabled: String by project.parent!!
val nativeBenchmarkTargetName =
    when {
        HostManager.host === KonanTarget.MACOS_ARM64 -> "macosArm64"
        HostManager.hostIsLinux -> "linuxX64"
        HostManager.hostIsMingw -> "mingwX64"
        else -> error("Native benchmarks are not configured for host ${HostManager.host}")
    }

if (nativeBenchmarksEnabled.toBoolean()) {
    kotlin {
        // TODO: consider supporting non-host native targets.
        when (nativeBenchmarkTargetName) {
            "macosArm64" -> macosArm64()
            "linuxX64" -> linuxX64()
            "mingwX64" -> mingwX64()
        }
    }
}

benchmark {
    targets {
        register("jvm") {
            this as JvmBenchmarkTarget
            jmhVersion = libs.versions.jmh.get()
        }
        if (nativeBenchmarksEnabled.toBoolean()) {
            register(nativeBenchmarkTargetName)
        }
    }
}
