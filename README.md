# km-io

[![Kotlin Alpha](https://kotl.in/badges/alpha.svg)](https://kotlinlang.org/docs/components-stability.html)
[![GitHub license](https://img.shields.io/github/license/KotlinMania/km-io)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3-blue.svg?logo=kotlin)](http://kotlinlang.org)

A multiplatform Kotlin library providing basic IO primitives. `km-io` is a fork of
[kotlinx-io](https://github.com/Kotlin/kotlinx-io), which is in turn based on
[Okio](https://github.com/square/okio) but does not preserve backward compatibility with it.

## Overview
**km-io** is built around `Buffer` - a mutable sequence of bytes.

`Buffer` works like a queue, allowing to read data from its head or to write data to its tail.
`Buffer` provides functions to read and write data of different built-in types, and to copy data to or from other `Buffer`s.
Depending on the target platform, extension functions allowing data exchange with platform-specific types are also available.

A `Buffer` consists of segments organized as a linked list: segments allow reducing memory allocations during the buffer's expansion and copy,
with the latter achieved by delegating or sharing the ownership over the underlying buffer's segments with other buffers.

**km-io** provides interfaces representing data sources and destinations - `Source` and `Sink`,
and in addition to the *mutable* `Buffer` the library also provides an *immutable* sequence of bytes - `ByteString`.

An experimental filesystem support is shipped under the `io.github.kotlinmania.io.files` package,
which includes the `FileSystem` interface and its default implementation - `SystemFileSystem`.

`FileSystem` provides basic operations for working with files and directories, which are represented by yet another class under the same package - `Path`.

There are several `km-io` modules:
- [km-io-bytestring](./bytestring) - provides `ByteString`.
- [km-io-core](./core) - provides IO primitives (`Buffer`, `Source`, `Sink`), filesystems support, depends on `km-io-bytestring`.
- [km-io-okio](./integration/km-io-okio) - bridges `km-io` and `Okio` `ByteString`, `io.github.kotlinmania.io.RawSource` and `okio.Source`, `io.github.kotlinmania.io.RawSink` and `okio.Sink`.

## Using in your projects

> Note that the library is experimental, and the API is subject to change.

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
    implementation("io.github.kotlinmania:km-io-core:0.1.1")
}
```

In multiplatform projects, add a dependency to the `commonMain` source set dependencies:
```kotlin
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation("io.github.kotlinmania:km-io-core:0.1.1")
            }
        }
    }
}
```

### Maven

Add the library to dependencies:
```xml
<dependency>
    <groupId>io.github.kotlinmania</groupId>
    <artifactId>km-io-core-jvm</artifactId>
    <version>0.1.1</version>
</dependency>
```

### JPMS support

On JVM, `km-io` supports Java Modules:
- `km-io-bytestring` library provides `io.github.kotlinmania.io.bytestring` module;
- `km-io-core` library provides `io.github.kotlinmania.io.core` module.
- `km-io-okio` library provides `io.github.kotlinmania.io.okio` module.

Read [this](https://kotlinlang.org/docs/gradle-configure-project.html#configure-with-java-modules-jpms-enabled) article
for details on how to configure a Gradle project to utilize JPMS.

### Android

`km-io` is not tested on Android on a regular basis,
but the library is compatible with Android 5.0+ (API level 21+).

### Segment pooling

As an optimization, on some platforms `Buffer`'s segments are pooled,
meaning that everytime a buffer needs a new segment,
an attempt will be made to take a pre-allocated segment from a pool,
and if the pool is empty, only then a new segment will be created.
Everytime a segment is no longer needed, it will be placed back into a pool (unless the pool is already full).

Currently, the pooling is only supported on JVM (and Android).

The segment pool has a two-level structure, with a smaller first-level pool aimed to serve requests as quickly as possible,
and a larger second-level pool. While the first-level pool sizing could not be configured at the moment,
the size of the second-level pool could be adjusted using the `io.github.kotlinmania.io.pool.size.bytes` system property.
The property accepts numeric values only.
On Android, the second-level pool size is `0` by default,
for all other JVM environments it is `4194304` (4 megabytes) by default.

While the size could be changed using the system property, it is necessary to note that its value will be read during
the pool initialization, and later changes of the property will not affect the pool size.
In other words, the property has to be updated before any calls to the `km-io` API.

On JVM, the easiest way to achieve that is to supply the property value via command line JVM flag
(`-Pio.github.kotlinmania.io.pool.size.bytes=XXX`).

On Android, it is a bit trickier. One of the ways to set up the property before running any `km-io`-related code
is by overriding `Application.onCreate` and setting the property there:
```kotlin
package org.example

import android.app.Application

class MySegmentHungryApp : Application() {
    override fun onCreate() {
        System.setProperty("io.github.kotlinmania.io.pool.size.bytes", "2097152" /* or whatever value suites your case */)
        super.onCreate()
    }
}
```

Then, the application class needs to be explicitly registered in the manifest:

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
km-io is licensed under the [Apache 2.0 License](LICENSE).

## Credits

km-io is a fork of [kotlinx-io](https://github.com/Kotlin/kotlinx-io) by JetBrains and the
Kotlin community — thanks to everyone who built the library this work stands on.

An honorable mention goes to the developers of [Okio](https://square.github.io/okio/)
that served as the foundation for kotlinx-io, and to [Jesse Wilson](https://github.com/swankjesse),
for the help with `Okio` adaption, his suggestions, assistance and guidance with kotlinx-io development.
