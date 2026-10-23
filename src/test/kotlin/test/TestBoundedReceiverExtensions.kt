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

// Extensions on type parameter receivers, listed for the classes satisfying their bounds,
// names avoid the "TestClass" suffix to keep search result tests stable
interface BoundedReceiverA

interface BoundedReceiverB

class BoundedReceiverOnlyA : BoundedReceiverA

class BoundedReceiverBoth : BoundedReceiverA, BoundedReceiverB

fun <T : BoundedReceiverA> T.boundedByA(): T = this

fun <T : BoundedReceiverA?> T.nullableBoundedByA() {}

fun <U : BoundedReceiverA, T : U> T.boundedByTypeParameter() {}

fun <T> T.boundedByBoth() where T : BoundedReceiverA, T : BoundedReceiverB {}

val <T : BoundedReceiverA> T.boundedPropertyOfA: Int get() = 0

fun <T> T.unboundedReceiver() {}
