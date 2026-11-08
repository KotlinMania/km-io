/*
 * Copyright 2017-2023 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

@file:OptIn(UnsafeNumber::class, ExperimentalForeignApi::class)

package io.github.kotlinmania.io

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSMachPort
import platform.Foundation.NSOutputStream
import platform.Foundation.NSRunLoop
import platform.Foundation.NSStreamEvent
import platform.Foundation.NSStreamEventEndEncountered
import platform.Foundation.NSStreamEventErrorOccurred
import platform.Foundation.NSStreamEventHasBytesAvailable
import platform.Foundation.NSStreamEventHasSpaceAvailable
import platform.Foundation.NSStreamEventNone
import platform.Foundation.NSStreamEventOpenCompleted
import platform.Foundation.NSThread
import platform.Foundation.create
import platform.Foundation.data
import platform.Foundation.run
import platform.posix.memcpy
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(BetaInteropApi::class)
internal fun ByteArray.toNSData() =
    if (isNotEmpty()) {
        usePinned {
            NSData.create(bytes = it.addressOf(0), length = size.convert())
        }
    } else {
        NSData.data()
    }

fun NSData.toByteArray() =
    ByteArray(length.toInt()).apply {
        if (isNotEmpty()) {
            memcpy(refTo(0), bytes, length)
        }
    }

fun startRunLoop(name: String = "run-loop"): NSRunLoop {
    val created = Mutex(true)
    var runLoop: NSRunLoop? = null
    val thread =
        NSThread {
            val currentRunLoop = NSRunLoop.currentRunLoop
            runLoop = currentRunLoop
            currentRunLoop.addPort(NSMachPort(), NSDefaultRunLoopMode)
            created.unlock()
            currentRunLoop.run()
        }
    thread.name = name
    thread.start()
    runBlocking {
        created.lockWithTimeout()
    }
    return requireNotNull(runLoop)
}

suspend fun Mutex.lockWithTimeout(timeout: Duration = 5.seconds) {
    class MutexSource : Throwable()
    val source = MutexSource()
    try {
        withTimeout(timeout) { lock() }
    } catch (e: TimeoutCancellationException) {
        fail("Mutex never unlocked", source)
    }
}

fun NSStreamEvent.asString(): String =
    when (this) {
        NSStreamEventNone -> "NSStreamEventNone"
        NSStreamEventOpenCompleted -> "NSStreamEventOpenCompleted"
        NSStreamEventHasBytesAvailable -> "NSStreamEventHasBytesAvailable"
        NSStreamEventHasSpaceAvailable -> "NSStreamEventHasSpaceAvailable"
        NSStreamEventErrorOccurred -> "NSStreamEventErrorOccurred"
        NSStreamEventEndEncountered -> "NSStreamEventEndEncountered"
        else -> "Unknown event $this"
    }

fun ByteArray.write(to: NSOutputStream): Int {
    this.usePinned {
        return to.write(it.addressOf(0).reinterpret(), size.convert()).convert()
    }
}
