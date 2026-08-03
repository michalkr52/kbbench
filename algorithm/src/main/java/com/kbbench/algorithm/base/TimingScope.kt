package com.kbbench.algorithm.base

/**
 * Runs [block] and returns its result paired with the elapsed wall-clock time in milliseconds,
 * measured via [System.nanoTime].
 */
internal inline fun <T> measureMs(block: () -> T): Pair<T, Long> {
    val start = System.nanoTime()
    val result = block()
    val elapsedMs = (System.nanoTime() - start) / 1_000_000L
    return result to elapsedMs
}


