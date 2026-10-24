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
import kotlin.test.Test

class TransientFailuresTest {

    @Test
    fun `should retry failing attempt once per operation until its failure is final`() {
        // given
        val failures = TransientFailures(maxAttempts = 2)
        var attempts = 0
        fun attempt() = failures.attempt("key") {
            attempts++
            TransientFailures.Outcome("failed", isFinal = false)
        }

        // when
        val first = failures.operation { listOf(attempt(), attempt()) }
        val second = failures.operation { attempt() }

        // then
        // the second attempt of the first operation is not retried
        assert(attempts == 2)
        assert(first.none { it.isFinal } && second.isFinal && second.value == "failed")
    }

    @Test
    fun `should retry failing attempt every time outside an operation until its failure is final`() {
        // given
        val failures = TransientFailures(maxAttempts = 2)
        var attempts = 0
        fun attempt() = failures.attempt("key") {
            attempts++
            TransientFailures.Outcome("failed", isFinal = false)
        }

        // when
        val outcomes = listOf(attempt(), attempt())

        // then
        // each attempt outside an operation is an operation of its own, never sharing the failure of another one
        assert(attempts == 2)
        assert(!outcomes[0].isFinal && outcomes[1].isFinal)
    }

    @Test
    fun `should not count failing attempts of overlapping operations as separate ones`() {
        // given
        val failures = TransientFailures(maxAttempts = 2)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        fun attempt() = failures.attempt("key") {
            attempts.incrementAndGet()
            TransientFailures.Outcome("failed", isFinal = false)
        }
        val started = java.util.concurrent.CountDownLatch(1)
        val failed = java.util.concurrent.CountDownLatch(1)
        var overlapping: TransientFailures.Outcome<String>? = null
        val other = kotlin.concurrent.thread {
            failures.operation {
                started.countDown()
                awaitOrFail(failed)
                overlapping = attempt()
            }
        }

        // when
        // the failure of the first operation happens while the other one is in progress, within the same outage
        val first = failures.operation {
            awaitOrFail(started)
            attempt().also { failed.countDown() }
        }
        joinOrFail(other)
        val later = failures.operation { attempt() }

        // then
        // the overlapping operation shares the failure, the later one retries, and deems it final
        assert(attempts.get() == 2)
        assert(!first.isFinal && overlapping?.isFinal == false && later.isFinal)
    }

    @Test
    fun `should deem failure final when failing attempts of overlapping operations interleave with later ones`() {
        // given
        val failures = TransientFailures(maxAttempts = 2)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // a slow attempt of an operation started before the first failure, failing only after a later attempt began
        val slow = kotlin.concurrent.thread {
            failures.operation {
                failures.attempt("key") {
                    attempts.incrementAndGet()
                    entered.countDown()
                    awaitOrFail(release)
                    TransientFailures.Outcome("failed", isFinal = false)
                }
            }
        }
        awaitOrFail(entered)

        // when
        val first = failures.operation {
            failures.attempt("key") {
                attempts.incrementAndGet()
                TransientFailures.Outcome("failed", isFinal = false)
            }
        }
        // started after the first failure, so counted, while the slow attempt fails meanwhile, within the first outage
        val later = failures.operation {
            failures.attempt("key") {
                attempts.incrementAndGet()
                release.countDown()
                joinOrFail(slow)
                TransientFailures.Outcome("failed", isFinal = false)
            }
        }

        // then
        assert(attempts.get() == 3)
        assert(!first.isFinal && later.isFinal)
    }

    @Test
    fun `should count transient failures per thread`() {
        // given
        val failures = TransientFailures()

        // when
        failures.mark()
        var otherCount = -1L
        joinOrFail(kotlin.concurrent.thread { otherCount = failures.count() })

        // then
        assert(failures.count() == 1L && otherCount == 0L)
    }

    @Test
    fun `should compute value again until its outcome is final`() {
        // given
        var computations = 0
        val value = FinalOnce {
            computations++
            TransientFailures.Outcome(computations, isFinal = computations == 2)
        }

        // when
        val values = List(3) { value.value }

        // then
        assert(values == listOf(1, 2, 2) && computations == 2)
    }

}
