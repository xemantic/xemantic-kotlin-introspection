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

// The waits of the concurrency tests, bounded, so that a regression fails the test rather than hanging it,
// never telling anything about the time it takes otherwise.

private const val WAIT_SECONDS = 60L

/**
 * Waits for the [threads], failing rather than hanging if they never finish.
 */
internal fun joinOrFail(vararg threads: Thread) {
    threads.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }
    assert(threads.none { it.isAlive })
}

/**
 * Waits for the [latch], failing rather than hanging if it is never counted down.
 */
internal fun awaitOrFail(latch: CountDownLatch) {
    val isCountedDown = latch.await(WAIT_SECONDS, TimeUnit.SECONDS)
    assert(isCountedDown)
}
