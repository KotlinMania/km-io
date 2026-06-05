/*
 * Copyright 2017-2024 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

package io.github.kotlinmania.io

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memset
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(UnsafeNumber::class)
class ByteStringAppleTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun toNSData() {
        val emptyData = ByteString().toNSData()
        assertEquals(0u, emptyData.length)

        val copy = ByteString(0, 1, 2, 3, 4, 5).toNSData()
        assertContentEquals(byteArrayOf(0, 1, 2, 3, 4, 5), copy.bytes!!.readBytes(copy.length.convert()))
    }

    @OptIn(BetaInteropApi::class)
    @Test
    fun fromNSData() {
        assertTrue(NSData().toByteString().isEmpty())
        val src =
            NSData.create(
                base64EncodedString = Base64.encode(byteArrayOf(0, 1, 2, 3, 4, 5)),
                options = 0u,
            )!!
        val copy = src.toByteString()
        assertContentEquals(byteArrayOf(0, 1, 2, 3, 4, 5), copy.toByteArray())
    }

    @OptIn(UnsafeByteStringApi::class, ExperimentalForeignApi::class)
    @Test
    fun toNSDataDataIntegrity() {
        val mutableArray = byteArrayOf(0, 0, 0, 0, 0, 0)
        // Don't try that at home, kids!
        val cursedString = UnsafeByteStringOperations.wrapUnsafe(mutableArray)
        val nsData = cursedString.toNSData()

        mutableArray.fill(42)
        // NSData should hold a copy
        assertContentEquals(ByteArray(6), nsData.bytes!!.readBytes(6))
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    @Test
    fun fromNSDataIntegrity() =
        memScoped {
            val length = 6
            val data = allocArray<ByteVar>(length)
            memset(data, 0, length.convert())

            val cursedData =
                NSData.create(
                    bytesNoCopy = data,
                    length = length.convert(),
                    freeWhenDone = false,
                )

            val byteString = cursedData.toByteString()
            memset(data, 42, length.convert())

            assertContentEquals(ByteArray(length), byteString.toByteArray())
        }
}
