/*
 * Copyright 2017-2023 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */
pluginManagement {
    includeBuild("build-logic")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "km-io"

include(":km-io-core")
include(":km-io-benchmarks")
include(":km-io-bytestring")

project(":km-io-core").projectDir = file("./core")
project(":km-io-benchmarks").projectDir = file("./benchmarks")
project(":km-io-bytestring").projectDir = file("./bytestring")