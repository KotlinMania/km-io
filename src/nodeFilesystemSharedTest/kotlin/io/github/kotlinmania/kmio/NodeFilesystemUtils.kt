/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kotlinmania.kmio

import io.github.kotlinmania.kmio.fs
import io.github.kotlinmania.kmio.os
import io.github.kotlinmania.kmio.path
import kotlin.random.Random

actual fun tempFileName(): String {
    while (true) {
        val tmpdir = os.tmpdir()
        val filename = Random.nextBytes(32).toHexString()
        val fullpath = "$tmpdir${path.sep}$filename"

        if (fs.existsSync(fullpath)) {
            continue
        }
        return fullpath
    }
}
