/*
 * Copyright 2017-2023 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.kotlinmania.io

import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.plusAssign
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.native.concurrent.ThreadLocal

@OptIn(ExperimentalAtomicApi::class)
internal class RefCountingCopyTracker : SegmentCopyTracker() {
    private val copyCount = AtomicInt(0)

    override val shared: Boolean
        get() = copyCount.load() > 0

    override fun addCopy() {
        copyCount += 1
    }

    override fun removeCopy(): Boolean {
        if (copyCount.load() == 0) return false

        val updatedValue = copyCount.decrementAndFetch()
        if (updatedValue >= 0) return true
        check(updatedValue == -1) { "Shared copies count is negative: ${updatedValue + 1}" }
        copyCount.store(0)
        return false
    }
}

@ThreadLocal
private var currentThreadBucketHash: Int = -1

@OptIn(ExperimentalAtomicApi::class, ExperimentalNativeApi::class)
internal actual object SegmentPool {
    actual val MAX_SIZE: Int = 64 * 1024 // 64 KiB.

    private val LOCK =
        Segment.new(ByteArray(0), pos = 0, limit = 0, copyTracker = null, owner = false)

    internal val HASH_BUCKET_COUNT: Int =
        (Platform.getAvailableProcessors().coerceAtLeast(1) * 2 - 1).takeHighestOneBit()

    private val HASH_BUCKET_COUNT_L2: Int = (HASH_BUCKET_COUNT / 2).coerceAtLeast(1)

    internal const val SECOND_LEVEL_POOL_TOTAL_SIZE: Int = 4194304 // 4MB

    private val SECOND_LEVEL_POOL_BUCKET_SIZE: Int =
        (SECOND_LEVEL_POOL_TOTAL_SIZE / HASH_BUCKET_COUNT_L2).coerceAtLeast(Segment.SIZE)

    private val nextThreadHash = AtomicInt(0)
    private val hashBuckets: AtomicArray<Segment?> = AtomicArray(HASH_BUCKET_COUNT) { null }
    private val hashBucketsL2: AtomicArray<Segment?> = AtomicArray(HASH_BUCKET_COUNT_L2) { null }

    actual val byteCount: Int
        get() {
            val first = hashBuckets.loadAt(l1BucketId()) ?: return 0
            return first.limit
        }

    actual fun take(): Segment {
        val buckets = hashBuckets
        val bucketId = l1BucketId()

        while (true) {
            when (val first = buckets.exchangeAt(bucketId, LOCK)) {
                LOCK -> continue

                null -> {
                    buckets.storeAt(bucketId, null)
                    if (SECOND_LEVEL_POOL_TOTAL_SIZE > 0) {
                        return takeL2()
                    }
                    return Segment.new()
                }

                else -> {
                    buckets.storeAt(bucketId, first.next)
                    first.next = null
                    first.limit = 0
                    return first
                }
            }
        }
    }

    private fun takeL2(): Segment {
        val buckets = hashBucketsL2
        var bucket = l2BucketId()
        var attempts = 0
        while (true) {
            when (val first = buckets.exchangeAt(bucket, LOCK)) {
                LOCK -> continue

                null -> {
                    buckets.storeAt(bucket, null)
                    if (attempts < HASH_BUCKET_COUNT_L2) {
                        bucket = (bucket + 1) and (HASH_BUCKET_COUNT_L2 - 1)
                        attempts++
                        continue
                    }
                    return Segment.new()
                }

                else -> {
                    buckets.storeAt(bucket, first.next)
                    first.next = null
                    first.limit = 0
                    return first
                }
            }
        }
    }

    actual fun recycle(segment: Segment) {
        require(segment.next == null && segment.prev == null)
        if (segment.copyTracker?.removeCopy() == true) return

        val buckets = hashBuckets
        val bucketId = l1BucketId()

        segment.pos = 0
        segment.owner = true

        while (true) {
            val first = buckets.loadAt(bucketId)
            if (first === LOCK) continue
            val firstLimit = first?.limit ?: 0
            if (firstLimit >= MAX_SIZE) {
                if (SECOND_LEVEL_POOL_TOTAL_SIZE > 0) {
                    recycleL2(segment)
                }
                return
            }

            segment.next = first
            segment.limit = firstLimit + Segment.SIZE

            if (buckets.compareAndSetAt(bucketId, first, segment)) {
                return
            }
        }
    }

    private fun recycleL2(segment: Segment) {
        segment.pos = 0
        segment.owner = true

        var bucket = l2BucketId()
        val buckets = hashBucketsL2
        var attempts = 0

        while (true) {
            val first = buckets.loadAt(bucket)
            if (first === LOCK) continue
            val firstLimit = first?.limit ?: 0
            if (firstLimit + Segment.SIZE > SECOND_LEVEL_POOL_BUCKET_SIZE) {
                if (attempts < HASH_BUCKET_COUNT_L2) {
                    attempts++
                    bucket = (bucket + 1) and (HASH_BUCKET_COUNT_L2 - 1)
                    continue
                }
                return
            }

            segment.next = first
            segment.limit = firstLimit + Segment.SIZE

            if (buckets.compareAndSetAt(bucket, first, segment)) {
                return
            }
        }
    }

    actual fun tracker(): SegmentCopyTracker = RefCountingCopyTracker()

    private fun l1BucketId(): Int = bucketId(HASH_BUCKET_COUNT - 1)

    private fun l2BucketId(): Int = bucketId(HASH_BUCKET_COUNT_L2 - 1)

    private fun bucketId(mask: Int): Int {
        var id = currentThreadBucketHash
        if (id == -1) {
            id = nextThreadHash.incrementAndFetch() and Int.MAX_VALUE
            currentThreadBucketHash = id
        }
        return id and mask
    }
}
