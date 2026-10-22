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

// Extensions declared across two files, in each file in an order different from the sorted one,
// so that both the declaration order and the classpath scan order of the files
// would show up in the rendered class if the extensions were not sorted.
// Names avoid the "TestClass" suffix to keep search result tests stable.
interface ExtensionOrderSupertype

class ExtensionOrderSubject(val value: Int) : ExtensionOrderSupertype

fun ExtensionOrderSubject.zulu(): Int = value

fun ExtensionOrderSubject.mike(): Int = value

val ExtensionOrderSubject.yankee: Int get() = value

val ExtensionOrderSubject.charlie: Int get() = value

// an overload of the one in ExtensionOrderB.kt, sorted before it by the declaring file
fun ExtensionOrderSubject.foxtrot(factor: Int): Int = value * factor

// the one of ExtensionOrderB.kt is on the class, listed before the ones on its supertypes,
// and its receiver, as well as its signature, sorts before this one,
// so this one is rendered before it only when the merged extensions are sorted by the declaring file
fun ExtensionOrderSupertype.alpha(): Int = 0
