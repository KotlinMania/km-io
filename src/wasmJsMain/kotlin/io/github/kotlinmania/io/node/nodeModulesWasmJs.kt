/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kotlinmania.io.node

/**
 * Looks up a Node.js built-in module through `process.getBuiltinModule`, which is
 * available as a plain global in Node.js and does not depend on the surrounding
 * script being an ES module. Returns `null` outside Node.js (for example in a
 * browser) or when the module does not exist.
 */
@JsFun(
    """
    (mod) => {
        try {
            const p = globalThis.process;
            if (!p || !p.versions || !p.versions.node || typeof p.getBuiltinModule !== 'function') return null;
            const m = p.getBuiltinModule(mod);
            return m ? m : null;
        } catch (e) {
            return null;
        }
    }
""",
)
internal external fun requireModule(mod: String): JsAny?

internal fun loadModule(name: String): JsAny {
    val mod = requireModule(name) ?: throw UnsupportedOperationException("Module '$name' could not be imported")
    return mod
}

internal actual val buffer: NodeJsBufferModule by lazy {
    loadModule("buffer").unsafeCast()
}

internal actual val os: Os by lazy {
    loadModule("os").unsafeCast()
}

internal actual val path: NodeJsPathInterface by lazy {
    loadModule("path").unsafeCast()
}

internal actual val fs: Fs by lazy {
    loadModule("fs").unsafeCast()
}
