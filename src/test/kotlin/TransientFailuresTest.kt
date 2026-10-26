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
    fun `should retry failing attempt every time outside an operation, never deeming its failure final`() {
        // given
        val failures = TransientFailures(maxAttempts = 2)
        var attempts = 0
        fun attempt() = failures.attempt("key") {
            attempts++
            TransientFailures.Outcome("failed", isFinal = false)
        }

        // when
        // like two lookups within one call, not wrapped in an operation, failing within the same outage
        val outcomes = listOf(attempt(), attempt(), attempt())
        val within = failures.operation { attempt() }

        // then
        // never sharing the failure of another attempt, nor counted, as the outage of an attempt outside an operation is unknown
        assert(attempts == 4)
        assert(outcomes.none { it.isFinal } && !within.isFinal)
    }

    @Test
    fun `should neither count nor end failure by indecisive outcome`() {
        // given
        val failures = TransientFailures(maxAttempts = 2)
        var attempts = 0
        fun attempt(outcome: TransientFailures.Outcome<String>) = failures.operation {
            failures.attempt("key") {
                attempts++
                outcome
            }
        }

        // when
        val first = attempt(TransientFailures.Outcome("failed", isFinal = false))
        // like a miss of a class lookup not telling a missing class apart from one failing to load
        val indecisive = listOf(
            attempt(TransientFailures.Outcome("failing", isFinal = false, isDecisive = false)),
            attempt(TransientFailures.Outcome("missing", isFinal = true, isDecisive = false))
        )
        val last = attempt(TransientFailures.Outcome("failed", isFinal = false))

        // then
        // the indecisive outcomes are received as they are, while the failure is deemed final by the second failing attempt
        assert(attempts == 4)
        assert(!first.isFinal && !indecisive[0].isFinal && indecisive[1].isFinal && last.isFinal)
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
        val other = testThread {
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
        val slow = testThread {
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
        joinOrFail(testThread { otherCount = failures.count() })

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
