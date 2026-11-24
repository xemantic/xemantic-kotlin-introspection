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

// tests relocate both classes, like by shading, which keeps the original names in the metadata,
// so that the class is found by its located name, giving the witnesses, like String of V, see #362

interface RelocatedSameKeyMap<V> : Map<V, V>

fun <T : Comparable<T>> Map<T, T>.relocatedSameComparables() {}

// tests relocate only the dependency bounding the type parameter of a class which is not relocated,
// so that the bound is not found by its metadata name, while the generic signature of the class resolves it,
// giving the witnesses, like RelocatedDependencyBound of V

interface RelocatedDependencyBound : Comparable<RelocatedDependencyBound>

interface RelocatedDependencyBoundedMap<V : RelocatedDependencyBound> : Map<V, V>
