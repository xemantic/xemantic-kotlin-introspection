/*
 * xemantic-kotlin-introspection - Kotlin API introspection of the classpath, rendering LLM-friendly signatures
 * Copyright (C) 2026  Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.kotlin.introspection

import com.xemantic.kotlin.test.assert
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// The threads and the waits of the concurrency tests, bounded, so that a regression fails the test rather than hanging it,
// never telling anything about the time it takes otherwise.

private val WAIT_NANOS = TimeUnit.SECONDS.toNanos(60)

/**
 * A thread of a concurrency test, started by [testThread], keeping the [failure] of its block, if any,
 * rethrown by [joinOrFail], rather than only printed, so that it fails the test.
 */
internal class TestThread(private val block: () -> Unit) : Thread() {

    @Volatile
    var failure: Throwable? = null
        private set

    override fun run() {
        try {
            block()
        } catch (e: Throwable) {
            failure = e
        }
    }

}

/**
 * Starts a [TestThread] running the [block].
 */
internal fun testThread(block: () -> Unit): TestThread = TestThread(block).apply { start() }

/**
 * Waits for the [threads], all within one deadline, failing rather than hanging if they never finish,
 * and rethrowing the first failure of their blocks, if any.
 */
internal fun joinOrFail(vararg threads: TestThread) {
    val deadline = System.nanoTime() + WAIT_NANOS
    threads.forEach { thread ->
        // at least a millisecond, as 0 waits forever
        thread.join(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1))
    }
    assert(threads.none { it.isAlive })
    threads.firstNotNullOfOrNull { it.failure }?.let { throw it }
}

/**
 * Waits for the [latch], failing rather than hanging if it is never counted down.
 */
internal fun awaitOrFail(latch: CountDownLatch) {
    val isCountedDown = latch.await(WAIT_NANOS, TimeUnit.NANOSECONDS)
    assert(isCountedDown)
}

/**
 * Polls the [condition] until it holds, or the wait is over, without failing then,
 * like for a state which a thread might never reach, telling whether it holds.
 */
internal fun pollUntil(condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + WAIT_NANOS
    while (!condition()) {
        if (System.nanoTime() - deadline >= 0) return false
        Thread.sleep(1)
    }
    return true
}

/**
 * Whether this thread waits for a value of a [BoundedCache] computed by another thread,
 * blocked on the lock of its lazy value, and not momentarily on anything else, like on loading a class,
 * to be polled, see [pollUntil], as it might never wait at all.
 */
internal fun Thread.isWaitingForComputation(): Boolean =
    state == Thread.State.BLOCKED && stackTrace.firstOrNull().let { frame ->
        frame != null && "Lazy" in frame.className && frame.methodName == "getValue"
    }
