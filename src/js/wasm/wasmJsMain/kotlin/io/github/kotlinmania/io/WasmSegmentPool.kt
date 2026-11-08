/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kotlinmania.io

internal class RefCountingCopyTracker : SegmentCopyTracker() {
    private var copyCount: Int = 0

    override val shared: Boolean
        get() = copyCount > 0

    override fun addCopy() {
        copyCount++
    }

    override fun removeCopy(): Boolean {
        if (copyCount == 0) return false

        val updatedValue = --copyCount
        if (updatedValue >= 0) return true
        check(updatedValue == -1) { "Shared copies count is negative: ${updatedValue + 1}" }
        copyCount = 0
        return false
    }
}

internal actual object SegmentPool {
    actual val MAX_SIZE: Int = 64 * 1024 // 64 KiB.

    private var head: Segment? = null

    actual val byteCount: Int
        get() = head?.limit ?: 0

    actual fun take(): Segment {
        val first = head ?: return Segment.new()
        head = first.next
        first.next = null
        first.limit = 0
        return first
    }

    actual fun recycle(segment: Segment) {
        require(segment.next == null && segment.prev == null)
        if (segment.copyTracker?.removeCopy() == true) return

        val first = head
        val firstLimit = first?.limit ?: 0
        if (firstLimit >= MAX_SIZE) return

        segment.pos = 0
        segment.owner = true
        segment.next = first
        segment.limit = firstLimit + Segment.SIZE
        head = segment
    }

    actual fun tracker(): SegmentCopyTracker = RefCountingCopyTracker()
}
