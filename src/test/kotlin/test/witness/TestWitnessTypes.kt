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

package com.xemantic.kotlin.introspection.test.witness

import com.xemantic.kotlin.introspection.test.other.JavaPlatformBoxes

// An extension of a type parameter bounded recursively, like T : Comparable<T>, on a kotlin.* or java.* supertype
// referring to the type parameters of the class, is listed when a type the type parameter of the class can take,
// a witness, like String, makes it callable, in a package of its own, as these extensions are listed on any map there

fun <T : Comparable<T>> Map<T, T>.witnessSameComparables() {}

fun <K : Comparable<K>, V> Map<K, V>.witnessComparableKeys() {}

fun <T : Comparable<T>> Map<T, List<T>>.witnessComparableKeysOfLists() {}

// a V of String makes a HashMap<V, V>, or a Map<V, V>, a Map<T, T> of T : Comparable<T>,
// and a V of Nothing a Map<V, V> a Map<T, List<T>>

abstract class WitnessSameKeyHashMap<V> : HashMap<V, V>()

interface WitnessSameKeyMap<V> : Map<V, V>

// also the MutableMap<V!, V!> of a Java subclass

abstract class WitnessJavaSameKeyMap<V> : JavaPlatformBoxes.SameKeyMap<V>()

// only a V of Nothing is a V : List<V> making a Map<V, V> one

interface WitnessSelfListMap<V : List<V>> : Map<V, V>

// no V : Any makes a Map<V, V?> a Map<T, T>, a Map<String, String?> at best, which is a Map<K, V> of K : Comparable<K>

interface WitnessNullableValueMap<V : Any> : Map<V, V?>

// and no V a Map<V, Comparable<V>> a Map<T, T>, nor a Map<T, List<T>>

interface WitnessComparableValueMap<V> : Map<V, Comparable<V>>

// a K of String, and a V of Nothing, make a Map<K, V> a Map<T, List<T>>

interface WitnessKeyValueMap<K, V> : Map<K, V>

// no witness is given for a function type, like (V) -> Unit, which kotlinc matches in ways not modelled

fun <T : Comparable<T>> List<T>.witnessComparableElements() {}

interface WitnessFunctionList<V> : List<(V) -> Unit>

// nor Nothing for a type parameter passed through Java, the platform type V!, which kotlinc does not take Nothing! for,
// like the only V of V : WitnessFinal making a Map<V, V> a Map<K, V> of V : Comparable<V>

class WitnessFinal

fun <K, V : Comparable<V>> Map<K, V>.witnessComparableValues() {}

abstract class WitnessJavaFinalKeyMap<V : WitnessFinal> : JavaPlatformBoxes.SameKeyMap<V>()

interface WitnessFinalKeyMap<V : WitnessFinal> : Map<V, V>

// each type parameter is given its own witness, also of more of them than can all be combined

interface WitnessThreeKeyValueMap<A, K, V> : Map<K, V>
