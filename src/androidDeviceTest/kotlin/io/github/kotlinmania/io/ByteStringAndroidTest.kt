/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kotlinmania.io

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Set of tests covering Android-target [ByteString] extensions; the
 * `bytestring/android/src/ByteStringAndroidExt.kt` file is byte-identical
 * to its JVM counterpart, so this is the JVM `ByteStringJvmTest` mirrored
 * onto the Android host test source set so that Kover counts both copies
 * of the file as covered.
 */
class ByteStringAndroidTest {
    @Test
    fun createFromString() {
        val str = "hello"

        assertEquals(ByteString(byteArrayOf(0x68, 0x65, 0x6c, 0x6c, 0x6f)), str.encodeToByteString(Charsets.UTF_8))
        assertEquals(
            ByteString(
                byteArrayOf(
                    0,
                    0,
                    0,
                    0x68,
                    0,
                    0,
                    0,
                    0x65,
                    0,
                    0,
                    0,
                    0x6c,
                    0,
                    0,
                    0,
                    0x6c,
                    0,
                    0,
                    0,
                    0x6f,
                ),
            ),
            str.encodeToByteString(Charsets.UTF_32),
        )
    }

    @Test
    fun decodeToString() {
        assertEquals(
            "Ϭ",
            ByteString(0xfeU.toByte(), 0xffU.toByte(), 0x03, 0xecU.toByte()).decodeToString(Charsets.UTF_16),
        )

        assertEquals("123", ByteString("123".encodeToByteArray()).decodeToString(Charsets.UTF_8))
    }
}
