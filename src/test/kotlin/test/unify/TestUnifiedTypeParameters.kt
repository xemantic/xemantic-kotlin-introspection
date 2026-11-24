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

package com.xemantic.kotlin.introspection.test.unify

import com.xemantic.kotlin.introspection.test.other.JavaUnifiedListPair

// A type parameter of a class matched against the types of an extension referring to its type parameters,
// in invariant positions, takes one type in all of them, like V of UnifiedSame<V> : UnifiedPair<V, V>,
// which is both T, and UnifiedWrap<T>, of UnifiedPair<T, UnifiedWrap<T>>, so that no T makes it callable

interface UnifiedPair<A, B>

interface UnifiedWrap<T>

interface UnifiedSame<V> : UnifiedPair<V, V>

interface UnifiedWrappedPair<V> : UnifiedPair<V, UnifiedWrap<V>>

fun <T> UnifiedPair<T, T>.unifiedSame() {}

fun <T> UnifiedPair<T, UnifiedWrap<T>>.unifiedWrapped() {}

fun <T> UnifiedPair<UnifiedWrap<T>, T>.unifiedWrapping() {}

fun <T> UnifiedPair<T, List<T>>.unifiedListed() {}

fun <T> UnifiedPair<UnifiedWrap<T>, List<T>>.unifiedWrappedListed() {}

// V of String? is T? of T of String?

fun <T> UnifiedPair<T?, T>.unifiedNullable() {}

// but no T : Any is T?

fun <T : Any> UnifiedPair<T?, T>.unifiedNotNullable() {}

// a type parameter taking a type of a class satisfies its bounds by that class,
// like T of UnifiedWrap<String>, which is no Comparable, while T of String is a CharSequence

fun <T : Comparable<T>> UnifiedPair<T, UnifiedWrap<String>>.unifiedComparableWrapped() {}

fun <T : CharSequence> UnifiedPair<T, String>.unifiedCharSequences() {}

// and the subtypes of a type parameter, like String of a covariant position, or W<V> of U : T,
// are subtypes of its bounds, like Enum<V> of a V which T is

interface UnifiedEnumKeys<V : Enum<V>> : Map<V, String>

fun <T> Map<T, T>.unifiedSameKeysAndValues() {}

fun <T, U : T> Map<T, U>.unifiedBoundedValues() {}

interface UnifiedEnumWrappedPair<V : Enum<V>> : UnifiedPair<V, UnifiedWrap<V>>

fun <T, U : T> UnifiedPair<T, U>.unifiedBoundedPair() {}

// a nullable subtype, like V? of U : T, makes the type parameter bounding it nullable, like V, which no T : Comparable<T> is

interface UnifiedNullablePair<V> : UnifiedPair<V?, V>

fun <T : Comparable<T>, U : T> UnifiedPair<U, T>.unifiedBoundedComparable() {}

fun <T, U : T> UnifiedPair<U, T>.unifiedBounded() {}

// a mutable collection is no read-only one, like V of UnifiedSame<V> both MutableList<T>, and List<T>, see #366,
// unlike a platform type of Java, (Mutable)List<V!>!, which is either

fun <T> UnifiedPair<MutableList<T>, List<T>>.unifiedMutableAndReadOnlyLists() {}

interface UnifiedJavaListPair<V> : JavaUnifiedListPair<V>

fun <T> UnifiedPair<MutableList<T>, MutableList<T>>.unifiedMutableLists() {}

// Nothing does not count as a type argument making an extension callable, see #366,
// like V of UnifiedSameKeyMap<V> : Map<V, V>, a T : Enum<T>, and a subtype of the final String, which only Nothing is both of,
// while V of String is a T : Comparable<T>

interface UnifiedSameKeyMap<V> : Map<V, V>

fun <T : Enum<T>> Map<T, String>.unifiedEnumKeysOfStrings() {}

fun <T : Comparable<T>> Map<T, String>.unifiedComparableKeysOfStrings() {}

// nor V of UnifiedSame<V> standing for the Nothing of UnifiedPair<Nothing, T>

fun <T> UnifiedPair<Nothing, T>.unifiedNothingFirst() {}

// nor V : CharSequence of a covariant position, a subtype of the final Int, or of Nothing, which only Nothing is,
// while V of String is a UnifiedProducer<String>

interface UnifiedProducer<out T>

interface UnifiedCharSequenceProducer<V : CharSequence> : UnifiedProducer<V>

fun UnifiedProducer<Int>.unifiedInts() {}

fun UnifiedProducer<String>.unifiedStrings() {}

fun UnifiedProducer<Nothing>.unifiedNothings() {}
