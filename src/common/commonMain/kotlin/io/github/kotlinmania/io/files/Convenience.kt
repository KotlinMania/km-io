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

/**
 * Returns a path lexical from the current working directory when this path is relative,
 * passing absolute paths through unchanged.
 */
public fun Path.toAbsolutePath(): Path {
    if (isAbsolute) return this
    val cwd = SystemFileSystem.resolve(Path("."))
    return Path(cwd.toString(), this.toString())
}

/** Splits a path into components, dropping empty segments, `.` and resolving `..` lexically. */
internal fun pathComponents(path: String): List<String> {
    var normalized = path
    if (SystemPathSeparator == '\\' && !normalized.contains('\\')) normalized = normalized.replace('/', '\\')
    val raw = normalized.split(SystemPathSeparator).filter { it.isNotEmpty() && it != "." }
    val components = ArrayList<String>(raw.size)
    for (component in raw) {
        if (component == ".." && components.isNotEmpty() && components.last() != "..") {
            components.removeAt(components.lastIndex)
        } else {
            components.add(component)
        }
    }
    if (path.startsWith("/")) components.add(0, "/") // POSIX absolute marker keeps relativize well-formed
    if (normalized.startsWith("\\\\") || normalized.startsWith("//")) components.add(0, "/")
    return components.toList()
}

internal fun joinPathComponents(components: List<String>): String {
    if (components.isEmpty()) return "."
    val separator = SystemPathSeparator
    val body =
        components
            .drop(if (components.first() == "/") 1 else 0)
            .joinToString(separator.toString())
    return if (components.first() == "/" && separator == '/') "/" + body else body
}

/**
 * Constructs a relative path between this path and [other] lexically, after normalizing both
 * to absolute paths. When the paths are equal, the empty-path `.` is returned.
 */
public fun Path.relativize(other: Path): Path {
    val from = pathComponents(toAbsolutePath().toString())
    val to = pathComponents(other.toAbsolutePath().toString())
    var common = 0
    while (common < from.size && common < to.size && from[common] == to[common]) common++
    val ups = List(from.size - common) { ".." }
    val rest = to.subList(common, to.size)
    if (ups.isEmpty() && rest.isEmpty()) return Path(".")
    return Path(joinPathComponents(ups + rest))
}
