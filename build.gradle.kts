import org.gradle.api.GradleException
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.vanniktech)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    id("kotlinx-io-publish") apply false
    id("kotlinx-io-dokka")
}

allprojects {
    properties["DeployVersion"]?.let { version = it }
    repositories {
        mavenCentral()
        google()
    }
}

subprojects {
    // Propagate Android SDK setup to subprojects that compile Android targets.
    // The root project's ensureAndroidSdk task handles the download/install;
    // subprojects just need to depend on it so the SDK is on disk before their
    // android {} blocks resolve.
    afterEvaluate {
        tasks.matching { it.name == "compileAndroidMain" }.configureEach {
            dependsOn(rootProject.tasks.named("ensureAndroidSdk"))
        }
    }
}

dependencies {
    kover(project(":km-io-core"))
    kover(project(":km-io-bytestring"))

    dokka(project(":km-io-bytestring"))
    dokka(project(":km-io-core"))
}

group = providers.gradleProperty("project.group").getOrElse("io.github.kotlinmania")
version = providers.gradleProperty("project.version").getOrElse("0.1.0-SNAPSHOT")
val frameworkName = providers.gradleProperty("project.frameworkName").getOrElse("KmIo")
val projectNamespace = providers.gradleProperty("project.namespace").getOrElse("io.github.kotlinmania.io")
val kotlinVersion = providers.gradleProperty("versions.kotlin").getOrElse("2.3.21")
val isCodeqlBuild = providers.gradleProperty("kotlinmania.codeql").map(String::toBoolean).getOrElse(false)
val commonMainBundleName = providers.gradleProperty("project.dependencies.commonMainBundle").get()
val commonMainDependencyBundle =
    extensions
        .getByType(VersionCatalogsExtension::class.java)
        .named("libs")
        .findBundle(commonMainBundleName)
        .orElseThrow { GradleException("Missing libs bundle '$commonMainBundleName'") }

val commonOptIns =
    listOf(
        "kotlin.time.ExperimentalTime",
        "kotlin.concurrent.atomics.ExperimentalAtomicApi",
        "kotlin.ExperimentalUnsignedTypes",
    )

// ============================================================================
// Detekt + Ktlint
// ============================================================================
detekt {
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
    source.setFrom(files("src"))
    config.setFrom(files("detekt.yml"))
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
        txt.required.set(false)
        xml.required.set(false)
    }
}

ktlint {
    debug.set(false)
    verbose.set(false)
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.SARIF)
    }
    filter {
        exclude("**/build/**")
        include("**/src/**/kotlin/**")
    }
}

// ============================================================================
// JS / Wasm toolchain pins
// ============================================================================
val nodeVersion = providers.gradleProperty("node.version").getOrElse("24.15.0")
val wasmNodeVersion = providers.gradleProperty("wasm.node.version").getOrElse(nodeVersion)
val yarnVersion = providers.gradleProperty("yarn.version").getOrElse("1.22.22")
val wasmYarnVersion = providers.gradleProperty("wasm.yarn.version").getOrElse(yarnVersion)

@Suppress("UNCHECKED_CAST")
val webpackVersion: String =
    (groovy.json.JsonSlurper().parse(rootProject.file("kotlin-js-store/package.json")) as Map<String, Any>)
        .let { it["dependencies"] as Map<String, Any> }["webpack"] as String

afterEvaluate {
    rootProject.extensions.configure<NodeJsEnvSpec>("kotlinNodeJsSpec") { version.set(nodeVersion) }
    rootProject.extensions.configure<WasmNodeJsEnvSpec>("kotlinWasmNodeJsSpec") { version.set(wasmNodeVersion) }
    rootProject.extensions.configure<YarnRootEnvSpec>("kotlinYarnSpec") { version.set(yarnVersion) }
    rootProject.extensions.configure<WasmYarnRootEnvSpec>("kotlinWasmYarnSpec") { version.set(wasmYarnVersion) }

    rootProject.extensions.configure<YarnRootExtension>("kotlinYarn") {
        project.properties
            .filterKeys { it.startsWith("yarn.resolution.") }
            .forEach { (key, value) ->
                val pkg = key.removePrefix("yarn.resolution.")
                val ver = value as? String ?: return@forEach
                resolution(pkg, ver)
                resolution("**/$pkg", ver)
            }
        resolution("webpack", webpackVersion)
        resolution("**/webpack", webpackVersion)
    }

    val patchedKarmaWebpackPackage =
        rootProject.layout.projectDirectory
            .dir("gradle/npm/karma-webpack")
            .asFile.absolutePath
            .replace("\\", "/")

    @Suppress("DEPRECATION")
    rootProject.extensions.configure<NodeJsRootExtension>("kotlinNodeJs") {
        versions.webpack.version = webpackVersion
        versions.webpackCli.version = providers.gradleProperty("node.webpackCli.version").getOrElse("7.0.2")
        versions.karma.version = providers.gradleProperty("node.karma.version").getOrElse("npm:karma-maintained@6.4.7")
        versions.karmaWebpack.version = "file:$patchedKarmaWebpackPackage"
        versions.mocha.version = providers.gradleProperty("node.mocha.version").getOrElse("12.0.0-beta-10")
        versions.kotlinWebHelpers.version = providers.gradleProperty("node.kotlinWebHelpers.version").getOrElse("3.1.0")
    }
}

// ============================================================================
// Android SDK installer
// ============================================================================
val androidCommandLineToolsRevision =
    providers.gradleProperty("android.commandLineTools.revision").getOrElse("14742923")
val projectCompileSdk = providers.gradleProperty("android.compileSdk").getOrElse("34")
val projectAndroidBuildTools = providers.gradleProperty("android.buildTools").getOrElse("36.0.0")
val osName = providers.systemProperty("os.name").get().lowercase()
val isWindowsHost = "windows" in osName
val isMacHost = "mac" in osName
val androidSdkOsName =
    when {
        isWindowsHost -> "win"
        isMacHost -> "mac"
        "linux" in osName -> "linux"
        else -> throw GradleException("Unsupported Android SDK setup OS: ${providers.systemProperty("os.name").get()}")
    }
val projectAndroidSdkDir = layout.projectDirectory.dir(".android-sdk").asFile
val androidSdkManager =
    projectAndroidSdkDir.resolve(
        if (isWindowsHost) {
            "cmdline-tools/latest/bin/sdkmanager.bat"
        } else {
            "cmdline-tools/latest/bin/sdkmanager"
        },
    )
val androidSdkInstallMarker = projectAndroidSdkDir.resolve(".install-complete")
val requiredAndroidSdkPackageDirs =
    listOf(
        projectAndroidSdkDir.resolve("platform-tools"),
        projectAndroidSdkDir.resolve("platforms/android-$projectCompileSdk"),
        projectAndroidSdkDir.resolve("build-tools/$projectAndroidBuildTools"),
    )

fun writeAndroidLocalProperties() {
    val sdkDirPropertyValue = projectAndroidSdkDir.absolutePath.replace("\\", "/")
    layout.projectDirectory
        .file("local.properties")
        .asFile
        .writeText("sdk.dir=$sdkDirPropertyValue\n")
}

fun isProjectAndroidSdkInstalled(): Boolean =
    androidSdkInstallMarker.exists() &&
        androidSdkManager.exists() &&
        requiredAndroidSdkPackageDirs.all { it.exists() }

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
                        if (!isWindowsHost && relativeName.startsWith("bin/")) target.setExecutable(true)
                    }
                }
                zipInput.closeEntry()
            }
        }
        if (!isWindowsHost) androidSdkManager.setExecutable(true)
    } finally {
        tmpDir.deleteRecursively()
    }
}

fun installProjectAndroidSdk(execOperations: ExecOperations) {
    if (isProjectAndroidSdkInstalled()) {
        writeAndroidLocalProperties()
        println("setup-android-sdk: SDK already installed at $projectAndroidSdkDir")
        return
    }
    if (!androidSdkManager.exists()) downloadAndroidCommandLineTools()
    println("setup-android-sdk: accepting licenses")
    val licenseAnswers = "y\n".repeat(200).toByteArray(Charsets.UTF_8)
    val licenseResult =
        execOperations.exec {
            commandLine(sdkManagerCommand("--sdk_root=${projectAndroidSdkDir.absolutePath}", "--licenses"))
            standardInput = ByteArrayInputStream(licenseAnswers)
            isIgnoreExitValue = true
        }
    if (licenseResult.exitValue != 0) {
        throw GradleException("Android SDK license acceptance failed with exit code ${licenseResult.exitValue}")
    }
    println(
        "setup-android-sdk: installing platform-tools, android-$projectCompileSdk, build-tools;$projectAndroidBuildTools",
    )
    val installLog = projectAndroidSdkDir.resolve("sdkmanager-install.log")
    installLog.parentFile.mkdirs()
    installLog.outputStream().use { output ->
        val installResult =
            execOperations.exec {
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
    writeAndroidLocalProperties()
    androidSdkInstallMarker.writeText("")
    println("setup-android-sdk: done; SDK at $projectAndroidSdkDir")
}

writeAndroidLocalProperties()

val ensureAndroidSdk by tasks.registering {
    group = "setup"
    description = "Ensures the project-local Android SDK is installed (idempotent)."
    onlyIf("Android SDK already installed at $projectAndroidSdkDir") { !isProjectAndroidSdkInstalled() }
    doLast {
        installProjectAndroidSdk(serviceOf())
    }
}

tasks.matching { it.name == "compileAndroidMain" }.configureEach {
    dependsOn(ensureAndroidSdk)
}

val jvmToolchainVersion = providers.gradleProperty("jvm.toolchain").getOrElse("21").toInt()

// ============================================================================
// kotlin { …}
// ============================================================================
kotlin {
    jvmToolchain(jvmToolchainVersion)

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

    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
        allWarningsAsErrors.set(!isCodeqlBuild)
        optIn.addAll(commonOptIns)
        freeCompilerArgs.add("-Xexpect-actual-classes")
        freeCompilerArgs.add("-Xreturn-value-checker=full")
        freeCompilerArgs.add("-XXLanguage:+UnnamedLocalVariables")
    }

    val xcf = XCFramework(frameworkName)
    val frameworkBundleId = projectNamespace

    fun KotlinNativeTarget.addToXcf(static: Boolean = false) {
        binaries.framework {
            baseName = frameworkName
            if (static) isStatic = true
            xcf.add(this)
            binaryOption("bundleId", frameworkBundleId)
        }
    }

    macosArm64 { addToXcf() }
    iosArm64 { addToXcf(static = true) }
    iosSimulatorArm64 { addToXcf(static = true) }
    iosX64 { addToXcf(static = true) }
    tvosArm64 { addToXcf() }
    tvosSimulatorArm64 { addToXcf() }
    watchosArm64 { addToXcf() }
    watchosDeviceArm64 { addToXcf() }
    watchosSimulatorArm64 { addToXcf() }

    linuxX64()
    linuxArm64()
    mingwX64()

    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    js {
        nodejs {
            testTask {
                useMocha {
                    timeout = "30s"
                }
            }
        }
        browser {
            testTask {
                useMocha {
                    timeout = "30s"
                }
                filter.setExcludePatterns("*SmokeFileTest*")
            }
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                filter.setExcludePatterns("*SmokeFileTest*")
            }
        }
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    swiftExport {
        moduleName = frameworkName
        flattenPackage = projectNamespace
        @OptIn(org.jetbrains.kotlin.gradle.swiftexport.ExperimentalSwiftExportDsl::class)
        configure {
            settings.put("enableCoroutinesSupport", "true")
        }
    }

    android {
        namespace = projectNamespace
        compileSdk = projectCompileSdk.toInt()
        minSdk = providers.gradleProperty("android.minSdk").getOrElse("24").toInt()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder { sourceSetTreeName = "test" }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(jvmToolchainVersion.toString()))
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(commonMainDependencyBundle)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        val sharedAndroidTestSources = files("src/androidTest/kotlin")
        findByName("androidHostTest")?.kotlin?.srcDir(sharedAndroidTestSources)
        findByName("androidDeviceTest")?.kotlin?.srcDir(sharedAndroidTestSources)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name.startsWith("compileSwiftExport")) {
        compilerOptions.allWarningsAsErrors.set(false)
    }
}

// ============================================================================
// Test logging
// ============================================================================
tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events(
            TestLogEvent.STARTED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED,
            TestLogEvent.FAILED,
            TestLogEvent.STANDARD_OUT,
            TestLogEvent.STANDARD_ERROR,
        )
        exceptionFormat = TestExceptionFormat.FULL
        showCauses = true
        showExceptions = true
        showStackTraces = true
        showStandardStreams = true
    }
}

// ============================================================================
// Swift Export smoke test
// ============================================================================
tasks.register("swiftExportSmokeTest") {
    group = "verification"
    description = "Builds the Swift Export SPM package and runs swift test against it."
    outputs.upToDateWhen { false }
    onlyIf {
        System.getProperty("os.name").contains("Mac", ignoreCase = true)
    }

    doLast {
        val execOperations = serviceOf<ExecOperations>()
        val swiftBuildDir =
            layout.buildDirectory
                .dir("swift-test")
                .get()
                .asFile
                .absolutePath
        execOperations
            .exec {
                workingDir = projectDir
                commandLine(
                    "./gradlew",
                    "embedSwiftExportForXcode",
                    "--no-configuration-cache",
                    "--no-daemon",
                    "--console=plain",
                )
                environment(
                    mapOf(
                        "BUILT_PRODUCTS_DIR" to swiftBuildDir,
                        "TARGET_BUILD_DIR" to swiftBuildDir,
                        "SDK_NAME" to "macosx",
                        "CONFIGURATION" to "Debug",
                        "ARCHS" to "arm64",
                        "FRAMEWORKS_FOLDER_PATH" to "Frameworks",
                        "MACOSX_DEPLOYMENT_TARGET" to "14.0",
                        "DEPLOYMENT_TARGET_SETTING_NAME" to "MACOSX_DEPLOYMENT_TARGET",
                    ),
                )
            }.assertNormalExitValue()

        val generatedPackageSwift =
            layout.buildDirectory.file("SPMPackage/macosArm64/Debug/Package.swift").get().asFile
        if (generatedPackageSwift.exists()) {
            val text = generatedPackageSwift.readText()
            if (!text.contains("platforms:")) {
                generatedPackageSwift.writeText(
                    text.replaceFirst(
                        Regex("(name:\\s*\"[^\"]*\",)"),
                        "$1\n    platforms: [.macOS(.v14)],",
                    ),
                )
            }
        }

        execOperations
            .exec {
                workingDir = layout.projectDirectory.dir("swift-test-harness").asFile
                commandLine("swift", "test")
            }.assertNormalExitValue()
    }
}

tasks.named("check") {
    dependsOn(tasks.withType<io.gitlab.arturbosch.detekt.Detekt>())
    dependsOn(tasks.named("ktlintCheck"))
    dependsOn("testAndroidHostTest")
    dependsOn("swiftExportSmokeTest")
}

// ============================================================================
// Maven Central publishing
// ============================================================================
mavenPublishing {
    publishToMavenCentral()
    if (project.findProperty("RELEASE_SIGNING_ENABLED") != "false") {
        signAllPublications()
    }
    val projectName = providers.gradleProperty("project.name").getOrElse("km-io")
    coordinates(group.toString(), projectName, version.toString())
    pom {
        name.set(projectName)
        description.set(providers.gradleProperty("project.pom.description").getOrElse(""))
        inceptionYear.set("2026")
        url.set("https://github.com/KotlinMania/$projectName")
        licenses {
            license {
                name.set(providers.gradleProperty("project.pom.licenseName").getOrElse("Apache-2.0"))
                url.set(
                    providers.gradleProperty("project.pom.licenseUrl").getOrElse("https://www.apache.org/licenses/LICENSE-2.0.txt"),
                )
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
            url.set("https://github.com/KotlinMania/$projectName")
            connection.set("scm:git:git://github.com/KotlinMania/$projectName.git")
            developerConnection.set("scm:git:ssh://github.com/KotlinMania/$projectName.git")
        }
    }
}

// ============================================================================
// CodeQL extraction
// ============================================================================
val codeqlKotlincScope =
    configurations.dependencyScope("codeqlKotlinc") {
        description = "Kotlin compiler (CodeQL extraction target only)"
    }
val codeqlSourceScope =
    configurations.dependencyScope("codeqlSourceClasspath") {
        description = "Runtime classpath for CodeQL extraction of commonMain sources"
    }
val codeqlAarScope =
    configurations.dependencyScope("codeqlAndroidAar") {
        description = "Android AAR artifacts for CodeQL dependency classpath extraction"
    }
val codeqlKotlincFiles =
    configurations.resolvable("codeqlKotlincFiles") {
        extendsFrom(codeqlKotlincScope.get())
    }
val codeqlSourceFiles =
    configurations.resolvable("codeqlSourceFiles") {
        extendsFrom(codeqlSourceScope.get())
    }
val codeqlAarFiles =
    configurations.resolvable("codeqlAarFiles") {
        extendsFrom(codeqlAarScope.get())
    }

val codeqlLanguageVersion =
    providers
        .gradleProperty("kotlin.languageVersion")
        .getOrElse(kotlinVersion.split('.').take(2).joinToString("."))
val codeqlApiVersion = providers.gradleProperty("kotlin.apiVersion").getOrElse(codeqlLanguageVersion)
val codeqlKotlinSourceSetNames =
    providers
        .gradleProperty("project.codeql.kotlinSourceSets")
        .getOrElse("commonMain")
        .splitToSequence(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()
val codeqlKotlinCommonSourceSetNames =
    providers
        .gradleProperty("project.codeql.kotlinCommonSourceSets")
        .getOrElse("commonMain")
        .splitToSequence(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

dependencies {
    val codeqlKotlinVersion = providers.gradleProperty("codeql.kotlin.version").getOrElse(kotlinVersion)
    add("codeqlKotlinc", "org.jetbrains.kotlin:kotlin-compiler-embeddable:$codeqlKotlinVersion")

    providers
        .gradleProperty("project.dependencies.codeqlSourceClasspath")
        .getOrElse("")
        .splitToSequence(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { add("codeqlSourceClasspath", it) }

    providers
        .gradleProperty("project.dependencies.codeqlAndroidAar")
        .getOrElse("")
        .splitToSequence(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { add("codeqlAndroidAar", it) }
}

tasks.register<JavaExec>("codeqlCompileJvm") {
    description =
        "Compile ${codeqlKotlinSourceSetNames.joinToString(",")} Kotlin sources " +
            "with kotlinc $codeqlLanguageVersion for CodeQL Java/Kotlin extraction."
    group = "verification"
    classpath(codeqlKotlincFiles)
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    val fs = serviceOf<org.gradle.api.file.FileSystemOperations>()
    val archives = serviceOf<org.gradle.api.file.ArchiveOperations>()
    val outDir = layout.buildDirectory.dir("classes/kotlin/codeql-jvm")
    val aarExtractDir = layout.buildDirectory.dir("codeql/android-aar")
    val commonSources =
        files(
            codeqlKotlinCommonSourceSetNames.map { sourceSetName ->
                fileTree("src/$sourceSetName/kotlin") { include("**/*.kt") }
            },
        )
    val sources =
        files(
            codeqlKotlinSourceSetNames.map { sourceSetName ->
                fileTree("src/$sourceSetName/kotlin") { include("**/*.kt") }
            },
        )
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(commonSources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(codeqlSourceFiles).withNormalizer(ClasspathNormalizer::class.java)
    inputs.files(codeqlAarFiles).withNormalizer(ClasspathNormalizer::class.java)
    outputs.dir(outDir)
    outputs.dir(aarExtractDir)
    doFirst {
        outDir.get().asFile.mkdirs()
        val extractedJars =
            codeqlAarFiles.get().resolve().mapNotNull { aar ->
                val extractTarget = aarExtractDir.get().asFile.resolve(aar.nameWithoutExtension)
                extractTarget.mkdirs()
                fs.copy {
                    from(archives.zipTree(aar))
                    include("classes.jar")
                    into(extractTarget)
                }
                extractTarget.resolve("classes.jar").takeIf { it.exists() }
            }
        val fullClasspath =
            (codeqlSourceFiles.get().resolve() + extractedJars)
                .joinToString(File.pathSeparator) { it.absolutePath }
        val commonSourceFiles = commonSources.files.toMutableList()
        require(commonSourceFiles.isNotEmpty()) {
            "project.codeql.kotlinCommonSourceSets must resolve to at least one Kotlin source file"
        }
        val sourceFiles = sources.files.toMutableList()
        require(sourceFiles.isNotEmpty()) {
            "project.codeql.kotlinSourceSets must resolve to at least one Kotlin source file"
        }
        args = listOf(
            "-d",
            outDir.get().asFile.absolutePath,
            "-classpath",
            fullClasspath,
            "-jvm-target",
            jvmToolchainVersion.toString(),
            "-no-stdlib",
            "-no-reflect",
            "-language-version",
            codeqlLanguageVersion,
            "-api-version",
            codeqlApiVersion,
            "-Xmulti-platform",
            "-Xcommon-sources=${commonSourceFiles.joinToString(",") { it.absolutePath }}",
            "-Xexpect-actual-classes",
        ) + commonOptIns.flatMap { listOf("-opt-in", it) } + sourceFiles.map { it.absolutePath }
    }
}

// ============================================================================
// Tasks
// ============================================================================

tasks.register("setupAndroidSdk") {
    group = "setup"
    description = "Downloads and configures the project-local Android SDK. (Alias for ensureAndroidSdk)"
    dependsOn("ensureAndroidSdk")
}

tasks.register("hostTests") {
    group = "verification"
    description = "Runs the required real test suite (jvm, macosArm64, js, wasmJs, wasmWasi, android host)."
    dependsOn(
        "jvmTest",
        "macosArm64Test",
        "jsNodeTest",
        "wasmJsNodeTest",
        "wasmWasiNodeTest",
        "testAndroidHostTest",
    )
}

tasks.register("test") {
    group = "verification"
    description = "Runs the project test suite (alias for hostTests + swift export smoke test)."
    dependsOn(
        "hostTests",
        "swiftExportSmokeTest",
    )
}

tasks.named("wasmWasiNodeTest") {
    // TODO: remove once https://youtrack.jetbrains.com/issue/KT-65179 solved
    val rootName = rootProject.name
    val moduleName = project.name
    doFirst {
        val entryName =
            if (moduleName == rootName) {
                rootName
            } else {
                "$rootName-$moduleName"
            }
        val templateFile = layout.projectDirectory.file("core/wasmWasi/test/test-driver.mjs.template").asFile
        val driverFile =
            layout.buildDirectory.file(
                "compileSync/wasmWasi/test/testDevelopmentExecutable/kotlin/$entryName-test.mjs",
            )

        fun File.mkdirsAndEscape(): String {
            mkdirs()
            return absolutePath.replace("\\", "\\\\")
        }

        val tmpDir = temporaryDir.resolve("km-io-wasi-test").mkdirsAndEscape()
        val tmpDir2 = temporaryDir.resolve("km-io-wasi-test-2").mkdirsAndEscape()

        val newDriver =
            templateFile
                .readText()
                .replace("<SYSTEM_TEMP_DIR>", tmpDir, false)
                .replace("<SYSTEM_TEMP_DIR2>", tmpDir2, false)
                .replace("<WASM_FILE>", "$entryName-test.wasm", false)

        driverFile.get().asFile.writeText(newDriver)
    }
}

// ============================================================================
// `build` aggregate
// ============================================================================
val nativeTargetNames =
    listOf(
        "androidNativeArm32",
        "androidNativeArm64",
        "androidNativeX64",
        "androidNativeX86",
        "iosArm64",
        "iosSimulatorArm64",
        "iosX64",
        "linuxArm64",
        "linuxX64",
        "macosArm64",
        "mingwX64",
        "tvosArm64",
        "tvosSimulatorArm64",
        "watchosArm64",
        "watchosDeviceArm64",
        "watchosSimulatorArm64",
    )

val fullTargetBuildTaskNames =
    buildSet {
        addAll(
            listOf(
                "compileAndroidMain",
                "compileAndroidHostTest",
                "compileAndroidDeviceTest",
                "assembleAndroidMain",
                "assembleUnitTest",
                "assembleAndroidTest",
                "assembleAndroidDeviceTest",
                "jvmMainClasses",
                "jvmTestClasses",
                "jsMainClasses",
                "jsTestClasses",
                "wasmJsMainClasses",
                "wasmJsTestClasses",
                "wasmWasiMainClasses",
                "wasmWasiTestClasses",
                "swiftExportSmokeTest",
                "assemble${frameworkName}XCFramework",
            ),
        )
        for (target in nativeTargetNames) {
            add("${target}Binaries")
            add("${target}TestBinaries")
        }
    }

tasks.named("build") {
    dependsOn(fullTargetBuildTaskNames)
}
