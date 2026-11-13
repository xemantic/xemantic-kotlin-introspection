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

package com.xemantic.kotlin.introspection.test.nullable

// a nullable type parameter of the class, like V? of java.util.List<V?> named as a supertype, is no definitely not nullable type
// of an extension of its package, like T & Any, in a package of its own, as these extensions are listed on any collection there

abstract class NullableJdkList<V> : java.util.List<V?>

fun <T> Collection<T & Any>.notNullElements() {}

fun Collection<*>.anyElements() {}
