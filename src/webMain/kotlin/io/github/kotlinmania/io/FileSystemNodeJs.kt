/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kotlinmania.io

public actual val SystemFileSystem: FileSystem =
    object : SystemFileSystemImpl() {
        override fun exists(path: Path): Boolean = fs.existsSync(path.pathString)

        override fun delete(path: Path, mustExist: Boolean) {
            if (!exists(path)) {
                if (mustExist) {
                    throw FileNotFoundException("File does not exist: $path")
                }
                return
            }
            withCaughtException {
                val stats = fs.statSync(path.pathString) ?: throw FileNotFoundException("File does not exist: $path")
                if (stats.isDirectory()) {
                    fs.rmdirSync(path.pathString)
                } else {
                    fs.rmSync(path.pathString)
                }
            }?.also {
                throw IOException("Delete failed for $path", it)
            }
        }

        override fun createDirectories(path: Path, mustCreate: Boolean) {
            val metadata = metadataOrNull(path)
            if (metadata != null) {
                if (mustCreate) {
                    throw IOException("Path already exists: $path")
                }
                if (metadata.isRegularFile) {
                    throw IOException("Path already exists and it's a file: $path")
                }
                return
            }

            val parts = arrayListOf<String>()
            var p: Path? = path
            while (p != null && !exists(p)) {
                parts.add(p.toString())
                p = p.parent
            }
            parts.asReversed().forEach {
                fs.mkdirSync(it)
            }
        }

        override fun atomicMove(source: Path, destination: Path) {
            if (!exists(source)) {
                throw FileNotFoundException("Source does not exist: ${source.pathString}")
            }
            withCaughtException {
                fs.renameSync(source.pathString, destination.pathString)
            }?.also {
                throw IOException("Move failed from $source to $destination", it)
            }
        }

        override fun metadataOrNull(path: Path): FileMetadata? {
            if (!exists(path)) return null
            var metadata: FileMetadata? = null
            withCaughtException {
                val stat = fs.statSync(path.pathString) ?: return@withCaughtException
                val mode = stat.mode
                val isFile = (mode and fs.constants.S_IFMT) == fs.constants.S_IFREG
                metadata =
                    FileMetadata(
                        isRegularFile = isFile,
                        isDirectory = (mode and fs.constants.S_IFMT) == fs.constants.S_IFDIR,
                        if (isFile) stat.size.toLong() else -1L,
                    )
            }?.also {
                throw IOException("Stat failed for $path", it)
            }
            return metadata
        }

        override fun source(path: Path): RawSource = FileSource(path)

        override fun sink(path: Path, append: Boolean): RawSink = FileSink(path, append)

        override fun resolve(path: Path): Path {
            if (!exists(path)) throw FileNotFoundException(path.pathString)
            return Path(fs.realpathSync.native(path.pathString))
        }

        override fun list(directory: Path): Collection<Path> {
            val metadata = metadataOrNull(directory) ?: throw FileNotFoundException(directory.pathString)
            if (!metadata.isDirectory) throw IOException("Not a directory: ${directory.pathString}")
            val dir = fs.opendirSync(directory.pathString) ?: throw IOException("Unable to read directory: ${directory.pathString}")
            try {
                return buildList {
                    var child = dir.readSync()
                    while (child != null) {
                        add(Path(directory, child.name))
                        child = dir.readSync()
                    }
                }
            } finally {
                dir.closeSync()
            }
        }
    }

public actual val SystemTemporaryDirectory: Path
    get() {
        return Path(os.tmpdir() ?: "")
    }

public actual open class FileNotFoundException actual constructor(
    message: String?,
) : IOException(message)
