/*
 * Copyright 2017-2025 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.power.assert.plugin)
    implementation(libs.dokka.gradle.plugin)
    implementation(libs.animalsniffer.gradle.plugin)
    implementation(libs.maven.publish.gradle.plugin)
    // Needed so the kotlinx-io-multiplatform convention plugin can apply the
    // com.android.kotlin.multiplatform.library plugin id at the consumer site.
    implementation(libs.android.kmp.library.gradle.plugin)
}

kotlin {
    // The Android KMP library plugin (and AGP 9.x in general) require the
    // Gradle build to run on JDK 17. The multi-release-toolchain catalog
    // entry (17) is the same JDK used for the convention plugin's existing
    // Java-9 module-info compilation, so a single toolchain works for both.
    jvmToolchain(JavaLanguageVersion.of(libs.versions.multi.release.toolchain.get()).asInt())
}
