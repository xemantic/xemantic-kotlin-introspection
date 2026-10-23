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
    fun `should count transient failures per thread`() {
        // given
        val failures = TransientFailures()

        // when
        failures.mark()
        var otherCount = -1L
        kotlin.concurrent.thread { otherCount = failures.count() }.join()

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
