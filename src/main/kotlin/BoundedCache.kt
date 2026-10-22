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
 * but never of its own key, which would recurse, or deadlock when two threads request each other's keys.
 * A failed computation is not cached, and runs again on the next request.
 */
internal class BoundedCache<K : Any, V>(private val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val entries = object : LinkedHashMap<K, Lazy<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Lazy<V>>): Boolean = size > capacity
    }

    fun getOrPut(key: K, compute: () -> V): V {
        val entry = synchronized(entries) {
            entries.getOrPut(key) { lazy(compute) }
        }
        return try {
            entry.value
        } catch (e: Throwable) {
            synchronized(entries) {
                entries.remove(key, entry)
            }
            throw e
        }
    }

}
