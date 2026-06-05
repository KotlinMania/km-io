/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

@file:OptIn(UnsafeNumber::class, ExperimentalForeignApi::class)

package io.github.kotlinmania.io

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.DIR
import platform.posix.ENOENT
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.__xpg_basename
import platform.posix.closedir
import platform.posix.dirname
import platform.posix.errno
import platform.posix.free
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.realpath
import platform.posix.rename
import platform.posix.stat
import platform.posix.strerror

public actual val SystemLineSeparator: String = "\n"

internal actual fun dirnameImpl(path: String): String {
    if (!path.contains(SystemPathSeparator)) {
        return ""
    }
    memScoped {
        return dirname(path.cstr.ptr)?.toKString() ?: ""
    }
}

internal actual fun basenameImpl(path: String): String {
    memScoped {
        return __xpg_basename(path.cstr.ptr)?.toKString() ?: ""
    }
}

internal actual fun isAbsoluteImpl(path: String): Boolean = path.startsWith('/')

public actual val SystemTemporaryDirectory: Path
    get() =
        Path(
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
        if (stat(path.pathString, structStat.ptr) != 0) {
            if (errno == ENOENT) return null
            throw IOException("stat failed to ${path.pathString}: ${strerror(errno)?.toKString()}")
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

internal actual fun atomicMoveImpl(source: Path, destination: Path) {
    if (rename(source.pathString, destination.pathString) != 0) {
        throw IOException("Move failed: ${strerror(errno)?.toKString()}")
    }
}

internal actual fun realpathImpl(path: String): String {
    val result = realpath(path, null) ?: throw IllegalStateException()
    try {
        return result.toKString()
    } finally {
        free(result)
    }
}

internal actual fun mkdirImpl(path: String) {
    if (mkdir(path, PermissionAllowAll.convert()) != 0) {
        throw IOException("mkdir failed: ${strerror(errno)?.toKString()}")
    }
}

internal actual class OpaqueDirEntry(
    private val dir: CPointer<DIR>,
) : AutoCloseable {
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
