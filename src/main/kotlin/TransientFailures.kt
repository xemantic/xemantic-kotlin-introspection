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

import java.util.concurrent.atomic.AtomicLong

/**
 * The failures which might be transient, like of a class lookup after an `IOException` of the class loader,
 * so that nothing computed from their outcomes is cached.
 *
 * They are tracked per thread, see [mark] and [count], so that a computation is cached unless
 * a transient failure happened on its own thread meanwhile, whatever fails on the other threads.
 *
 * A failing attempt, see [attempt], is retried at most once per [operation], like a resolve,
 * so that a resolve looking a failing class up for every member checked does not retry it every time,
 * and its failure is deemed final after [maxAttempts] failing attempts, so that it is cached after all.
 *
 * @param capacity the number of the failing attempts remembered, the least recently used ones.
 */
internal class TransientFailures(private val maxAttempts: Int = 2, private val capacity: Int = 1024) {

    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    /**
     * An outcome of an [attempt], [isFinal] unless it might be transient.
     */
    class Outcome<T>(val value: T, val isFinal: Boolean)

    private class Failure(val value: Any?, val attempts: Int, val operation: Long)

    private val counts = ThreadLocal.withInitial { longArrayOf(0) }

    private val operations = ThreadLocal.withInitial { longArrayOf(0) }

    private val operationIds = AtomicLong()

    private val failures = object : LinkedHashMap<String, Failure>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Failure>): Boolean = size > capacity
    }

    /**
     * The number of the transient failures marked on the current thread so far.
     */
    fun count(): Long = counts.get()[0]

    /**
     * Marks a transient failure, or an outcome computed from one, on the current thread.
     */
    fun mark() {
        counts.get()[0]++
    }

    /**
     * Runs an operation, like a resolve, within which a failing [attempt] is not retried.
     */
    fun <T> operation(block: () -> T): T {
        val current = operations.get()
        val outer = current[0]
        current[0] = operationIds.incrementAndGet()
        try {
            return block()
        } finally {
            current[0] = outer
        }
    }

    /**
     * The outcome of the [attempt] of the [key], or the transient one of the failing attempt of the same operation.
     * A transient outcome is final after [maxAttempts] failing attempts.
     * Neither marks a transient failure, which is up to the receiver of the outcome, see [mark].
     */
    fun <T> attempt(key: String, attempt: () -> Outcome<T>): Outcome<T> {
        val operation = operations.get()[0]
        synchronized(failures) { failures[key] }?.let { failure ->
            @Suppress("UNCHECKED_CAST")
            if (failure.operation == operation) return Outcome(failure.value as T, isFinal = false)
        }
        val outcome = attempt()
        synchronized(failures) {
            if (outcome.isFinal) {
                failures.remove(key)
                return outcome
            }
            val attempts = (failures[key]?.attempts ?: 0) + 1
            if (attempts >= maxAttempts) {
                failures.remove(key)
                return Outcome(outcome.value, isFinal = true)
            }
            failures[key] = Failure(outcome.value, attempts, operation)
        }
        return outcome
    }

}
