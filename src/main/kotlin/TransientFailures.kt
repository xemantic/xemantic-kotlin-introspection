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
 * An operation started before the last failing attempt, like a resolve overlapping it on another thread,
 * is within the same outage, so it neither retries, nor counts as another failing attempt,
 * so that concurrent resolves do not deem a failure final after one outage.
 * An attempt outside an operation is an operation of its own, so it always retries.
 *
 * @param capacity the number of the failing attempts remembered, the least recently used ones, each with its outcome,
 *   like a class found only from its class file, kept until the key is attempted successfully, deemed final, or forgotten,
 *   so that with more failing keys a failure is forgotten, with its outcome, and its count, before it is deemed final,
 *   and then retried by each operation, like for an unbounded number of failing classes, which is not to be cached anyway.
 */
internal class TransientFailures(private val maxAttempts: Int = 2, private val capacity: Int = 1024) {

    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    /**
     * An outcome of an [attempt], [isFinal] unless it might be transient.
     */
    class Outcome<T>(val value: T, val isFinal: Boolean)

    /**
     * @param since the last operation started when the failure happened, see [operationIds],
     *   so that the operations up to it are within the same outage.
     */
    private class Failure(val value: Any?, val attempts: Int, val since: Long)

    private val counts = ThreadLocal.withInitial { longArrayOf(0) }

    // the operation in progress on the current thread, if any, see NO_OPERATION
    private val operations = ThreadLocal.withInitial { longArrayOf(NO_OPERATION) }

    // the ids of the operations, increasing in the order they start

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
     * The outcome of the [attempt] of the [key], or the transient one of the failing attempt of the same outage,
     * see [TransientFailures].
     * A transient outcome is final after [maxAttempts] failing attempts.
     * Neither marks a transient failure, which is up to the receiver of the outcome, see [mark].
     */
    fun <T> attempt(key: String, attempt: () -> Outcome<T>): Outcome<T> {
        val operation = operations.get()[0].takeIf { it != NO_OPERATION } ?: operationIds.incrementAndGet()
        synchronized(failures) { failures[key] }?.let { failure ->
            @Suppress("UNCHECKED_CAST")
            if (operation <= failure.since) return Outcome(failure.value as T, isFinal = false)
        }
        val outcome = attempt()
        synchronized(failures) {
            if (outcome.isFinal) {
                failures.remove(key)
                return outcome
            }
            val previous = failures[key]
            // like an attempt of an overlapping operation, failing at the same time
            val isSameOutage = previous != null && operation <= previous.since
            val attempts = if (isSameOutage) previous.attempts else (previous?.attempts ?: 0) + 1
            if (attempts >= maxAttempts) {
                failures.remove(key)
                return Outcome(outcome.value, isFinal = true)
            }
            failures[key] = Failure(outcome.value, attempts, since = operationIds.get())
        }
        return outcome
    }

    private companion object {

        // the operation of a thread outside any operation, never started, see operationIds
        const val NO_OPERATION = 0L

    }

}

/**
 * A value computed once its outcome is final, see [TransientFailures.Outcome],
 * and computed again on every access until then, like a class file which cannot be read, maybe only for now,
 * by a [compute] which is to retry a failing attempt at most once per operation, see [TransientFailures.attempt].
 */
internal class FinalOnce<T>(private val compute: () -> TransientFailures.Outcome<T>) {

    @Volatile
    private var final: TransientFailures.Outcome<T>? = null

    val outcome: TransientFailures.Outcome<T>
        get() {
            final?.let { return it }
            val outcome = compute()
            if (outcome.isFinal) final = outcome
            return outcome
        }

    val value: T get() = outcome.value

}
