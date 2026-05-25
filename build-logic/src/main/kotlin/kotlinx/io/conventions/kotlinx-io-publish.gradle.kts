/*
 * Copyright 2017-2023 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

import org.gradle.api.GradleException

plugins {
    id("com.vanniktech.maven.publish")
}

val publishedArtifactId =
    if (project.name == "km-io-core") {
        "km-io"
    } else {
        project.name
    }

mavenPublishing {
    publishToMavenCentral()
    if (project.hasSigningKey()) {
        signAllPublications()
    }

    coordinates(project.group.toString(), publishedArtifactId, project.version.toString())

    pom {
        name.set(publishedArtifactId)
        description.set("Multiplatform Kotlin IO primitives and ByteString support")
        inceptionYear.set("2026")
        url.set("https://github.com/KotlinMania/km-io")

        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("sydneyrenee")
                name.set("Sydney Renee")
                email.set("sydney@solace.ofharmony.ai")
                url.set("https://github.com/sydneyrenee")
            }
        }

        scm {
            url.set("https://github.com/KotlinMania/km-io")
            connection.set("scm:git:git://github.com/KotlinMania/km-io.git")
            developerConnection.set("scm:git:ssh://github.com/KotlinMania/km-io.git")
        }
    }
}

tasks.matching {
    it.name == "publishToMavenLocal" || it.name.endsWith("PublicationToMavenLocal")
}.configureEach {
    enabled = false
}

val centralPublishTaskNames = setOf(
    "publishToMavenCentral",
    "publishAllPublicationsToMavenCentralRepository",
    "publishAndReleaseToMavenCentral",
)
val unqualifiedCentralPublishRequested =
    gradle.startParameter.taskNames.any { it in centralPublishTaskNames }
val centralPublishProjectsRequested =
    gradle.startParameter.taskNames
        .mapNotNull { taskName ->
            centralPublishTaskNames
                .firstOrNull { taskName.endsWith(":$it") }
                ?.let { taskName.substringBeforeLast(":") }
        }
        .distinct()
val combinedCentralPublishRequested = centralPublishProjectsRequested.size > 1

if (unqualifiedCentralPublishRequested || combinedCentralPublishRequested) {
    val guardRegisteredProperty = "kmIoCentralPublishGuardRegistered"
    val rootExtraProperties = rootProject.extensions.extraProperties
    if (!rootExtraProperties.has(guardRegisteredProperty)) {
        rootExtraProperties.set(guardRegisteredProperty, true)
        gradle.taskGraph.whenReady {
            throw GradleException(
                "Do not publish multiple km-io modules in one Gradle invocation. " +
                    "Run each publishable module separately so Central Portal deployments " +
                    "are named <group>-<artifact>-<version>: " +
                    "./gradlew :km-io-bytestring:publishAndReleaseToMavenCentral and " +
                    "./gradlew :km-io-core:publishAndReleaseToMavenCentral."
            )
        }
    }
}

fun Project.hasSigningKey(): Boolean =
    !(findProperty("signingInMemoryKey") as? String).isNullOrBlank() ||
        !System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey").isNullOrBlank()
