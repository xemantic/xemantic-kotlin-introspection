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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BoundedCacheTest {

    @Test
    fun `should compute value once when requested concurrently`() {
        // given
        val cache = BoundedCache<String, String>(capacity = 10)
        val computations = AtomicInteger()
        val computing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = thread {
            cache.getOrPut("key") {
                computations.incrementAndGet()
                computing.countDown()
                release.await()
                "first"
            }
        }
        computing.await()

        // when
        var secondValue: String? = null
        val second = thread {
            secondValue = cache.getOrPut("key") {
                computations.incrementAndGet()
                "second"
            }
        }
        // the second request either waits for the first computation, or, if it is not shared, completes on its own,
        // told by the stack of the second thread, as it might also wait momentarily for anything else
        while (second.isAlive && !second.isWaitingForComputation()) {
            Thread.onSpinWait()
        }
        release.countDown()
        first.join()
        second.join()

        // then
        assert(computations.get() == 1 && secondValue == "first")
    }

    /**
     * Whether this thread is blocked within the cache, like on the lock of the value computed by another thread,
     * and not momentarily on anything else, like on loading a class.
     */
    private fun Thread.isWaitingForComputation(): Boolean =
        state == Thread.State.BLOCKED && stackTrace.firstOrNull().let { frame ->
            frame != null && (frame.className == BoundedCache::class.java.name || frame.methodName == "getValue")
        }

    @Test
    fun `should evict least recently used value beyond capacity`() {
        // given
        val cache = BoundedCache<String, String>(capacity = 2)
        cache.getOrPut("a") { "a1" }
        cache.getOrPut("b") { "b1" }
        cache.getOrPut("a") { "a2" }

        // when
        cache.getOrPut("c") { "c1" }

        // then
        val a = cache.getOrPut("a") { "a3" }
        val b = cache.getOrPut("b") { "b2" }
        assert(a == "a1" && b == "b2")
    }

    @Test
    fun `should compute value again after failed computation`() {
        // given
        val cache = BoundedCache<String, String>(capacity = 10)
        assertFailsWith<IllegalStateException> {
            cache.getOrPut("key") { error("failed") }
        }

        // when
        val value = cache.getOrPut("key") { "computed" }

        // then
        assert(value == "computed")
    }

    @Test
    fun `should fail computation requesting its own key`() {
        // given
        val cache = BoundedCache<String, String>(capacity = 10)

        // when
        val failure = assertFailsWith<IllegalStateException> {
            cache.getOrPut("key") {
                cache.getOrPut("other") {
                    cache.getOrPut("key") { "recursive" }
                }
            }
        }

        // then
        assert(failure.message == "The computation of key requests its own value")
        assert(cache.getOrPut("key") { "computed" } == "computed")
    }

    @Test
    fun `should compute value again after computation telling it is not cacheable`() {
        // given
        val cache = BoundedCache<String, String>(capacity = 10)
        val transient = cache.getOrCompute("key") { BoundedCache.Computed("transient", isCacheable = false) }

        // when
        val value = cache.getOrPut("key") { "computed" }

        // then
        assert(transient == "transient" && value == "computed")
    }

}
