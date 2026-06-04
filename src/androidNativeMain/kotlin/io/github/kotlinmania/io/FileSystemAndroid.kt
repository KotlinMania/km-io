/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kotlinmania.io

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import io.github.kotlinmania.io.IOException
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
internal actual fun dirnameImpl(path: String): String {
    if (!path.contains(SystemPathSeparator)) {
        return ""
    }
    return dirname(path)?.toKString() ?: ""
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun basenameImpl(path: String): String {
    return __posix_basename(path)?.toKString() ?: ""
}

internal actual fun isAbsoluteImpl(path: String): Boolean = path.startsWith('/')

@OptIn(ExperimentalForeignApi::class)
public actual val SystemTemporaryDirectory: Path
    get() = Path(
        sequenceOf(
            getenv("TMPDIR")?.toKString(),
            getenv("TMP")?.toKString(),
            getenv("TEMP")?.toKString(),
            getenv("TEMPDIR")?.toKString(),
        ).firstOrNull { !it.isNullOrEmpty() } ?: "/tmp",
    )

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun metadataOrNullImpl(path: Path): FileMetadata? {
    memScoped {
        val struct_stat = alloc<stat>()
        if (stat(path.path, struct_stat.ptr) != 0) {
            if (errno == ENOENT) return null
            throw IOException("stat failed to ${path.path}: ${strerror(errno)?.toKString()}")
        }
        val mode = struct_stat.st_mode.toInt()
        val isFile = (mode and S_IFMT) == S_IFREG
        @Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD") // https://youtrack.jetbrains.com/issue/KT-81896
        return FileMetadata(
            isRegularFile = isFile,
            isDirectory = (mode and S_IFMT) == S_IFDIR,
            if (isFile) struct_stat.st_size.toLong() else -1L
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun atomicMoveImpl(source: Path, destination: Path) {
    if (rename(source.path, destination.path) != 0) {
        throw IOException("Move failed: ${strerror(errno)?.toKString()}")
    }
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun mkdirImpl(path: String) {
    if (mkdir(path, PermissionAllowAll.convert()) != 0) {
        throw IOException("mkdir failed: ${strerror(errno)?.toKString()}")
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun realpathImpl(path: String): String {
    val result = realpath(path, null) ?: throw IllegalStateException()
    try {
        return result.toKString()
    } finally {
        free(result)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual class OpaqueDirEntry(private val dir: CPointer<cnames.structs.DIR>) : AutoCloseable {
    actual fun readdir(): String? {
        val entry = platform.posix.readdir(dir) ?: return null
        return entry[0].d_name.toKString()
    }

    actual override fun close() {
        if (closedir(dir) != 0) {
            val err = errno
            val strerr = strerror(err)?.toKString() ?: "unknown error"
            throw IOException("closedir failed with errno $err ($strerr)")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun opendir(path: String): OpaqueDirEntry {
    val dirent = platform.posix.opendir(path)
    if (dirent != null) return OpaqueDirEntry(dirent)

    val err = errno
    val strerr = strerror(err)?.toKString() ?: "unknown error"
    throw IOException("Can't open directory $path: $err ($strerr)")
}
