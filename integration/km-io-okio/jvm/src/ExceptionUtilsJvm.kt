/*
 * Copyright 2010-2024 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

package io.github.kotlinmania.io.okio

import io.github.kotlinmania.io.EOFException

internal actual fun Throwable.setCauseIfSupported(cause: Throwable?): Unit {
    initCause(cause)
}

internal actual fun newEOFExceptionWithCause(message: String?, cause: Throwable?): EOFException =
    EOFException(message).also { it.setCauseIfSupported(cause) }
