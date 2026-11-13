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

package com.xemantic.kotlin.introspection.test.jdk

// a JDK interface named as a receiver, like java.util.List<Any>, is the Java one, of invariant type parameters for kotlinc,
// unlike the built-in type kotlin-reflect describes it as, like List<out E>, in a package of its own,
// as these extensions are listed on any class naming the same JDK interface there

abstract class JdkNamedList : java.util.List<String>

abstract class JdkNamedMap : java.util.Map<String, Int>

abstract class JdkNamedComparable : java.lang.Comparable<String>

fun java.util.List<Any>.anyJdkElements() {}

fun java.util.List<out Any>.outAnyJdkElements() {}

fun java.util.List<String>.stringJdkElements() {}

fun java.util.Map<String, Int?>.nullableJdkValues() {}

fun java.util.Map<String, Int>.intJdkValues() {}

fun java.lang.Comparable<Nothing>.comparableToNothing() {}

fun java.lang.Comparable<in String>.comparableToString() {}
