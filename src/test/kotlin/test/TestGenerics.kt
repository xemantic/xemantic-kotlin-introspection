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

package com.xemantic.kotlin.introspection.test

// Generic class with a variance-annotated type parameter
class GenericTestClass<K, out V>(val key: K, val value: V) {
    fun <R : Any> transform(block: (K, V) -> R): R = block(key, value)
}

// Generic extension function
fun <K, V> GenericTestClass<K, V>.swap(): GenericTestClass<V, K> = GenericTestClass(value, key)

// Reified type parameter
inline fun <reified T> GenericTestClass<*, *>.valueAs(): T? = value as? T

// Type parameter with multiple upper bounds
fun <T> GenericTestClass<T, T>.longest(): T where T : CharSequence, T : Comparable<T> =
    if (key.length >= value.length) key else value
