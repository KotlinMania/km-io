/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kotlinmania.io

import kotlin.math.min
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * Path to a directory suitable for creating temporary files.
 *
 * This path is always `/tmp`, meaning that either `/` or `/tmp` should be pre-opened to use this path.
 */
public actual val SystemTemporaryDirectory: Path = Path("/tmp")

/**
 * An instance of [FileSystem] representing a default system-wide filesystem.
 *
 * The implementation is built upon Wasm WASI preview 1.
 *
 * To use files, at least one directory should be pre-opened.
 *
 * Operations on all absolute paths that are not sub-paths of pre-opened directories will fail.
 *
 * Relative paths are treated as paths relative to the first pre-opened directory.
 * For example, if following directories were pre-opened: `/work`, `/tmp`, `/persistent`, then
 * the path `a/b/c/d` will be resolved to `/work/a/b/c/d` as `/work` is the first pre-opened directory.
 */
public actual val SystemFileSystem: FileSystem get() = WasiFileSystem

@OptIn(UnsafeWasmMemoryApi::class)
internal object WasiFileSystem : SystemFileSystemImpl() {
    override fun exists(path: Path): Boolean = metadataOrNull(path) != null

    override fun delete(path: Path, mustExist: Boolean) {
        val (_, preOpen, relativePath) = PreOpens.resolvePreOpen(path)
        withScopedMemoryAllocator { allocator ->
            val (stringBuffer, stringLength) = allocator.storeString(relativePath.pathString)

            val unlinkRes = Errno(path_unlink_file(preOpen, stringBuffer.address.toInt(), stringLength))
            if (unlinkRes == Errno.SUCCESS) return
            if (unlinkRes == Errno.NOENT) {
                if (mustExist) throw FileNotFoundException("File does not exist: $path")
                return
            }
            // In case the path corresponding to a directory, either Error.isdir, or Errno.PERM will be returned.
            // In all other cases, there's no sense to continue, so we'll bail out with an exception.
            if (unlinkRes != Errno.ISDIR && unlinkRes != Errno.PERM) {
                throw IOException("Unable to remove file $path: ${unlinkRes.description}")
            }

            val removeDirRes = Errno(path_remove_directory(preOpen, stringBuffer.address.toInt(), stringLength))
            if (removeDirRes == Errno.SUCCESS) return
            if (removeDirRes == Errno.NOENT) {
                if (mustExist) throw FileNotFoundException("File does not exist: $path")
                return
            }
            throw IOException("Unable to remove directory ${path.pathString}: ${unlinkRes.description}")
        }
    }

    override fun createDirectories(path: Path, mustCreate: Boolean) {
        val (rootPath, preOpen, relativePath) = PreOpens.resolvePreOpen(path)
        if (relativePath.pathString.isEmpty()) {
            if (!mustCreate) return
            throw IOException("Directory already exists: $path")
        }
        val segmentNames: List<String> =
            buildList {
                var currentPath: Path? = relativePath
                while (currentPath != null) {
                    add(currentPath.name)
                    currentPath = currentPath.parent
                }
            }
        var created = false
        withScopedMemoryAllocator { allocator ->
            // Allocating one extra byte to place the NULL-byte there
            val pathBuffer = allocator.allocate(path.pathString.encodeToByteArray().size + 1)
            val pathBuilder = StringBuilder()

            for (idx in segmentNames.size - 1 downTo 0) {
                pathBuilder.append(segmentNames[idx])

                val segmentLength = pathBuffer.allocateString(pathBuilder.toString())
                val res = Errno(path_create_directory(preOpen, pathBuffer.address.toInt(), segmentLength))
                if (res == Errno.SUCCESS) {
                    created = true
                } else if (res != Errno.EXIST) {
                    throw IOException(
                        "Can't create directory $path. Creation of an intermediate directory " +
                            "${Path(rootPath, pathBuilder.toString())} failed: ${res.description}",
                    )
                }
                pathBuilder.append(SystemPathSeparator)
            }
            if (mustCreate && !created) throw IOException("Directory already exists: $path")
            if (!created) {
                if (!metadataOrNull(path)!!.isDirectory) {
                    throw IOException("Path already exists and it's not a directory: $path")
                }
            }
        }
    }

    /**
     * The move is not atomic (well, we don't know what kind of move it is), but there are no
     * alternatives.
     */
    override fun atomicMove(source: Path, destination: Path) {
        val (_, sourcePreOpen, relativeSourcePath) = PreOpens.resolvePreOpen(source)
        val (_, destPreOpen, relativeDestinationPath) = PreOpens.resolvePreOpen(destination)

        withScopedMemoryAllocator { allocator ->
            val (sourceBuffer, sourceBufferLength) = allocator.storeString(relativeSourcePath.pathString)
            val (destBuffer, destBufferLength) = allocator.storeString(relativeDestinationPath.pathString)

            val res =
                Errno(
                    path_rename(
                        oldFd = sourcePreOpen,
                        oldPathPtr = sourceBuffer.address.toInt(),
                        oldPathLen = sourceBufferLength,
                        newFd = destPreOpen,
                        newPathPtr = destBuffer.address.toInt(),
                        newPathLen = destBufferLength,
                    ),
                )
            when (res) {
                Errno.SUCCESS -> return
                Errno.NOENT -> throw FileNotFoundException(
                    "Failed to rename $source to $destination as either source file/directory, " +
                        "or destination's parent directory does not exist.",
                )

                else -> throw IOException("Failed to rename $source to $destination: ${res.description}")
            }
        }
    }

    override fun source(path: Path): RawSource {
        val (_, preOpen, relativePath) = PreOpens.resolvePreOpen(path)

        val fd =
            withScopedMemoryAllocator { allocator ->
                val fdPtr = allocator.allocateInt()
                val (stringBuffer, stringBufferLength) = allocator.storeString(relativePath.pathString)

                val res =
                    Errno(
                        path_open(
                            fd = preOpen,
                            dirflags = listOf(LookupFlags.SYMLINK_FOLLOW).toBitset(),
                            pathPtr = stringBuffer.address.toInt(),
                            pathLen = stringBufferLength,
                            oflags = 0,
                            fsRightsBase = listOf(Rights.FD_READ).toBitset(),
                            fsRightsInheriting = 0,
                            fdFlags = 0,
                            resultPtr = fdPtr.address.toInt(),
                        ),
                    )
                if (res != Errno.SUCCESS) {
                    if (res == Errno.NOENT) throw FileNotFoundException("File does not exist: $path")
                    throw IOException("Can't open $path for read: ${res.description}")
                }
                fdPtr.loadInt()
            }
        return FileSource(fd)
    }

    @OptIn(UnsafeWasmMemoryApi::class)
    override fun sink(path: Path, append: Boolean): RawSink {
        val (_, preOpen, relativePath) = PreOpens.resolvePreOpen(path)

        val fd =
            withScopedMemoryAllocator { allocator ->
                val fdPtr = allocator.allocateInt()
                val (stringBuffer, stringBufferLength) = allocator.storeString(relativePath.pathString)

                val fdFlags =
                    buildList {
                        if (append) {
                            add(FdFlags.APPEND)
                        }
                    }.toBitset()

                val openFlags =
                    buildList {
                        add(OpenFlags.CREAT)
                        if (!append) {
                            add(OpenFlags.TRUNC)
                        }
                    }.toBitset()

                val res =
                    Errno(
                        path_open(
                            fd = preOpen,
                            dirflags = listOf(LookupFlags.SYMLINK_FOLLOW).toBitset(),
                            pathPtr = stringBuffer.address.toInt(),
                            pathLen = stringBufferLength,
                            oflags = openFlags,
                            fsRightsBase = listOf(Rights.FD_WRITE, Rights.FD_SYNC).toBitset(),
                            fsRightsInheriting = 0,
                            fdFlags = fdFlags,
                            resultPtr = fdPtr.address.toInt(),
                        ),
                    )
                if (res != Errno.SUCCESS) {
                    throw IOException("Can't open $path for write: ${res.description}")
                }
                fdPtr.loadInt()
            }
        return FileSink(fd)
    }

    override fun metadataOrNull(path: Path): FileMetadata? {
        val (_, preOpen, relativePath) = PreOpens.resolvePreopenOrNull(path) ?: return null
        val md = metadataOrNullInternal(preOpen, relativePath, true) ?: return null

        val filetype = md.filetype
        val isDirectory = filetype == FileType.DIRECTORY
        val filesize = if (isDirectory) -1 else md.filesize
        return FileMetadata(
            isRegularFile = filetype == FileType.REGULAR_FILE,
            isDirectory = isDirectory,
            size = filesize,
        )
    }

    /**
     * Returns an absolute path to the same file or directory the [path] is pointing to.
     * All symbolic links are solved, extra path separators and references to current (`.`) or
     * parent (`..`) directories are removed.
     * If the [path] is a relative path then it'll be resolved against current working directory.
     * If there is no file or directory to which the [path] is pointing to then [FileNotFoundException] will be thrown.
     *
     * The behavior of this method differs from other platforms as the resolution
     * may not fail if there is no filesystem-node (file, directory, symlink, etc.) corresponding
     * to some interior path. This allows successfully resolving paths like `/a/b/c/../../d/e` when
     * pre-opened directories are `/a/b/c` and `/a/d/e`.
     *
     * @param path the path to resolve.
     * @return a resolved path.
     * @throws FileNotFoundException if there is no file or directory corresponding to the specified path.
     */
    override fun resolve(path: Path): Path {
        val absolutePath =
            if (path.isAbsolute) {
                path
            } else {
                Path(PreOpens.roots.first(), path.pathString)
            }

        val resolvedPath = resolvePathImpl(absolutePath, 0)
        check(resolvedPath.isAbsolute) {
            "Path is not absolute after symlinks resolution"
        }

        val normalizedPath = resolvedPath.normalized()
        if (!exists(normalizedPath)) throw FileNotFoundException("Path does not exist: $path")
        return normalizedPath
    }

    internal fun symlink(linked: Path, target: Path) {
        val (_, preOpen, relativePath) = PreOpens.resolvePreOpen(target)

        withScopedMemoryAllocator { allocator ->
            val (fromBuffer, fromBufferLength) = allocator.storeString(linked.pathString)
            val (toBuffer, toBufferLength) = allocator.storeString(relativePath.pathString)
            val res =
                Errno(
                    path_symlink(
                        fromBuffer.address.toInt(),
                        fromBufferLength,
                        preOpen,
                        toBuffer.address.toInt(),
                        toBufferLength,
                    ),
                )

            if (res == Errno.SUCCESS) return
            throw IOException("Can't create symbolic link $target pointing to $linked: ${res.description}")
        }
    }

    override fun list(directory: Path): Collection<Path> {
        val (_, preOpen, relativeDirectoryPath) = PreOpens.resolvePreOpen(directory)

        val metadata =
            metadataOrNullInternal(preOpen, relativeDirectoryPath, true)
                ?: throw FileNotFoundException(directory.pathString)
        if (metadata.filetype != FileType.DIRECTORY) throw IOException("Not a directory: ${directory.pathString}")

        val children = mutableListOf<Path>()
        val dir_fd =
            withScopedMemoryAllocator { allocator ->
                val fdPtr = allocator.allocateInt()
                val (stringBuffer, stringBufferLength) = allocator.storeString(relativeDirectoryPath.pathString)

                val res =
                    Errno(
                        path_open(
                            fd = preOpen,
                            dirflags = listOf(LookupFlags.SYMLINK_FOLLOW).toBitset(),
                            pathPtr = stringBuffer.address.toInt(),
                            pathLen = stringBufferLength,
                            oflags = setOf(OpenFlags.DIRECTORY).toBitset(),
                            fsRightsBase = listOf(Rights.FD_READDIR, Rights.FD_READ).toBitset(),
                            fsRightsInheriting = 0,
                            fdFlags = 0,
                            resultPtr = fdPtr.address.toInt(),
                        ),
                    )
                if (res != Errno.SUCCESS) throw IOException("Can't open directory ${directory.pathString}: ${res.description}")
                fdPtr.loadInt()
            }
        var closeFailure: IOException? = null
        try {
            withScopedMemoryAllocator { allocator ->
                val resultSizePtr = allocator.allocateInt()
                // directory's filesize expected to be larger than the actual buffer size required to fit all entries
                val bufferSize = metadata.filesize.toInt()
                val buffer = allocator.allocate(bufferSize)
                // Unsupported on Windows and Android:
                // https://github.com/nodejs/node/blob/6f4d6011ea1b448cf21f5d363c44e4a4c56ca34c/deps/uvwasi/src/uvwasi.c#L19
                val res =
                    Errno(
                        fd_readdir(
                            fd = dir_fd,
                            bufPtr = buffer.address.toInt(),
                            bufLen = bufferSize,
                            cookie = 0L,
                            resultPtr = resultSizePtr.address.toInt(),
                        ),
                    )
                if (res != Errno.SUCCESS) {
                    throw IOException("Can't read directory ${directory.pathString}: ${res.description}")
                }
                val resultSize: Int = resultSizePtr.loadInt()
                check(resultSize <= bufferSize) { "Result size: $resultSize, buffer size: $bufferSize" }
                var entryPtr = buffer
                val endPtr = entryPtr + resultSize
                while (entryPtr.address < endPtr.address) {
                    // read dirent: https://github.com/WebAssembly/WASI/blob/main/legacy/preview1/docs.md#-dirent-record
                    // Each entry is 24-byte-wide dirent with filename length at offset 16, followed by
                    // filename length bytes of data.
                    val entryLen = entryPtr.loadInt(16)
                    entryPtr += 24
                    val name = entryPtr.loadString(entryLen)
                    entryPtr += entryLen
                    if (name != "." && name != "..") {
                        children.add(Path(directory, name))
                    }
                }
            }
        } finally {
            val res = Errno(fd_close(dir_fd))
            if (res != Errno.SUCCESS) {
                closeFailure = IOException("fd_close failed for directory '$directory': ${res.description}")
            }
        }
        closeFailure?.let { throw it }
        return children
    }
}

private fun Path.normalized(): Path {
    require(isAbsolute)

    val parts = pathString.split(UnixPathSeparator)
    val constructedPath = mutableListOf<String>()
    // parts[0] is always empty
    for (idx in 1 until parts.size) {
        when (val part = parts[idx]) {
            "." -> continue
            ".." -> constructedPath.removeLastOrNull()
            else -> constructedPath.add(part)
        }
    }
    return constructedPath.fold(Path(UnixPathSeparator.toString())) { parent, part ->
        Path(parent, part)
    }
}

public actual open class FileNotFoundException actual constructor(
    message: String?,
) : IOException(message)

internal object PreOpens {
    data class PreOpen(
        val path: Path,
        val fd: Int,
    )

    data class ResolvedRelativePath(
        val preOpenPath: Path,
        val preOpenFd: Int,
        val relativePath: Path,
    )

    internal val roots: List<Path> by lazy {
        preopenEntries.map { it.path }
    }

    internal val preopenEntries: List<PreOpen> by lazy {
        // 0 - stdin, 1 - stdout, 2 - stderr, 3 - if the first preopened directory, if any
        val firstPreopenFd = 3
        val rootPaths = mutableListOf<PreOpen>()

        for (fd in firstPreopenFd..<Int.MAX_VALUE) {
            if (!loadPreopenInfo(fd, rootPaths)) {
                break
            }
        }

        rootPaths
    }

    /**
     * Finds a pre-open directory corresponding to a given path and relativize the path against it.
     * If there are no pre-open directories corresponding to a given path, returns `null`.
     *
     * Let's assume that there are two preopen directories: `PreOpen("/home", 3)` and `PreOpen("/tmp", 4)`.
     * Resolution of a `Path("/tmp/logs/log.txt")` will result in a
     * `ResolvedRelativePath(Path("/tmp"), 4, Path("logs/log.txt"))`.
     */
    internal fun resolvePreopenOrNull(path: Path): ResolvedRelativePath? {
        if (!path.isAbsolute) {
            val preOpen = preopenEntries.firstOrNull() ?: return null
            return ResolvedRelativePath(preOpen.path, preOpen.fd, path)
        }

        for (preopen in preopenEntries) {
            var p: Path? = path
            val builder = mutableListOf<String>()
            while (p != null) {
                if (preopen.path == p) {
                    return ResolvedRelativePath(
                        preopen.path,
                        preopen.fd,
                        Path(builder.asReversed().joinToString(SystemPathSeparator.toString())),
                    )
                }
                builder.add(p.name)
                p = p.parent
            }
        }
        return null
    }

    /**
     * Finds a pre-open directory corresponding to a given path and relativize the path against it.
     * If there are no pre-open directories corresponding to a given path, throws [IOException].
     *
     * See [resolvePreopenOrNull] for examples.
     */
    internal fun resolvePreOpen(path: Path): ResolvedRelativePath =
        resolvePreopenOrNull(path)
            ?: throw IOException("Path does not belong to any preopened directory: $path")

    @OptIn(UnsafeWasmMemoryApi::class)
    private fun loadPreopenInfo(fd: Int, outputList: MutableList<PreOpen>): Boolean {
        return withScopedMemoryAllocator { allocator ->
            val prestat = Prestat(allocator)
            val res = Errno(fd_prestat_get(fd, prestat.address))

            if (res == Errno.BADF) {
                return@withScopedMemoryAllocator false
            }

            if (res != Errno.SUCCESS) {
                throw IOException("Unable to process fd=$fd as preopen: ${res.description}")
            }

            check(prestat.type == PrestatType.DIR) { "Unsupported prestat type" }

            val nameLength = prestat.nameLength
            val pathBuffer = allocator.allocate(nameLength)
            val dirnameRes = Errno(fd_prestat_dir_name(fd, pathBuffer.address.toInt(), nameLength))
            if (dirnameRes != Errno.SUCCESS) {
                throw IOException("Unable to get preopen dir name for fd=$fd: ${dirnameRes.description}")
            }
            outputList.add(PreOpen(Path(pathBuffer.loadBytes(nameLength).decodeToString()), fd))

            true
        }
    }
}

private const val TEMP_CIOVEC_BUFFER_LEN = 8192

private class FileSink(
    private val fd: Fd,
) : RawSink {
    private var closed: Boolean = false

    @OptIn(UnsafeWasmMemoryApi::class)
    override fun write(source: Buffer, byteCount: Long) {
        check(byteCount >= 0)
        if (byteCount == 0L) return
        withScopedMemoryAllocator { allocator ->
            val temporaryWriteBuffer = allocator.allocate(TEMP_CIOVEC_BUFFER_LEN)
            var remaining = byteCount

            val ciovec =
                Ciovec(allocator).also {
                    it.buffer = temporaryWriteBuffer
                }

            val resultPtr = allocator.allocateInt()

            while (remaining > 0) {
                val bytesToWrite = minOf(remaining, TEMP_CIOVEC_BUFFER_LEN).toInt()
                source.readToLinearMemory(temporaryWriteBuffer, bytesToWrite)
                ciovec.length = bytesToWrite

                val res = Errno(fd_write(fd, ciovec.address, 1, resultPtr.address.toInt()))
                if (res != Errno.SUCCESS) {
                    throw IOException("Write failed: ${res.description}")
                }
                check(resultPtr.loadInt(0) == bytesToWrite) {
                    "Expected to write $bytesToWrite, but ${resultPtr.loadInt(0)} bytes were written"
                }
                remaining -= bytesToWrite
            }
        }
    }

    override fun flush() {
        val res = Errno(fd_sync(fd))
        if (res != Errno.SUCCESS) {
            throw IOException("fd_sync failed: ${res.description}")
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            val res = Errno(fd_close(fd))
            if (res != Errno.SUCCESS) {
                throw IOException("fd_close failed: ${res.description}")
            }
        }
    }
}

private class FileSource(
    private val fd: Fd,
) : RawSource {
    private var closed: Boolean = false

    @OptIn(UnsafeWasmMemoryApi::class)
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(byteCount >= 0)
        if (byteCount == 0L) return 0L
        var totalRead = 0L

        withScopedMemoryAllocator { allocator ->
            val temporaryReadBuffer = allocator.allocate(TEMP_CIOVEC_BUFFER_LEN)
            var remaining = byteCount

            val ciovec =
                Ciovec(allocator).also {
                    it.buffer = temporaryReadBuffer
                }

            val resultPtr = allocator.allocateInt()
            while (remaining > 0) {
                val bytesToRead = minOf(remaining, TEMP_CIOVEC_BUFFER_LEN).toInt()
                ciovec.length = bytesToRead

                val res = Errno(fd_read(fd, ciovec.address, 1, resultPtr.address.toInt()))
                if (res != Errno.SUCCESS) {
                    throw IOException("Read failed: ${res.description}")
                }
                val readBytes = resultPtr.loadInt()
                // we're done
                if (readBytes == 0) {
                    if (totalRead == 0L) return -1L
                    break
                }
                sink.writeFromLinearMemory(temporaryReadBuffer, readBytes)
                remaining -= readBytes
                totalRead += readBytes
            }
        }
        return totalRead
    }

    override fun close() {
        if (!closed) {
            closed = true
            val res = Errno(fd_close(fd))
            if (res != Errno.SUCCESS) {
                throw IOException("fd_close failed: ${res.description}")
            }
        }
    }
}

private data class InternalMetadata(
    val filetype: FileType,
    val filesize: Long,
)

@OptIn(UnsafeWasmMemoryApi::class)
private fun metadataOrNullInternal(rootFd: Fd, path: Path, followSymlinks: Boolean): InternalMetadata? {
    require(!path.isAbsolute) { "only relative paths are allowed, was: $path" }
    withScopedMemoryAllocator { allocator ->
        val (pathBuffer, pathBufferLength) = allocator.storeString(path.pathString)

        val filestat = FileStat(allocator)

        val res =
            Errno(
                path_filestat_get(
                    fd = rootFd,
                    flags = if (followSymlinks) 1 else 0,
                    pathPtr = pathBuffer.address.toInt(),
                    pathLen = pathBufferLength,
                    resultPtr = filestat.address,
                ),
            )

        if (res == Errno.NOENT || res == Errno.NOTCAPABLE) return null
        if (res != Errno.SUCCESS) throw IOException(res.description)

        return InternalMetadata(filestat.filetype, filestat.filesize)
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun readlinkInternal(rootFd: Fd, path: Path, linkSize: Int): Path {
    require(!path.isAbsolute) { "only relative paths are allowed, was: $path" }
    withScopedMemoryAllocator { allocator ->
        val resultPtr = allocator.allocateInt()
        val (pathBuffer, pathBufferLength) = allocator.storeString(path.pathString)
        // Allocating one extra byte to have enough space for the NULL-byte
        val resultBuffer = allocator.allocate(linkSize + 1)

        val res =
            Errno(
                path_readlink(
                    fd = rootFd,
                    pathPtr = pathBuffer.address.toInt(),
                    pathLen = pathBufferLength,
                    bufPtr = resultBuffer.address.toInt(),
                    bufLen = linkSize + 1,
                    resultPtr = resultPtr.address.toInt(),
                ),
            )
        if (res != Errno.SUCCESS) {
            throw IOException("Link resolution failed for path $path: ${res.description}")
        }
        val resultLength = resultPtr.loadInt()
        // resultLength includes the NULL-byte, we don't have to read it
        return Path(resultBuffer.loadBytes(min(resultLength, linkSize)).decodeToString())
    }
}

// The value compatible with current Linux defaults
private const val PATH_RESOLUTION_MAX_LINKS_DEPTH = 40

private fun resolvePathImpl(path: Path, recursion: Int): Path {
    if (recursion >= PATH_RESOLUTION_MAX_LINKS_DEPTH) {
        throw IOException("Too many levels of symbolic links")
    }
    val resolvedParent =
        when (val parent = path.parent) {
            null -> null
            else -> resolvePathImpl(parent, recursion)
        }
    val withResolvedParent =
        when (resolvedParent) {
            null -> path
            else -> Path(resolvedParent, path.name)
        }
    // There are cases when we simply can't resolve the intermediate path, but the resulting path should be fine:
    // pre-opened directories: [/a/b/c, /a/d/e]
    // path to resolve: /a/b/c/../../d/e/f
    // The path, after normalization, is /a/d/e/f and its metadata could be fetched using the root /a/d/e.
    // However, normalization could not be performed before links resolution, and an intermediate non-normalized path
    // may point to a directory not belonging to any of pre-opened directories (like /a/b/c/../..).
    // So, let's hope for the best and throw an exception after resolution and normalization.
    val (_, preOpen, relativePath) = PreOpens.resolvePreopenOrNull(withResolvedParent) ?: return withResolvedParent
    val metadata = metadataOrNullInternal(preOpen, relativePath, false) ?: return withResolvedParent
    if (metadata.filetype == FileType.SYMBOLIC_LINK) {
        val linkTarget = readlinkInternal(preOpen, relativePath, metadata.filesize.toInt())
        val resolvedPath =
            if (linkTarget.isAbsolute || resolvedParent == null) {
                linkTarget
            } else {
                Path(resolvedParent, linkTarget.pathString)
            }
        return resolvePathImpl(resolvedPath, recursion + 1)
    }
    return withResolvedParent
}
