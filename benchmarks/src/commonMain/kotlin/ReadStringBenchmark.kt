package io.github.kotlinmania.io.benchmarks

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import io.github.kotlinmania.io.Buffer
import io.github.kotlinmania.io.Source
import io.github.kotlinmania.io.readCodePointValue
import io.github.kotlinmania.io.readString
import io.github.kotlinmania.io.writeCodePointValue
import io.github.kotlinmania.io.writeString
import kotlin.random.Random


@State(Scope.Benchmark)
open class ReadStringBenchmark() {

    @Param("16", "64", "512") // Fits into a single segment, so the benchmark does not measure segment boundaries crossing
    var size: Int = 0

    val buffer: Buffer = Buffer()

    @Setup
    fun setup() {
        val string = buildString { repeat(size) { append(('a'..'z').random()) } }
        buffer.writeString(string)
    }


    @Benchmark
    fun bufferReadString(): String {
        return buffer.copy().readString()
    }

    @Benchmark
    fun sourceReadString(): String {
        val source: Source = buffer.copy()
        return source.readString()
    }
}
