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

import com.xemantic.kotlin.introspection.test.other.JavaGenericOuter

// Generic class with a variance-annotated type parameter
class GenericTestClass<K, out V>(val key: K, val value: V) {
    fun <R : Any> transform(block: (K, V) -> R): R = block(key, value)
    fun withKey(newKey: K): GenericTestClass<K, V> = GenericTestClass(newKey, value)
}

// Generic extension function
fun <K, V> GenericTestClass<K, V>.swap(): GenericTestClass<V, K> = GenericTestClass(value, key)

// Reified type parameter
inline fun <reified T> GenericTestClass<*, *>.valueAs(): T? = value as? T

// Type parameter with multiple upper bounds
fun <T> GenericTestClass<T, T>.longest(): T where T : CharSequence, T : Comparable<T> =
    if (key.length >= value.length) key else value

// Types of inner classes of a generic class carry the type arguments of the outer class
class GenericInnerOuter<A> {
    inner class Inner<B>
}

class NonGenericInnerOuter {
    inner class In<B>
}

class NonGenericInnerTypes {
    fun cursor(): NonGenericInnerOuter.In<String> = NonGenericInnerOuter().In()
    fun accept(cursor: NonGenericInnerOuter.In<String>?) {}
}

class GenericInnerTypes(
    val cursor: GenericInnerOuter<String>.Inner<Int>
) {
    fun cursor(): GenericInnerOuter<String>.Inner<Int> = cursor
    fun nullableCursor(): GenericInnerOuter<String>.Inner<Int>? = null
    fun accept(cursor: GenericInnerOuter<Long>.Inner<GenericInnerOuter<Int>.Inner<String>>) {}
}

// A Java inner class of a generic Java class as a supertype
class JavaGenericOuterString : JavaGenericOuter<String>() {
    inner class Sub : JavaGenericOuter<String>.Inner<Int>()
}

// A type parameter used as definitely not nullable, rendered so by the members inheriting it
// only when the type argument might be nullable, as `U & Any` of a `U : Any` does not compile
open class DefinitelyNonNullBox<T> {
    fun orDefault(value: T, default: T & Any): T & Any = value ?: default
    fun orNull(): T? = null
    fun <R : T> narrowed(value: R & Any): R & Any = value
    fun applyTo(block: (T & Any).() -> Unit) {}
}

class NullableDefinitelyNonNullBox<X> : DefinitelyNonNullBox<X>()

class NotNullDefinitelyNonNullBox<U : Any> : DefinitelyNonNullBox<U>()

class BoundNotNullDefinitelyNonNullBox<A : Any, B : A> : DefinitelyNonNullBox<B>()

class DefinitelyNonNullArgumentBox<U> : DefinitelyNonNullBox<U & Any>()

class StringDefinitelyNonNullBox : DefinitelyNonNullBox<String>()

class OuterNotNull<T : Any> {
    inner class InnerBox : DefinitelyNonNullBox<T>()
    inner class InnerMap : java.util.HashMap<String, T>()
}
