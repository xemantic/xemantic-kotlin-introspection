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

package com.xemantic.kotlin.introspection.isolated

// a type parameter bounded by Absent withholds the witnesses of the type parameters of the class, see #362,
// like String of V, and Absent of W, which make a Map<V, V> a Map<T, T> of T : Comparable<T> when Absent is there,
// while the class itself, whose supertypes do not refer to Absent, can be reflected without it
interface AbsentBoundedKeyMap<V, W : Absent> : Map<V, V>

fun <T : Comparable<T>> Map<T, T>.absentSameComparables() {}

// Absent only nested in a type argument of a bound, which the JDK reifies only when asked for, see #386,
// withholds the witnesses as well, like String of V, and List<Absent> of W
interface AbsentNestedBoundKeyMap<V, W : List<Absent>> : Map<V, V>

// Absent only in a bound of the extension, the class itself not referring to it, see #386:
// when Absent is missing, kotlinc rejects the call, as it cannot access the class
interface AbsentFreeKeyMap<V> : Map<V, V>

fun <T : Comparable<T>, A : Absent> Map<T, T>.absentBoundedSameComparables(absent: A) {}

// a supertype of the class itself naming Absent, not one the receiver is matched against, see #386:
// the class is listed without Absent, its witnesses withheld, as its generic signature cannot be reified
interface AbsentComparableKeyMap<V> : Map<V, V>, Comparable<Absent>

abstract class AbsentListKeyMap<V> : ArrayList<Absent>(), Map<V, V>
