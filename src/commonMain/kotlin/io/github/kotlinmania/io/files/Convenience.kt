/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kotlinmania.io.files

import io.github.kotlinmania.io.IOException
import io.github.kotlinmania.io.RawSink
import io.github.kotlinmania.io.RawSource
import io.github.kotlinmania.io.buffered
import io.github.kotlinmania.io.readByteArray
import io.github.kotlinmania.io.readString
import io.github.kotlinmania.io.writeString

/**
 * Whole-file conveniences over [FileSystem], mirroring the operations callers expect from a
 * std `fs`/`Files` style API. Every read opens a source and closes it before returning; every
 * write opens a sink, writes and closes it.
 */

/** Reads the file at [path] entirely as UTF-8 text, closing the source first. */
public fun FileSystem.readString(path: Path): String {
    val raw: RawSource = source(path)
    try {
        return raw.buffered().readString()
    } finally {
        raw.close()
    }
}

/** Reads the file at [path] entirely as bytes, closing the source first. */
public fun FileSystem.readBytes(path: Path): ByteArray {
    val raw: RawSource = source(path)
    try {
        return raw.buffered().readByteArray()
    } finally {
        raw.close()
    }
}

/** Reads the file at [path] as UTF-8 text split into lines without line terminators. */
public fun FileSystem.readLines(path: Path): List<String> = readString(path).lines()

/** Writes [text] to the file at [path] as UTF-8, replacing any existing file. */
public fun FileSystem.writeString(path: Path, text: String) {
    val raw: RawSink = sink(path)
    try {
        raw.buffered().use { it.writeString(text) }
    } finally {
        raw.close()
    }
}

/** Writes [bytes] to the file at [path], replacing any existing file. */
public fun FileSystem.writeBytes(path: Path, bytes: ByteArray) {
    val raw: RawSink = sink(path)
    try {
        raw.buffered().use { it.write(bytes) }
    } finally {
        raw.close()
    }
}

/** Creates a directory at [path], failing with [IOException] when it already exists. */
public fun FileSystem.createDirectory(path: Path) {
    if (metadataOrNull(path) != null) throw IOException("Directory already exists: $path")
    createDirectories(path, mustCreate = true)
}

/** Returns `true` when [path] points to a directory, `false` when it is absent or a file. */
public fun FileSystem.isDirectory(path: Path): Boolean = metadataOrNull(path)?.isDirectory == true

/** Returns the depth-first sequence of every path under [directory], starting with [directory] itself. */
public fun FileSystem.listRecursively(directory: Path): Sequence<Path> =
    sequence {
        yield(directory)
        for (child in list(directory)) {
            if (isDirectory(child)) {
                yieldAll(listRecursively(child))
            } else {
                yield(child)
            }
        }
    }

/** Joins this path with another, as a child. */
public fun Path.resolve(other: Path): Path = Path(this, other.toString())

/** Joins this path with another, as a child. */
public fun Path.resolve(other: String): Path = Path(this, other)
