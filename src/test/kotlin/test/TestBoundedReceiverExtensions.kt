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

import com.xemantic.kotlin.introspection.test.other.JavaBoundedReceiverBase
import com.xemantic.kotlin.introspection.test.other.JavaFunctionBase

// Extensions on type parameter receivers, listed for the classes satisfying their bounds,
// names avoid the "TestClass" suffix to keep search result tests stable
interface BoundedReceiverA

interface BoundedReceiverB

class BoundedReceiverOnlyA : BoundedReceiverA

class BoundedReceiverBoth : BoundedReceiverA, BoundedReceiverB

interface BoundedReceiverAB : BoundedReceiverA, BoundedReceiverB

class BoundedReceiverSubtypeOfAB : BoundedReceiverAB

fun <T : BoundedReceiverA> T.boundedByA(): T = this

fun <T : BoundedReceiverA?> T.nullableBoundedByA() {}

fun <U : BoundedReceiverA, T : U> T.boundedByTypeParameter() {}

fun <T> T.boundedByBoth() where T : BoundedReceiverA, T : BoundedReceiverB {}

val <T : BoundedReceiverA> T.boundedPropertyOfA: Int get() = 0

fun <T> T.unboundedReceiver() {}

// the context parameters and the deprecation of the extensions on a bounded type parameter, or on a function type,
// rendered like of any other extension
interface BoundedReceiverC

class BoundedReceiverOnlyC : BoundedReceiverC

@Deprecated("Use boundedByA")
context(log: Logger)
fun <T : BoundedReceiverC> T.deprecatedInContext() {}

context(log: Logger)
fun ((Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit).invokeInContext() {}

// the bounds satisfied through a Java supertype
class BoundedReceiverStream : java.io.FilterInputStream(null), BoundedReceiverA

fun <T> T.boundedByAAndCloseable() where T : BoundedReceiverA, T : java.io.Closeable {}

class BoundedReceiverThroughJava : JavaBoundedReceiverBase(), BoundedReceiverA

// the function type supertypes
class BoundedReceiverHandler : (String) -> Unit {
    override fun invoke(p1: String) {}
}

fun <P, R> ((P) -> R).handleAll(events: List<P>): List<R> = events.map(this)

class BoundedReceiverSuspendHandler : BoundedReceiverA, suspend () -> Unit {
    override suspend fun invoke() {}
}

fun <T> T.boundedByAAndSuspendFunction() where T : BoundedReceiverA, T : suspend () -> Unit {}

class BoundedReceiverJavaFunction : JavaFunctionBase()

// the bounds of the standard library satisfied by a class
class BoundedReceiverRange(override val start: Int, override val endInclusive: Int) : ClosedRange<Int>, Iterable<Int> {
    override fun iterator(): Iterator<Int> = (start..endInclusive).iterator()
}
