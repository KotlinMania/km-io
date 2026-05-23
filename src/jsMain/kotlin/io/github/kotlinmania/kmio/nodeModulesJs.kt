/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kotlinmania.kmio

internal actual val path: NodeJsPathInterface by lazy {
    try {
        val pathMod = js("require('path')")
        if (pathMod.default !== undefined && pathMod.default.resolve !== undefined) pathMod.default else pathMod
    } catch (e: Throwable) {
        throw UnsupportedOperationException("Module 'path' could not be imported", e)
    }
}

internal actual val fs: Fs by lazy {
    try {
        val fsMod = js("require('fs')")
        if (fsMod.default !== undefined && fsMod.default.existsSync !== undefined) fsMod.default else if (fsMod.fs !== undefined) fsMod.fs else fsMod
    } catch (e: Throwable) {
        throw UnsupportedOperationException("Module 'fs' could not be imported", e)
    }
}

internal actual fun callReaddirSync(directory: String): List<String> {
    val children = fs.asDynamic().readdirSync(directory) as Array<String>
    return children.toList()
}

internal actual val os: Os by lazy {
    try {
        val osMod = js("require('os')")
        if (osMod.default !== undefined && osMod.default.tmpdir !== undefined) osMod.default else osMod
    } catch (e: Throwable) {
        throw UnsupportedOperationException("Module 'os' could not be imported", e)
    }
}

internal actual val buffer: NodeJsBufferModule by lazy {
    try {
        val bufMod = js("require('buffer')")
        if (bufMod.default !== undefined && bufMod.default.Buffer !== undefined) bufMod.default else bufMod
    } catch (e: Throwable) {
        throw UnsupportedOperationException("Module 'buffer' could not be imported", e)
    }
}

