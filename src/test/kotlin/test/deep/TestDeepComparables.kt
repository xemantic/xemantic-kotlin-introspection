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

package com.xemantic.kotlin.introspection.test.deep

// a class comparable to what is comparable to it, see #343, as the receiver of an extension on a type parameter comparable
// to itself, see #376: kotlinc incorporates the type it is comparable to, Comparable<DeepComparable>, into the bound,
// asking whether DeepComparable is a Comparable<DeepComparable>, endlessly, until it gives up with "Arguments depth is too high",
// so that x.deepSelf() does not compile, nor x.deepBounded(), nor x.coerceAtLeast(x), also of a subclass,
// while x.deepAny() does
interface DeepComparable : Comparable<Comparable<DeepComparable>>

interface DeepComparableSub : DeepComparable

fun <T : Comparable<T>> T.deepSelf() {}

fun <A : Comparable<A>, B : A> B.deepBounded() {}

fun <T> T.deepAny() {}

// an intersection the type parameter is inferred as, see #376: kotlinc infers T as String & DeepComparable,
// and asks whether it is comparable to itself by each of its types, endlessly by DeepComparable
interface DeepSink<in A, in B>

fun <T : Comparable<T>> DeepSink<T, T>.deepSinkOfComparable() {}

interface DeepStringSink : DeepSink<String, DeepComparable>

// an intersection of upper types only, see #376: kotlinc infers T as DeepComparable & Comparable<DeepComparable>,
// telling first whether one of them is a subtype of the other, endlessly, so that neither x.deepSinkOfAny() compiles,
// nor x.deepSinkOfChars(), while both do on a DeepStringSink, though String & DeepComparable is an empty intersection
fun <T> DeepSink<T, T>.deepSinkOfAny() {}

fun <T : CharSequence> DeepSink<T, T>.deepSinkOfChars() {}

interface DeepComparableSink : DeepSink<DeepComparable, Comparable<DeepComparable>>

// two types the type parameter is inferred from, see #376: kotlinc tells their common supertype, asking whether
// DeepComparable is a Comparable<DeepComparable>, endlessly
interface DeepSource<out A, out B>

fun <T> DeepSource<T, T>.deepSourceOfAny() {}

interface DeepComparableSource : DeepSource<DeepComparable, Comparable<DeepComparable>>

// a witness of a type parameter of the class bounded by itself, see #334, like DeepComparable of the extension for V : Comparable<V>,
// is none, as kotlinc fails to tell whether it satisfies the bound, see #376, while String is, x.deepSame() compiling for a DeepMap<String>
interface DeepMap<V : Comparable<V>> : Map<V, V>

fun <T : Comparable<T>> Map<T, DeepComparable>.deepValues() {}

fun <T : Comparable<T>> Map<T, T>.deepSame() {}
