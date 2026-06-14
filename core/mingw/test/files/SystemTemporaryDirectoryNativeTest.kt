/*
 * Copyright 2010-2025 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

package io.github.kotlinmania.io.files

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemTemporaryDirectoryNativeTest {
    @Test
    fun prefersFirstNonEmptyEnvironmentVariable() {
        assertEquals(
            "/custom/tmpdir",
            systemTemporaryDirectoryPath(
                tmpDir = "/custom/tmpdir",
                tmp = "/custom/tmp",
                temp = "/custom/temp",
                tempDir = "/custom/tempdir",
                isWindowsHost = false,
            ),
        )
    }

    @Test
    fun fallsBackToUnixTmpDirectory() {
        assertEquals(
            "/tmp",
            systemTemporaryDirectoryPath(
                tmpDir = "",
                tmp = null,
                temp = "",
                tempDir = null,
                isWindowsHost = false,
            ),
        )
    }

    @Test
    fun fallsBackToWindowsTempDirectory() {
        assertEquals(
            "C:/Windows/Temp",
            systemTemporaryDirectoryPath(
                tmpDir = null,
                tmp = "",
                temp = null,
                tempDir = "",
                isWindowsHost = true,
            ),
        )
    }
}
