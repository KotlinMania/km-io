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
