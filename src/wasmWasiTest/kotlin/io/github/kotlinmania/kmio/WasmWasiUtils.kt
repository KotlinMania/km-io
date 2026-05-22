/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kotlinmania.kmio

import io.github.kotlinmania.kmio.Path
import io.github.kotlinmania.kmio.SystemTemporaryDirectory
import kotlin.random.Random

actual fun tempFileName(): String =
    Path(SystemTemporaryDirectory, Random.nextBytes(32).toHexString()).toString()
