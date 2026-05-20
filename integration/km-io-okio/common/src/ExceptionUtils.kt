/*
 * Copyright 2010-2024 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

package io.github.kotlinmania.io.okio

internal expect fun Throwable.setCauseIfSupported(cause: Throwable?): Unit

internal expect fun newEOFExceptionWithCause(message: String?, cause: Throwable?): io.github.kotlinmania.io.EOFException

internal inline fun <T> withOkio2KxIOExceptionMapping(block: () -> T): T {
    try {
        return block()
    } catch (bypassIOE: io.github.kotlinmania.io.IOException) { // on JVM, io.github.kotlinmania.io.IOException and okio.IOException are the same
        throw bypassIOE
    } catch (bypassEOF: io.github.kotlinmania.io.EOFException) { // see above
        throw bypassEOF
    } catch (eofe: okio.EOFException) {
        throw newEOFExceptionWithCause(eofe.message, eofe)
    } catch (ioe: okio.IOException) {
        throw io.github.kotlinmania.io.IOException(ioe.message, ioe)
    }
}

internal inline fun <T> withKxIO2OkioExceptionMapping(block: () -> T): T {
    try {
        return block()
    } catch (bypassIOE: okio.IOException) {  // on JVM, io.github.kotlinmania.io.IOException and okio.IOException are the same
        throw bypassIOE
    } catch (bypassEOF: okio.EOFException) { // see above
        throw bypassEOF
    } catch (eofe: io.github.kotlinmania.io.EOFException) {
        throw okio.EOFException(buildString {
            if (eofe.message != null) {
                append(eofe.message)
            } else {
                append("Intercepted io.github.kotlinmania.io.EOFException")
            }
            append("\nCaused by: ")
            append(eofe.stackTraceToString())
        }).also { it.setCauseIfSupported(eofe) }
    } catch (ioe: io.github.kotlinmania.io.IOException) {
        throw okio.IOException(ioe.message, ioe)
    }
}
