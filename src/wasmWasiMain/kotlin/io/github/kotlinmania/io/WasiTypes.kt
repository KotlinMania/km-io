/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

@file:OptIn(UnsafeWasmMemoryApi::class)

package io.github.kotlinmania.io

import kotlin.wasm.unsafe.MemoryAllocator
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi

// https://github.com/WebAssembly/WASI/blob/main/legacy/preview1/docs.md
internal enum class Errno(
    val description: String,
) {
    SUCCESS("No error occurred. System call completed successfully"),
    TOOBIG("Argument list too long"),
    ACCES("Permission denied"),
    ADDRINUSE("Address in use"),
    ADDRNOTAVAIL("Address not available"),
    AFNOSUPPORT("Address family not supported"),
    AGAIN("Resource unavailable, or operation would block"),
    ALREADY("Connection already in progress"),
    BADF("Bad file descriptor"),
    BADMSG("Bad message"),
    BUSY("Device or resource busy"),
    CANCELED("Operation canceled"),
    CHILD("No child processes"),
    CONNABORTED("Connection aborted"),
    CONNREFUSED("Connection refused"),
    CONNRESET("Connection reset"),
    DEADLK("Resource deadlock would occur"),
    DESTADDRREQ("Destination address required"),
    DOM("Mathematics argument out of domain of function"),
    DQUOT("Reserved"),
    EXIST("File exists"),
    FAULT("Bad address"),
    FBIG("File too large"),
    HOSTUNREACH("Host is unreachable"),
    IDRM("Identifier removed"),
    ILSEQ("Illegal byte sequence"),
    INPROGRESS("Operation in progress"),
    INTR("Interrupted function"),
    INVAL("Invalid argument"),
    IO("I/O error"),
    ISCONN("Socket is connected"),
    ISDIR("Is a directory"),
    LOOP("Too many levels of symbolic links"),
    MFILE("File descriptor value too large"),
    MLINK("Too many links"),
    MSGSIZE("Message too large"),
    MULTIHOP("Reserved"),
    NAMETOOLONG("Filename too long"),
    NETDOWN("Network is down"),
    NETRESET("Connection aborted by network"),
    NETUNREACH("Network unreachable"),
    NFILE("Too many files open in system"),
    NOBUFS("No buffer space available"),
    NODEV("No such device"),
    NOENT("No such file or directory"),
    NOEXEC("Executable file format error"),
    NOLCK("No locks available"),
    NOLINK("Reserved"),
    NOMEM("Not enough space"),
    NOMSG("No message of the desired type"),
    NOPROTOOPT("Protocol not available"),
    NOSPC("No space left on device"),
    NOSYS("Function not supported"),
    NOTCONN("The socket is not connected"),
    NOTDIR("Not a directory or a symbolic link to a directory"),
    NOTEMPTY("Directory not empty"),
    NOTRECOVERABLE("State not recoverable"),
    NOTSOCK("Not a socket"),
    NOTSUP("Not supported, or operation not supported on socket"),
    NOTTY("Inappropriate I/O control operation"),
    NXIO("No such device or address"),
    OVERFLOW("Value too large to be stored in data type"),
    OWNERDEAD("Previous owner died"),
    PERM("Operation not permitted"),
    PIPE("Broken pipe"),
    PROTO("Protocol error"),
    PROTONOSUPPORT("Protocol not supported"),
    PROTOTYPE("Protocol wrong type for socket"),
    RANGE("Result too large"),
    ROFS("Read-only file system"),
    SPIPE("Invalid seek"),
    SRCH("No such process"),
    STALE("Reserved"),
    TIMEDOUT("Connection timed out"),
    TXTBSY("Text file busy"),
    XDEV("Cross-device link"),
    NOTCAPABLE("Extension: Capabilities insufficient"),
}

internal fun Errno(errno: Int): Errno {
    require(errno in Errno.entries.indices) { "Unknown errno: $errno" }
    return Errno.entries[errno]
}

internal enum class FileType {
    UNKNOWN,
    BLOCK_DEVICE,
    CHARACTER_DEVICE,
    DIRECTORY,
    REGULAR_FILE,
    SOCKET_DGRAM,
    SOCKET_STREAM,
    SYMBOLIC_LINK,
}

internal fun FileType(filetype: Byte): FileType {
    val value = filetype.toInt()
    require(value in FileType.entries.indices) { "Unknown file type: $value" }
    return FileType.entries[value]
}

internal enum class Rights {
    FD_DATASYNC,
    FD_READ,
    FD_SEEK,
    FD_FDSTAT_SET_FLAGS,
    FD_SYNC,
    FD_TELL,
    FD_WRITE,
    FD_ADVISE,
    FD_ALLOCATE,
    PATH_CREATE_DIRECTORY,
    PATH_CREATE_FILE,
    PATH_LINK_SOURCE,
    PATH_LINK_TARGET,
    PATH_OPEN,
    FD_READDIR,
    PATH_READLINK,
    PATH_RENAME_SOURCE,
    PATH_RENAME_TARGET,
    PATH_FILESTAT_GET,
    PATH_FILESTAT_SET_SIZE,
    PATH_FILESTAT_SET_TIMES,
    FD_FILESTAT_GET,
    FD_FILESTAT_SET_SIZE,
    FD_FILESTAT_SET_TIMES,
    PATH_SYMLINK,
    PATH_REMOVE_DIRECTORY,
    PATH_UNLINK_FILE,
    POLL_FD_READWRITE,
    SOCK_SHUTDOWN,
    SOCK_ACCEPT,
}

internal fun Iterable<Rights>.toBitset(): Long {
    var bitset = 0L
    for (right in this) {
        bitset = bitset.or(1L shl right.ordinal)
    }
    return bitset
}

internal enum class FdFlags {
    APPEND,
    DSYNC,
    NONBLOCK,
    RSYNC,
}

internal fun Iterable<FdFlags>.toBitset(): Short {
    var bitset = 0
    for (flag in this) {
        bitset = bitset.or(1 shl flag.ordinal)
    }
    return bitset.toShort()
}

internal enum class LookupFlags {
    SYMLINK_FOLLOW,
}

internal fun Iterable<LookupFlags>.toBitset(): Int {
    var bitset = 0
    for (flag in this) {
        bitset = bitset.or(1 shl flag.ordinal)
    }
    return bitset
}

internal enum class OpenFlags {
    CREAT,
    DIRECTORY,
    EXCL,
    TRUNC,
}

internal fun Iterable<OpenFlags>.toBitset(): Int {
    var bitset = 0
    for (flag in this) {
        bitset = bitset.or(1 shl flag.ordinal)
    }
    return bitset
}

internal typealias Fd = Int

internal enum class PrestatType {
    DIR,
    INVALID,
}

internal interface WasmMemoryAllocated {
    val address: Int
}

/**
 * See https://github.com/WebAssembly/WASI/blob/main/legacy/preview1/docs.md#-prestat-variant
 */
internal data class Prestat(
    val ptr: Pointer,
) : WasmMemoryAllocated {
    val type: PrestatType
        get() =
            if (ptr.loadByte().toInt() == 0) {
                PrestatType.DIR
            } else {
                PrestatType.INVALID
            }

    val nameLength: Int
        get() = ptr.loadInt(4)

    override val address: Int
        get() = ptr.address.toInt()
}

internal fun Prestat(allocator: MemoryAllocator): Prestat = Prestat(allocator.allocate(8))

/**
 * See https://github.com/WebAssembly/WASI/blob/main/legacy/preview1/docs.md#-filestat-record
 */
internal data class FileStat(
    val ptr: Pointer,
) : WasmMemoryAllocated {
    val dev: Long
        get() = ptr.loadLong()

    val ino: Long
        get() = ptr.loadLong(8)

    val filetype: FileType
        get() = FileType(ptr.loadByte(16))

    val linkCount: Long
        get() = ptr.loadLong(24)

    val filesize: Long
        get() = ptr.loadLong(32)

    val accessTime: Long
        get() = ptr.loadLong(40)

    val modificationTime: Long
        get() = ptr.loadLong(48)

    val changeTime: Long
        get() = ptr.loadLong(56)

    override val address: Int
        get() = ptr.address.toInt()
}

internal fun FileStat(allocator: MemoryAllocator): FileStat = FileStat(allocator.allocate(64))

/**
 * See https://github.com/WebAssembly/WASI/blob/main/legacy/preview1/docs.md#-ciovec-record
 */
internal data class Ciovec(
    val ptr: Pointer,
) : WasmMemoryAllocated {
    var buffer: Pointer
        get() = Pointer(ptr.loadInt().toUInt())
        set(value) = ptr.storeInt(value.address.toInt())

    var length: Int
        get() = ptr.loadInt(4)
        set(value) = ptr.storeInt(4, value)

    override val address: Int
        get() = ptr.address.toInt()
}

internal fun Ciovec(allocator: MemoryAllocator): Ciovec = Ciovec(allocator.allocate(8))
