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

/**
 * A thread-safe cache keeping at most [capacity] values, evicting the least recently used one.
 *
 * A value is computed once, also when requested concurrently: the other requests of the same key wait for it.
 * The computation runs outside the lock of the cache, so it may request the values of other keys,
 * but never of its own key, directly or through the computations of other keys:
 * such a request fails with an [IllegalStateException] on the thread computing the key,
 * while two threads requesting each other's keys at the same time would still deadlock.
 * A failed computation is not cached, and runs again on the next request,
 * just like a computation telling that its value is not cacheable, see [getOrCompute].
 */
internal class BoundedCache<K : Any, V>(private val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /**
     * A computed [value], cached only if [isCacheable], unlike one computed after a transient failure.
     */
    class Computed<V>(val value: V, val isCacheable: Boolean)

    private val entries = object : LinkedHashMap<K, Lazy<Computed<V>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Lazy<Computed<V>>>): Boolean =
            size > capacity
    }

    // the keys requested on the current thread, and not yet computed, telling a recursive request
    private val requestedKeys = ThreadLocal.withInitial { mutableSetOf<K>() }

    fun getOrPut(key: K, compute: () -> V): V = getOrCompute(key) { Computed(compute(), isCacheable = true) }

    /**
     * The value of the [key], computed if absent, and then cached, unless it is not cacheable,
     * while the requests waiting for the same computation receive it anyway.
     */
    fun getOrCompute(key: K, compute: () -> Computed<V>): V {
        val requested = requestedKeys.get()
        check(requested.add(key)) { "The computation of $key requests its own value" }
        try {
            val entry = synchronized(entries) {
                entries.getOrPut(key) { lazy(compute) }
            }
            val computed = try {
                entry.value
            } catch (e: Throwable) {
                synchronized(entries) {
                    entries.remove(key, entry)
                }
                throw e
            }
            if (!computed.isCacheable) {
                synchronized(entries) {
                    entries.remove(key, entry)
                }
            }
            return computed.value
        } finally {
            requested.remove(key)
        }
    }

}
