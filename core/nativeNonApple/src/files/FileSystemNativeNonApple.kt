/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kotlinmania.io.files

import kotlinx.cinterop.*
import io.github.kotlinmania.io.IOException
import io.github.kotlinmania.io.isWindows
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
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
