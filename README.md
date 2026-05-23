# km-io

[![Kotlin Alpha](https://kotl.in/badges/alpha.svg)](https://kotlinlang.org/docs/components-stability.html)
[![GitHub license](https://img.shields.io/github/license/KotlinMania/km-io)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3-blue.svg?logo=kotlin)](http://kotlinlang.org)

A multiplatform Kotlin library providing low-level I/O primitives — `Buffer`,
`Source`, `Sink`, `ByteString`, `FileSystem`, `Path` — built for the broadest practical
Kotlin Multiplatform target matrix. km-io is a hard fork of
[kotlinx-io](https://github.com/Kotlin/kotlinx-io), which in turn descended from
[Okio](https://github.com/square/okio). It does not preserve binary or source
compatibility with either; the namespace, packaging, and target coverage have changed
substantively.

## Overview

km-io is built around `Buffer` — a mutable sequence of bytes.

`Buffer` works like a queue, letting callers read from its head and write to its tail.
`Buffer` provides functions to read and write data of the common scalar built-in types,
and to copy data to or from other `Buffer`s. Depending on the target platform, extension
functions allowing data exchange with platform-specific types are also available
(`java.nio.ByteBuffer` on JVM, `NSData` on Apple, and so on).

A `Buffer` is internally a linked list of segments. Segments reduce allocation cost as a
buffer expands; they also allow zero-copy semantics for reads, writes, and slices by
sharing or transferring segment ownership between buffers.

km-io provides interfaces representing data sources and destinations — `Source` and
`Sink` — and, alongside the *mutable* `Buffer`, an *immutable* sequence of bytes
called `ByteString`.

Experimental filesystem support is shipped in the `io.github.kotlinmania.kmio` package
under `FileSystem`, with a default implementation `SystemFileSystem` and the `Path` type
used to address files and directories. `FileSystem` provides the basic operations
(read, write, list, delete, atomic move, metadata) with platform-appropriate backends
on every supported target.

## Platform coverage

km-io ships across the canonical KotlinMania broad-target matrix — every target Kotlin
Multiplatform actively supports for general-purpose libraries, with deprecated x86_64
Apple variants removed:

| Family | Targets |
|---|---|
| JVM | `jvm` |
| Android | `android` (Kotlin Multiplatform library with `withHostTestBuilder` and `withDeviceTestBuilder`), `androidNativeArm32`, `androidNativeArm64`, `androidNativeX64`, `androidNativeX86` |
| Apple | `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`, `tvosArm64`, `tvosSimulatorArm64`, `watchosArm32`, `watchosArm64`, `watchosDeviceArm64`, `watchosSimulatorArm64` |
| Linux | `linuxX64`, `linuxArm64` |
| Windows | `mingwX64` |
| Web | `js` (browser + Node), `wasmJs` (Node; browser via [`wasi-fs-access`](https://github.com/GoogleChromeLabs/wasi-fs-access) is in progress), `wasmWasi` (Node) |

That's twenty-two distinct compilation targets. Each runs the full common test suite
against a platform-appropriate filesystem backend.

### What km-io changes from kotlinx-io's target matrix

- **All four Android Native variants** (`androidNativeArm32/Arm64/X64/X86`) are
  built and tested as first-class targets rather than as an optional add-on. The
  workspace-canonical configuration-time Android SDK installer (`setupAndroidSdk`) means
  contributors don't need a separately installed Android SDK to build the Android Native
  source set — the SDK is staged under `.android-sdk/` in the project directory.
- **Apple-deprecated x86_64 targets are dropped**: `watchosX64`, `tvosX64`, and
  `macosX64` are not configured. Apple has been retiring x86_64 across its non-macOS
  platforms for several major OS releases, and the deprecation chain reaches macOS-on-x64
  in the foreseeable future. Builders that need to ship to those slices can pin to
  upstream kotlinx-io until they reach end-of-life.
- **`watchosDeviceArm64`** is configured and built. This target covers the newer
  Apple Watch device ABI separately from `watchosArm64`.
- **`wasmJs` browser target is staged**. The Node side of `wasmJs` ships today.
  The browser side is being wired via Google Chrome Labs's `wasi-fs-access`, which
  bridges WASI filesystem syscalls onto the browser's File System Access API (and OPFS),
  so the same `FileSystem` API tested on Node can run unchanged inside Chrome.
- **JVM artifact ships under a single coordinate**. The historical
  `kotlinx-io-bytestring` / `kotlinx-io-core` / `kotlinx-io-okio` split has been
  collapsed into one `km-io` artifact at the root of this repository. Okio adapters
  remain available as opt-in extension functions; they do not pull in an Okio dependency
  unless used.
- **Build infrastructure follows the KotlinMania workspace template**: the canonical
  configuration-time Android SDK installer, the workspace CI workflow shape (`ci.yml`
  with `workflow_dispatch` only, platform workflows with `workflow_call`, a weekly
  `codeql.yml`, and `release[released]`-gated `publish.yml`), a full-target `build`
  gate that links every native test binary and assembles every configured framework,
  and vendored npm tooling patches that close CVE-driven advisories on the JS test
  toolchain.

### Supported host versions

- **Android** 5.0+ (API level 21+).
- **JDK** 21+ for building; the JVM artifact targets `jvmDefault =
  NO_COMPATIBILITY` for `default` interface methods on JVM.
- **Node.js** as required by the current Kotlin Multiplatform JS / Wasm-JS / Wasm-WASI
  toolchains.

## Using in your projects

> km-io is in the `0.1.x` series and marked as alpha. The API surface may change between
> minor versions while the library settles. We avoid gratuitous breakage and call out
> incompatible changes in [`CHANGELOG.md`](CHANGELOG.md).

### Gradle

Make sure that you have `mavenCentral()` in the list of repositories:

```kotlin
repositories {
    mavenCentral()
}
```

Add the library to dependencies:

```kotlin
dependencies {
    implementation("io.github.kotlinmania:km-io:0.1.1")
}
```

In multiplatform projects, add a dependency to the `commonMain` source set:

```kotlin
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation("io.github.kotlinmania:km-io:0.1.1")
            }
        }
    }
}
```

### Maven

Add the library to dependencies. The JVM-only artifact uses the `-jvm` suffix:

```xml
<dependency>
    <groupId>io.github.kotlinmania</groupId>
    <artifactId>km-io-jvm</artifactId>
    <version>0.1.1</version>
</dependency>
```

### Android

km-io is compatible with Android 5.0+ (API level 21+) and is built and tested as a
first-class Kotlin Multiplatform library Android target — both the `main` and the unit
test source sets compile on every contributor's machine without an external Android SDK
install, thanks to the project-local SDK staged by the `setupAndroidSdk` Gradle task on
first build.

### Segment pooling

As an optimization, on some platforms `Buffer`'s segments are pooled, so that every time
a buffer needs a new segment an attempt is made to take a pre-allocated segment from a
pool first. When the pool is empty, only then is a new segment allocated. Every time a
segment is no longer needed, it is returned to the pool (unless the pool is already
full).

Currently, pooling is implemented on JVM (including Android JVM).

The segment pool has a two-level structure: a smaller first-level pool aims to serve
requests as quickly as possible, backed by a larger second-level pool. The first-level
pool sizing is not configurable at the moment. The size of the second-level pool can be
adjusted with the `io.github.kotlinmania.kmio.pool.size.bytes` system property, which
accepts numeric values only. On Android the second-level pool size is `0` by default; in
all other JVM environments it defaults to `4194304` (4 megabytes).

While the size can be changed using the system property, the value is read once during
pool initialization — later changes do not affect the pool size. The property must be
updated before any calls to the km-io API.

On JVM, the easiest way to do this is to supply the property via a JVM command-line
flag:

```
-Dio.github.kotlinmania.kmio.pool.size.bytes=2097152
```

On Android, override `Application.onCreate` and set the property there:

```kotlin
package org.example

import android.app.Application

class MySegmentHungryApp : Application() {
    override fun onCreate() {
        System.setProperty(
            "io.github.kotlinmania.kmio.pool.size.bytes",
            "2097152", // or whatever value suits your case
        )
        super.onCreate()
    }
}
```

Then register the application class in the manifest:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:name="org.example.MySegmentHungryApp"
        ...
    >
    </application>
</manifest>
```

## Contributing

Read the [Contributing Guidelines](CONTRIBUTING.md).

## License

km-io is licensed under the [Apache 2.0 License](LICENSE). See [`NOTICE`](NOTICE) for
the full attribution chain to the upstream projects this work derives from.

## Maintainer

km-io is maintained by **Sydney Renee** ([@sydneyrenee](https://github.com/sydneyrenee))
of **The Solace Project** — `sydney@solace.ofharmony.ai`. The repository lives at
[github.com/KotlinMania/km-io](https://github.com/KotlinMania/km-io).

For questions, bug reports, and feature requests, please use the project's
[issue tracker](https://github.com/KotlinMania/km-io/issues).

## Acknowledgments

km-io stands on the shoulders of two excellent libraries, and would not exist without
the years of work that went into them.

**[kotlinx-io](https://github.com/Kotlin/kotlinx-io)** by JetBrains and the Kotlin
community is the direct ancestor of km-io. The `Buffer` / `Source` / `Sink` / `Segment`
design, the multiplatform filesystem API, the segment pool, and the great majority of
the test suite were authored upstream long before this fork existed. Every member of
the kotlinx-io contributor list has a real share in km-io as well, and we are deeply
grateful for the open-source license under which kotlinx-io was released — without it,
this fork would simply not be possible. If you find km-io useful, please also consider
[starring kotlinx-io](https://github.com/Kotlin/kotlinx-io) and tracking its progress;
the projects will continue to share genealogy even as they diverge.

**[Okio](https://square.github.io/okio/)** by Square — and in particular the years of
work by **[Jesse Wilson](https://github.com/swankjesse)** — served as the foundation
that kotlinx-io was built on. Okio established the `Buffer` / `Source` / `Sink` model
that this entire lineage inherits, and Jesse's guidance during kotlinx-io's early
development shaped much of what survives into km-io today. The Okio adapters in km-io
exist specifically so that Okio-using codebases can interoperate with km-io without
either side compromising.

Thanks also to **Google Chrome Labs** for the
[`wasi-fs-access`](https://github.com/GoogleChromeLabs/wasi-fs-access) project, which
bridges WASI filesystem syscalls onto the browser's File System Access API and is the
basis for km-io's in-progress browser filesystem backend.
