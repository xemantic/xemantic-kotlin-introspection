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

import com.xemantic.kotlin.introspection.test.other.JavaFirstList
import com.xemantic.kotlin.introspection.test.other.JavaSameKeyMap

// User collections of the types of this package, whose extensions of this package on kotlin.* and java.* supertypes
// are listed, like next to them, unlike the ones of the standard library, or of the other packages

class CollectionOrder(val amount: Int)

class CollectionRefund(val amount: Int)

interface CollectionOrders : List<CollectionOrder>

class CollectionOrderList : ArrayList<CollectionOrder>()

class CollectionOrderMap : HashMap<String, CollectionOrder>()

class CollectionOrderJavaList : JavaFirstList<CollectionOrder>()

fun List<CollectionOrder>.totalAmount(): Int = sumOf { it.amount }

val Collection<CollectionOrder>.orderCount: Int get() = size

fun <T : List<CollectionOrder>> T.withLargestFirst(): T = this

fun List<CollectionRefund>.refundedAmount(): Int = sumOf { it.amount }

fun Map<String, CollectionOrder>.orderById(id: String): CollectionOrder? = get(id)

// a type parameter of the class repeated in the type arguments of a supertype takes one type in all its positions,
// so that an extension on Map<String, CollectionOrder> cannot be called on any HashMap<V, V>

class CollectionSameKeyMap<V> : HashMap<V, V>()

interface CollectionKeyed<K, out V>

class CollectionSameKeyed<V> : CollectionKeyed<V, V>

fun CollectionKeyed<String, CollectionOrder>.keyedOrder() {}

fun CollectionKeyed<String, CharSequence>.keyedText() {}

// no V makes a Map<V, Comparable<V>> a Map<T, T> of a T : Comparable<T>

interface CollectionComparableMap<V> : Map<V, Comparable<V>>

fun <T : Comparable<T>> Map<T, T>.sameComparables() {}

// a type parameter of the class passed through a Java supertype is a platform type, like V! of JavaSameKeyMap<V>,
// of either nullability in each of its positions, and one used as definitely not nullable, like V & Any, is not nullable,
// while the V of HashMap<V, V> is nullable in all its positions, or in none

class CollectionJavaSameKeyMap<V> : JavaSameKeyMap<V>()

interface CollectionNotNullValueMap<V> : Map<V, V & Any>

fun Map<String?, String>.nullableKeyMap() {}

// a JDK interface named as a supertype is the Java one, no List of Kotlin, of the supertypes of Java,
// like (Mutable)Collection<CollectionOrder!>, through java.util.SequencedCollection since JDK 21

abstract class CollectionOrderJdkList : java.util.List<CollectionOrder>
