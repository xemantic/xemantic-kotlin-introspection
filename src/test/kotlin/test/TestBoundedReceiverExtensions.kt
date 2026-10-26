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

// the extensions on a type parameter next to an overload of a class not pinning the type parameter,
// which can be inferred as a supertype, like BoundedReceiverD, so that none of them is shadowed
interface BoundedReceiverD

fun <T : BoundedReceiverD> T.nextToMember(): T = this

fun <T : BoundedReceiverD> T.nextToExtension(value: T): T = this

fun <T : BoundedReceiverD> T.nextToSubtypeParameter(value: T) {}

fun <T : BoundedReceiverD?> T.nextToNullableBound(value: T) {}

val <T : BoundedReceiverD> T.nextToProperty: Int get() = 0

class BoundedReceiverShadowing : BoundedReceiverD {
    val nextToProperty: Int = 1
    fun nextToMember(): BoundedReceiverShadowing = this
    fun nextToSubtypeParameter(value: BoundedReceiverShadowing) {}
    fun nextToNullableBound(value: BoundedReceiverShadowing) {}
}

fun BoundedReceiverShadowing.nextToExtension(value: BoundedReceiverShadowing): BoundedReceiverShadowing = value

// an extension on a supertype never shadows a more specific one on a type parameter
interface BoundedReceiverShape

interface BoundedReceiverNamed

class BoundedReceiverCircle : BoundedReceiverShape, BoundedReceiverNamed

fun BoundedReceiverShape.describe(): String = ""

@JvmName("describeNamed")
fun <T> T.describe(): String where T : BoundedReceiverShape, T : BoundedReceiverNamed = ""

val BoundedReceiverShape.label: String get() = ""

val <T> T.label: String where T : BoundedReceiverShape, T : BoundedReceiverNamed
    @JvmName("getNamedLabel") get() = ""

// the classes comparable to themselves, satisfying the bound T : Comparable<T>, or not
class BoundedReceiverVersion(val major: Int) : Comparable<BoundedReceiverVersion> {
    override fun compareTo(other: BoundedReceiverVersion): Int = major.compareTo(other.major)
    // shadows T.coerceAtMost(maximumValue: T), as T is pinned to this final class, comparable only to itself
    fun coerceAtMost(maximumValue: BoundedReceiverVersion): BoundedReceiverVersion = this
}

open class BoundedReceiverVersionBase : Comparable<BoundedReceiverVersionBase> {
    override fun compareTo(other: BoundedReceiverVersionBase): Int = 0
}

class BoundedReceiverPatchVersion : BoundedReceiverVersionBase() {
    // does not shadow T.coerceAtMost(maximumValue: T), as T can be inferred as BoundedReceiverVersionBase
    fun coerceAtMost(maximumValue: BoundedReceiverPatchVersion): BoundedReceiverPatchVersion = this
}

class BoundedReceiverComparableToOther : Comparable<BoundedReceiverVersion> {
    override fun compareTo(other: BoundedReceiverVersion): Int = 0
}

// the bounds of the standard library satisfied by a class
class BoundedReceiverRange(override val start: Int, override val endInclusive: Int) : ClosedRange<Int>, Iterable<Int> {
    override fun iterator(): Iterator<Int> = (start..endInclusive).iterator()
}

// the classes comparable to themselves through a generic supertype, satisfying the bound T : Comparable<T>
abstract class BoundedReceiverEntity<E : BoundedReceiverEntity<E>> : Comparable<E>

class BoundedReceiverUser : BoundedReceiverEntity<BoundedReceiverUser>() {
    override fun compareTo(other: BoundedReceiverUser): Int = 0
    // shadows T.coerceAtMost(maximumValue: T), as T is pinned to this final class, comparable only to itself
    fun coerceAtMost(maximumValue: BoundedReceiverUser): BoundedReceiverUser = this
}

// E : BoundedReceiverEntity<E> inferred as BoundedReceiverMember for BoundedReceiverAdmin, not satisfying it itself
fun <E : BoundedReceiverEntity<E>> E.save(): E = this

open class BoundedReceiverMember : BoundedReceiverEntity<BoundedReceiverMember>() {
    override fun compareTo(other: BoundedReceiverMember): Int = 0
}

class BoundedReceiverAdmin : BoundedReceiverMember()

interface BoundedReceiverOrdered<T> : Comparable<T>

class BoundedReceiverRank : BoundedReceiverOrdered<BoundedReceiverRank> {
    override fun compareTo(other: BoundedReceiverRank): Int = 0
}

// the extensions on Comparable of the package of a class, listed for it, like the ones of the standard library,
// unlike the ones of another package, like isAtLeast, see CustomTypes, or the ones it cannot be called with
fun <E : Comparable<in E>> E.isBetween(low: E, high: E): Boolean = this >= low && this <= high

fun Comparable<Int>.comparedToZero(): Int = compareTo(0)

// the extensions on a type parameter pinned to a final class by a self-referential bound, which is invariant,
// shadowed by the members of the class called instead, unless a call can still reach them
interface BoundedReceiverNode<N : BoundedReceiverNode<N>>

fun <N : BoundedReceiverNode<N>> N.linkTo(next: N): N = next

fun <N : BoundedReceiverNode<N>> N.linkAll(vararg nodes: N): N = this

fun <N : BoundedReceiverNode<N>> N.linkOrSelf(next: N? = null): N = this

fun <N : BoundedReceiverNode<N>> N.linkInContext(next: N): N = this

fun <N : BoundedReceiverNode<N>> N.linkSuspending(next: N): N = this

fun <N : BoundedReceiverNode<N>> N.linkMany(next: N): N = this

class BoundedReceiverLeaf : BoundedReceiverNode<BoundedReceiverLeaf> {
    // called instead of the extension
    fun linkTo(next: BoundedReceiverLeaf): BoundedReceiverLeaf = next
    // not called for leaf.linkAll(), or for more nodes
    fun linkAll(nodes: BoundedReceiverLeaf): BoundedReceiverLeaf = this
    // not called for leaf.linkOrSelf()
    fun linkOrSelf(next: BoundedReceiverLeaf?): BoundedReceiverLeaf = this
    // not called without the context parameter
    context(log: Logger)
    fun linkInContext(next: BoundedReceiverLeaf): BoundedReceiverLeaf = this
    // called instead of the extension, also outside a coroutine, failing to compile then
    suspend fun linkSuspending(next: BoundedReceiverLeaf): BoundedReceiverLeaf = this
    // not called for leaf.linkMany(next = leaf), as a vararg does not take a single named argument
    fun linkMany(vararg next: BoundedReceiverLeaf): BoundedReceiverLeaf = this
}

open class BoundedReceiverTwigBase {
    // called instead of N.linkTo(next: N) on a BoundedReceiverTwig, inheriting it
    fun linkTo(next: BoundedReceiverTwig): BoundedReceiverTwig = next
}

class BoundedReceiverTwig : BoundedReceiverTwigBase(), BoundedReceiverNode<BoundedReceiverTwig>

// the type arguments of the receivers, and of the bounds, matched against the ones of the supertypes of a class
interface BoundedReceiverBox<T>

interface BoundedReceiverSource<out T>

class BoundedReceiverIntBox : BoundedReceiverBox<Int>, BoundedReceiverSource<Int>

class BoundedReceiverMixedBox : BoundedReceiverBox<Int>, BoundedReceiverSource<String>

fun BoundedReceiverBox<Int>.sumOfInts(): Int = 0

fun BoundedReceiverBox<String>.joinStrings(): String = ""

fun BoundedReceiverBox<out Number>.sumOfNumbers(): Double = 0.0

fun BoundedReceiverSource<Number>.firstNumber(): Number = 0

fun BoundedReceiverSource<String>.firstString(): String = ""

fun <T> BoundedReceiverBox<T>.anyBox() {}

fun <T, B> B.sameElements() where B : BoundedReceiverBox<T>, B : BoundedReceiverSource<T> {}

// the bound T : Comparable<T> next to another one, satisfied only by a class comparable to itself
interface BoundedReceiverE

fun <T> T.comparableE() where T : Comparable<T>, T : BoundedReceiverE {}

class BoundedReceiverComparableE : Comparable<BoundedReceiverComparableE>, BoundedReceiverE {
    override fun compareTo(other: BoundedReceiverComparableE): Int = 0
}

class BoundedReceiverComparableToOtherE : Comparable<BoundedReceiverVersion>, BoundedReceiverE {
    override fun compareTo(other: BoundedReceiverVersion): Int = 0
}

// a generic class whose type parameter, bounded by the class itself, stands for its subclass, comparable to itself
abstract class BoundedReceiverTitled<N : BoundedReceiverTitled<N>> : BoundedReceiverEntity<N>()

// a contravariant type argument, which Any is not a subtype of
interface BoundedReceiverSink<in T>

class BoundedReceiverNumberSink : BoundedReceiverSink<Number>

fun BoundedReceiverSink<Any>.sinkAny() {}

fun BoundedReceiverSink<Int>.sinkInt() {}

// a type argument of a Java class, which is not a String
class BoundedReceiverFiles : BoundedReceiverSource<java.io.File>

// the supertypes of the Kotlin classes mapped to Java classes, like java.io.Serializable, only told by the Java classes
class BoundedReceiverIoErrors : BoundedReceiverSource<java.io.IOException>

class BoundedReceiverStrings : BoundedReceiverSource<String>

class BoundedReceiverArrays : BoundedReceiverSource<Array<String>>

class BoundedReceiverSerializableSink : BoundedReceiverSink<java.io.Serializable>

fun BoundedReceiverSource<java.io.Serializable>.firstSerializable() {}

fun BoundedReceiverSource<Cloneable>.firstCloneable() {}

// a Java interface without superinterfaces, which is not a String
class BoundedReceiverRunnables : BoundedReceiverSource<Runnable>

// a type parameter bounded by the class of other type arguments, not standing for the class
abstract class BoundedReceiverStarred<X, T : BoundedReceiverStarred<String, T>> : Comparable<T>
