/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

@file:OptIn(ExperimentalForeignApi::class)

package io.github.kotlinmania.io.files

import kotlinx.cinterop.*
import io.github.kotlinmania.io.IOException
import io.github.kotlinmania.io.isWindows
import platform.posix.*
import platform.windows.*

internal actual fun atomicMoveImpl(source: Path, destination: Path) {
    if (MoveFileExA(source.path, destination.path, MOVEFILE_REPLACE_EXISTING.convert()) == 0) {
        // TODO: get formatted error message
        throw IOException("Move failed with error code: ${GetLastError()}")
    }
}

internal actual fun dirnameImpl(path: String): String {
    if (!path.contains(UnixPathSeparator) && !path.contains(WindowsPathSeparator)) {
        return ""
    }
    memScoped {
        return dirname(path.cstr.ptr)?.toKString() ?: ""
    }
}

internal actual fun basenameImpl(path: String): String {
    memScoped {
        return basename(path.cstr.ptr)?.toKString() ?: ""
    }
}

internal actual fun isAbsoluteImpl(path: String): Boolean {
    if (path.startsWith(SystemPathSeparator)) return true
    if (path.length > 1 && path[1] == ':') {
        if (path.length == 2) return false
        val next = path[2]
        return next == WindowsPathSeparator || next == SystemPathSeparator
    }
    return PathIsRelativeA(path) == 0
}

public actual val SystemTemporaryDirectory: Path
    get() = Path(
        systemTemporaryDirectoryPath(
            tmpDir = getenv("TMPDIR")?.toKString(),
            tmp = getenv("TMP")?.toKString(),
            temp = getenv("TEMP")?.toKString(),
            tempDir = getenv("TEMPDIR")?.toKString(),
        ),
    )

internal fun systemTemporaryDirectoryPath(
    tmpDir: String?,
    tmp: String?,
    temp: String?,
    tempDir: String?,
    isWindowsHost: Boolean = isWindows,
): String =
    sequenceOf(tmpDir, tmp, temp, tempDir)
        .firstOrNull { !it.isNullOrEmpty() }
        ?: if (isWindowsHost) {
            "C:/Windows/Temp"
        } else {
            "/tmp"
        }

internal actual fun metadataOrNullImpl(path: Path): FileMetadata? {
    memScoped {
        val structStat = alloc<stat>()
        if (stat(path.path, structStat.ptr) != 0) {
            if (errno == ENOENT) return null
            throw IOException("stat failed to ${path.path}: ${strerror(errno)?.toKString()}")
        }
        val mode = structStat.st_mode.toInt()
        val isFile = (mode and S_IFMT) == S_IFREG
        @Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD") // https://youtrack.jetbrains.com/issue/KT-81896
        return FileMetadata(
            isRegularFile = isFile,
            isDirectory = (mode and S_IFMT) == S_IFDIR,
            size = if (isFile) structStat.st_size.toLong() else -1L,
        )
    }
}

internal actual fun mkdirImpl(path: String) {
    if (mkdir(path) != 0) {
        throw IOException("mkdir failed: ${strerror(errno)?.toKString()}")
    }
}

private const val MAX_PATH_LENGTH = 32767

internal actual fun realpathImpl(path: String): String {
    memScoped {
        val buffer = allocArray<CHARVar>(MAX_PATH_LENGTH)
        val len = GetFullPathNameA(path, MAX_PATH_LENGTH.convert(), buffer, null)
        if (len == 0u) throw IllegalStateException()
        return buffer.toKString()
    }
}

internal actual class OpaqueDirEntry(private val dir: CPointer<DIR>) : AutoCloseable {
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

internal actual fun opendir(path: String): OpaqueDirEntry {
    val dirent = platform.posix.opendir(path)
    if (dirent != null) return OpaqueDirEntry(dirent)

    val err = errno
    val strerr = strerror(err)?.toKString() ?: "unknown error"
    throw IOException("Can't open directory $path: $err ($strerr)")
}
