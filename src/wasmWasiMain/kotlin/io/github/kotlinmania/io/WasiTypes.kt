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
@Suppress("EnumNaming")
internal enum class Errno(val description: String) {
    success("No error occurred. System call completed successfully"),
    toobig("Argument list too long"),
    acces("Permission denied"),
    addrinuse("Address in use"),
    addrnotavail("Address not available"),
    afnosupport("Address family not supported"),
    again("Resource unavailable, or operation would block"),
    already("Connection already in progress"),
    badf("Bad file descriptor"),
    badmsg("Bad message"),
    busy("Device or resource busy"),
    canceled("Operation canceled"),
    child("No child processes"),
    connaborted("Connection aborted"),
    connrefused("Connection refused"),
    connreset("Connection reset"),
    deadlk("Resource deadlock would occur"),
    destaddrreq("Destination address required"),
    dom("Mathematics argument out of domain of function"),
    dquot("Reserved"),
    exist("File exists"),
    fault("Bad address"),
    fbig("File too large"),
    hostunreach("Host is unreachable"),
    idrm("Identifier removed"),
    ilseq("Illegal byte sequence"),
    inprogress("Operation in progress"),
    intr("Interrupted function"),
    inval("Invalid argument"),
    io("I/O error"),
    isconn("Socket is connected"),
    isdir("Is a directory"),
    loop("Too many levels of symbolic links"),
    mfile("File descriptor value too large"),
    mlink("Too many links"),
    msgsize("Message too large"),
    multihop("Reserved"),
    nametoolong("Filename too long"),
    netdown("Network is down"),
    netreset("Connection aborted by network"),
    netunreach("Network unreachable"),
    nfile("Too many files open in system"),
    nobufs("No buffer space available"),
    nodev("No such device"),
    noent("No such file or directory"),
    noexec("Executable file format error"),
    nolck("No locks available"),
    nolink("Reserved"),
    nomem("Not enough space"),
    nomsg("No message of the desired type"),
    noprotoopt("Protocol not available"),
    nospc("No space left on device"),
    nosys("Function not supported"),
    notconn("The socket is not connected"),
    notdir("Not a directory or a symbolic link to a directory"),
    notempty("Directory not empty"),
    notrecoverable("State not recoverable"),
    notsock("Not a socket"),
    notsup("Not supported, or operation not supported on socket"),
    notty("Inappropriate I/O control operation"),
    nxio("No such device or address"),
    overflow("Value too large to be stored in data type"),
    ownerdead("Previous owner died"),
    perm("Operation not permitted"),
    pipe("Broken pipe"),
    proto("Protocol error"),
    protonosupport("Protocol not supported"),
    prototype("Protocol wrong type for socket"),
    range("Result too large"),
    rofs("Read-only file system"),
    spipe("Invalid seek"),
    srch("No such process"),
    stale("Reserved"),
    timedout("Connection timed out"),
    txtbsy("Text file busy"),
    xdev("Cross-device link"),
    notcapable("Extension: Capabilities insufficient")
}

internal fun Errno(errno: Int): Errno {
    require(errno in Errno.entries.indices) { "Unknown errno: $errno" }
    return Errno.entries[errno]
}

@Suppress("EnumNaming")
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
