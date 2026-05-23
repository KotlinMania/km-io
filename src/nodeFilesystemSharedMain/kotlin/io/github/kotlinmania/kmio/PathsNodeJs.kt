/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kotlinmania.kmio

import io.github.kotlinmania.kmio.*
import io.github.kotlinmania.kmio.buffer
import io.github.kotlinmania.kmio.fs
import io.github.kotlinmania.kmio.UnsafeBufferOperations

public actual class Path internal constructor(
    rawPath: String,
    @Suppress("UNUSED_PARAMETER") any: Any?
) {
    internal val pathString: String = removeTrailingSeparators(rawPath)

    public actual val parent: Path?
        get() {
            if (pathString.isEmpty()) return null
            if (isWindows) {
                if (!pathString.contains(UnixPathSeparator) && !pathString.contains(WindowsPathSeparator)) {
                    return null
                }
            } else if (!pathString.contains(SystemPathSeparator)) {
                return null
            }
            val p = path.dirname(pathString)
            return when {
                p.isEmpty() -> null
                p == pathString -> null
                else -> Path(p)
            }
        }

    public actual val isAbsolute: Boolean
        get() {
            return path.isAbsolute(pathString)
        }

    public actual val name: String
        get() {
            when {
                pathString.isEmpty() -> return ""
            }
            val p = path.basename(pathString)
            return when {
                p.isEmpty() -> ""
                else -> p
            }
        }

    public actual override fun toString(): String = pathString

    actual override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Path) return false

        return pathString == other.pathString
    }

    actual override fun hashCode(): Int {
        return pathString.hashCode()
    }
}

public actual val SystemPathSeparator: Char by lazy {
    val sep = path.sep
    check(sep.length == 1)
    sep[0]
}

public actual fun Path(path: String): Path {
    return Path(path, null)
}

internal class FileSource(private val path: Path) : RawSource {
    private var nodeBuffer: NodeJsBuffer? = null
    private var closed = false
    private var offset = 0
    private val fd = open(path)

    private fun open(path: Path): Int {
        if (!fs.existsSync(path.pathString)) {
            throw FileNotFoundException("File does not exist: ${path.pathString}")
        }
        var fd: Int = -1
        withCaughtException {
            fd = fs.openSync(path.pathString, "r")
        }?.also {
            throw IOException("Failed to open a file ${path.pathString}.", it)
        }
        if (fd < 0) throw IOException("Failed to open a file ${path.pathString}.")
        return fd
    }

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "Source is closed." }
        if (byteCount == 0L) {
            return 0
        }
        if (nodeBuffer === null) {
            withCaughtException {
                nodeBuffer = fs.readFileSync(fd, null)
            }?.also {
                throw IOException("Failed to read data from ${path.pathString}", it)
            }
        }
        val len: Int = nodeBuffer!!.length
        if (offset >= len) {
            return -1L
        }
        val bytesToRead = minOf(byteCount, (len - offset))
        for (i in 0 until bytesToRead) {
            sink.writeByte(nodeBuffer!!.readInt8(offset++))
        }

        return bytesToRead
    }

    override fun close() {
        if (!closed) {
            closed = true
            fs.closeSync(fd)
        }
    }
}

internal class FileSink(path: Path, append: Boolean) : RawSink {
    private var closed = false
    private val fd = open(path, append)

    private fun open(path: Path, append: Boolean): Int {
        val flags = if (append) "a" else "w"
        var fd = -1
        withCaughtException {
            fd = fs.openSync(path.pathString, flags)
        }?.also {
            throw IOException("Failed to open a file ${path.pathString}.", it)
        }
        if (fd < 0) throw IOException("Failed to open a file ${path.pathString}.")
        return fd
    }

    @OptIn(UnsafeIoApi::class)
    override fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "Sink is closed." }
        if (byteCount == 0L) {
            return
        }

        var remainingBytes = minOf(byteCount, source.size)
        while (remainingBytes > 0) {
            val segmentBytes = UnsafeBufferOperations.readFromHead(source) { headData, headPos, headLimit ->
                val segmentBytes = headLimit - headPos
                val buf = buffer.Buffer.allocUnsafe(segmentBytes)
                for (offset in 0 until segmentBytes) {
                    buf.writeInt8(headData[headPos + offset], offset)
                }
                withCaughtException {
                    fs.writeFileSync(fd, buf)
                }?.also {
                    throw IOException("Write failed", it)
                }
                segmentBytes
            }
            remainingBytes -= segmentBytes
        }
    }

    override fun flush() = Unit

    override fun close() {
        if (!closed) {
            closed = true
            fs.closeSync(fd)
        }
    }
}
