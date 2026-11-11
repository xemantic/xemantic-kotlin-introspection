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

import com.xemantic.kotlin.introspection.test.other.JavaAnyRows
import com.xemantic.kotlin.introspection.test.other.JavaBoundedReceiverBase
import com.xemantic.kotlin.introspection.test.other.JavaBoxBase
import com.xemantic.kotlin.introspection.test.other.JavaComparableBase
import com.xemantic.kotlin.introspection.test.other.JavaDoublingPaths
import com.xemantic.kotlin.introspection.test.other.JavaFace
import com.xemantic.kotlin.introspection.test.other.JavaFaceBase
import com.xemantic.kotlin.introspection.test.other.JavaFlexibleListPaths
import com.xemantic.kotlin.introspection.test.other.JavaFunctionBase
import com.xemantic.kotlin.introspection.test.other.JavaGenericComparable
import com.xemantic.kotlin.introspection.test.other.JavaLinkBase
import com.xemantic.kotlin.introspection.test.other.JavaListBoth
import com.xemantic.kotlin.introspection.test.other.JavaListPathsOf
import com.xemantic.kotlin.introspection.test.other.JavaListRows
import com.xemantic.kotlin.introspection.test.other.JavaManyListPaths
import com.xemantic.kotlin.introspection.test.other.JavaNamedLinkBase
import com.xemantic.kotlin.introspection.test.other.JavaNestedRows
import com.xemantic.kotlin.introspection.test.other.JavaNullableChainPaths
import com.xemantic.kotlin.introspection.test.other.JavaProjectedAnyRows
import com.xemantic.kotlin.introspection.test.other.JavaProjectedObjectArrayRows
import com.xemantic.kotlin.introspection.test.other.JavaProjectedObjectWildcardRows
import com.xemantic.kotlin.introspection.test.other.JavaProjectedStringArrayRows
import com.xemantic.kotlin.introspection.test.other.JavaProjectedStringArraySource
import com.xemantic.kotlin.introspection.test.other.JavaProjectedStringListSource
import com.xemantic.kotlin.introspection.test.other.JavaProjectedStringListRows
import com.xemantic.kotlin.introspection.test.other.JavaProjectedWildcardSource
import com.xemantic.kotlin.introspection.test.other.JavaRawComparableBoth
import com.xemantic.kotlin.introspection.test.other.JavaRawBoundedListPaths
import com.xemantic.kotlin.introspection.test.other.JavaRawCharSequenceJavaListPaths
import com.xemantic.kotlin.introspection.test.other.JavaRawCharSequenceListPaths
import com.xemantic.kotlin.introspection.test.other.JavaRawComparableListPaths
import com.xemantic.kotlin.introspection.test.other.JavaRawGenericComparableMid
import com.xemantic.kotlin.introspection.test.other.JavaRawListConsumer
import com.xemantic.kotlin.introspection.test.other.JavaRawListPaths
import com.xemantic.kotlin.introspection.test.other.JavaRawMapArrayBoth
import com.xemantic.kotlin.introspection.test.other.JavaRawMapBoth
import com.xemantic.kotlin.introspection.test.other.JavaRawMixedNullabilityListPaths
import com.xemantic.kotlin.introspection.test.other.JavaRawMutableBoundPaths
import com.xemantic.kotlin.introspection.test.other.JavaRawMutableBoundListPaths
import com.xemantic.kotlin.introspection.test.other.JavaRawWildcardMapBoth
import com.xemantic.kotlin.introspection.test.other.JavaStringCollection
import com.xemantic.kotlin.introspection.test.other.JavaStringList
import com.xemantic.kotlin.introspection.test.other.JavaStringListPaths
import com.xemantic.kotlin.introspection.test.other.JavaStringMutableListPaths
import com.xemantic.kotlin.introspection.test.other.JavaStringNullableWrapPaths
import com.xemantic.kotlin.introspection.test.other.JavaStringObjectPairSink
import com.xemantic.kotlin.introspection.test.other.JavaWildcardListConsumer
import com.xemantic.kotlin.introspection.test.other.JavaWildcardRows

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

// Nothing, a subtype of any type, as a type argument, like of an Arrow-style None
object BoundedReceiverNone : BoundedReceiverSource<Nothing>

class BoundedReceiverIntSink : BoundedReceiverSink<Int>

fun BoundedReceiverSink<Nothing>.sinkNothing() {}

// the JVM supertypes of a built-in type, which Kotlin does not see, like java.lang.constant.Constable of Int,
// or java.util.Collection of List, seen as MutableCollection
class BoundedReceiverInts : BoundedReceiverSource<Int>

class BoundedReceiverLists : BoundedReceiverSource<List<String>>

fun BoundedReceiverSource<java.lang.constant.Constable>.firstConstable() {}

fun BoundedReceiverSource<MutableCollection<String>>.addRow() {}

// a self-referential bound of a covariant type argument, satisfied only by a class of a type argument which is its subtype
interface BoundedReceiverChain<out T>

fun <T : BoundedReceiverChain<T>> T.chain() {}

class BoundedReceiverChainOfAny : BoundedReceiverChain<Any>

open class BoundedReceiverChainBase : BoundedReceiverChain<BoundedReceiverChainLink>

class BoundedReceiverChainLink : BoundedReceiverChainBase()

// a type parameter inferred from a contravariant and a covariant type argument,
// which has to be a subtype of the former, and a supertype of the latter
interface BoundedReceiverPair<in A, out B>

fun <T> BoundedReceiverPair<T, T>.samePair() {}

class BoundedReceiverStringToAny : BoundedReceiverPair<String, Any>

class BoundedReceiverAnyToString : BoundedReceiverPair<Any, String>

// the type arguments of a supertype of a type argument, like Comparable<String> of String
interface BoundedReceiverComparables<out T>

fun BoundedReceiverComparables<Comparable<java.io.File>>.firstComparableFile() {}

fun BoundedReceiverComparables<Comparable<String>>.firstComparableString() {}

class BoundedReceiverComparableStrings : BoundedReceiverComparables<String>

// the JVM supertypes of a built-in supertype, like java.io.Serializable of kotlin.Throwable, and of kotlin.Enum
fun <T> T.boundedByAAndSerializable() where T : BoundedReceiverA, T : java.io.Serializable {}

class BoundedReceiverThrowable : Throwable(), BoundedReceiverA

enum class BoundedReceiverEnum : BoundedReceiverA { ENTRY }

// an enum class, comparable to itself through kotlin.Enum, listing the extensions on Comparable of its package,
// like isBetween, but not the ones of the standard library, like coerceAtLeast
enum class BoundedReceiverLevel { LOW, HIGH }

fun Comparable<BoundedReceiverLevel>.isAboveLow(): Boolean = compareTo(BoundedReceiverLevel.LOW) > 0

val Comparable<BoundedReceiverLevel>.isBelowHigh: Boolean get() = compareTo(BoundedReceiverLevel.HIGH) < 0

// the extensions on Comparable declared in an object of the package of a class, imported from it
object BoundedReceiverComparisons {
    fun Comparable<BoundedReceiverVersion>.isNewerThan(version: BoundedReceiverVersion): Boolean = compareTo(version) > 0
}

// a class comparable to its Java supertype
class BoundedReceiverJavaComparable : JavaComparableBase()

// a method of a Java supertype, which never shadows the extension, called by leaf.linkTo(next = leaf)
class BoundedReceiverJavaLeaf : JavaLinkBase<BoundedReceiverJavaLeaf>(), BoundedReceiverNode<BoundedReceiverJavaLeaf>

// the methods of a Java supertype taking parameters, never shadowing the extensions, which a named argument calls,
// like picker.pick(arg0 = picker), also when the Java parameter is named like the one of the extension,
// unlike a Java method taking none
interface BoundedReceiverJavaNode<N : BoundedReceiverJavaNode<N>>

fun <N : BoundedReceiverJavaNode<N>> N.pick(arg0: N): N = arg0

fun <N : BoundedReceiverJavaNode<N>> N.linkNext(next: N): N = next

fun <N : BoundedReceiverJavaNode<N>> N.self(): N = this

class BoundedReceiverJavaPicker : JavaLinkBase<BoundedReceiverJavaPicker>(), BoundedReceiverJavaNode<BoundedReceiverJavaPicker>

class BoundedReceiverJavaNamedLeaf : JavaNamedLinkBase<BoundedReceiverJavaNamedLeaf>(), BoundedReceiverJavaNode<BoundedReceiverJavaNamedLeaf>

// the type arguments given to a Java supertype, platform types in its supertypes, unless nullable,
// like BoundedReceiverBox<String!> of BoundedReceiverStringBox, but BoundedReceiverBox<String?> of BoundedReceiverNullableStringBox
fun BoundedReceiverBox<String?>.joinNullableStrings(): String = ""

class BoundedReceiverStringBox : JavaBoxBase<String>()

class BoundedReceiverNullableStringBox : JavaBoxBase<String?>()

// the type arguments of a Java supertype of a type argument, told only by its generic signature,
// like Comparable<JavaComparableBase> of JavaComparableBase, or Comparable<StringBuilder> of java.lang.StringBuilder
fun BoundedReceiverComparables<Comparable<JavaComparableBase>>.firstComparableJava() {}

fun BoundedReceiverComparables<Comparable<JavaGenericComparable<String>>>.firstComparableGenericString() {}

fun BoundedReceiverComparables<Comparable<JavaGenericComparable<Int>>>.firstComparableGenericInt() {}

class BoundedReceiverJavaComparables : BoundedReceiverComparables<JavaComparableBase>

class BoundedReceiverStringBuilders : BoundedReceiverComparables<StringBuilder>

class BoundedReceiverJavaGenericComparables : BoundedReceiverComparables<JavaGenericComparable<String>>

// the type arguments of a Java interface receiver, invariant, reached through a Java supertype
fun JavaFace<Int>.faceOfInt() {}

fun JavaFace<String>.faceOfString() {}

fun JavaFace<String?>.faceOfNullableString() {}

class BoundedReceiverStringFace : JavaFaceBase<String>()

class BoundedReceiverNullableStringFace : JavaFaceBase<String?>()

// a Java list given as a type argument by a Java supertype, either a read-only list, or a mutable one
interface BoundedReceiverRows<T>

fun BoundedReceiverRows<MutableList<String>>.addStringRow() {}

fun BoundedReceiverRows<List<String>>.readStringRows() {}

fun BoundedReceiverRows<List<Int>>.readIntRows() {}

class BoundedReceiverStringRows : JavaListRows<String>()

// a star projection, or a Java wildcard, the same type argument only as another one of the same projection
fun BoundedReceiverRows<MutableList<*>>.addAnyRow() {}

fun BoundedReceiverRows<List<*>>.readAnyRows() {}

fun BoundedReceiverRows<List<MutableList<String>>>.readMutableRows() {}

class BoundedReceiverWildcardRows : JavaWildcardRows<String>()

class BoundedReceiverNestedRows : JavaNestedRows()

// the supertypes of the mutable list, matched only when its type arguments are told, unlike of a star projection,
// or of a Java wildcard
fun BoundedReceiverRows<out MutableCollection<Int>>.addIntRows() {}

fun BoundedReceiverRows<out MutableCollection<String>>.addStringRows() {}

fun BoundedReceiverRows<out MutableIterable<Int>>.removeIntRows() {}

class BoundedReceiverAnyRows : JavaAnyRows()

// a use-site projection, another type in an invariant position, unlike MutableList<String>
fun BoundedReceiverRows<MutableList<in String>>.addSuperStringRow() {}

// the bounds of a type parameter inferred from the mutable alternative, satisfied by String, but not by String?,
// and only T : Any by Int
fun <T : Any> BoundedReceiverRows<MutableList<T>>.addNotNullRow() {}

fun <T : CharSequence> BoundedReceiverRows<MutableList<T>>.addCharSequenceRow() {}

class BoundedReceiverNullableStringRows : JavaListRows<String?>()

class BoundedReceiverIntRows : JavaListRows<Int>()

// a mutable list of a supertype known only by its name, java.io.Serializable of String and Int, but not of String?
fun BoundedReceiverRows<out MutableList<out java.io.Serializable>>.addSerializableRows() {}

// the Java list of a Java supertype of a Kotlin type argument, substituted, still a read-only List<String>
open class BoundedReceiverKotlinRows<T> : JavaListRows<T>()

fun BoundedReceiverComparables<BoundedReceiverRows<List<String>>>.firstStringRows() {}

class BoundedReceiverKotlinRowsSource : BoundedReceiverComparables<BoundedReceiverKotlinRows<String>>

// a type parameter inferred from the read-only List<String> of a Java list, and from the same type argument
interface BoundedReceiverTwoRows<A, B>

fun <T> BoundedReceiverTwoRows<T, T>.sameBoth() {}

class BoundedReceiverListBoth : JavaListBoth<List<String>>()

// the same type parameter inferred from the mutable alternative of a Java list, and from the same type argument,
// but not from another one of the same class, like MutableList<CharSequence>, or List<*>
fun <T> BoundedReceiverTwoRows<T, out T>.sameOrSubtypeBoth() {}

class BoundedReceiverMutableListBoth : JavaListBoth<MutableList<String>>()

class BoundedReceiverNullableListBoth : JavaListBoth<MutableList<String?>>()

class BoundedReceiverCharSequenceListBoth : JavaListBoth<MutableList<CharSequence>>()

class BoundedReceiverStarListBoth : JavaListBoth<List<*>>()

// a type parameter inferred from a raw type of a Java supertype, any type between its erasure and its star projection
class BoundedReceiverRawMapBoth : JavaRawMapBoth<MutableMap<String, Int>>()

class BoundedReceiverRawStarMapBoth : JavaRawMapBoth<Map<*, *>>()

class BoundedReceiverRawComparableBoth : JavaRawComparableBoth<Comparable<String>>()

// also as the bound of a wildcard, and as the component of an array
class BoundedReceiverRawWildcardMapBoth : JavaRawWildcardMapBoth<List<Map<*, *>>>()

class BoundedReceiverRawMapArrayBoth : JavaRawMapArrayBoth<Array<out Map<*, *>>>()

// a nullable type parameter inferred from a nullable type, which the type parameter itself is not, unlike a not nullable type
interface BoundedReceiverNullablePair<A, B>

fun <T> BoundedReceiverNullablePair<T?, T>.nullableFirst() {}

fun <T> BoundedReceiverNullablePair<T, T?>.nullableSecond() {}

fun <T> BoundedReceiverNullablePair<out T?, T>.nullableOutFirst() {}

class BoundedReceiverNullableFirstPair : BoundedReceiverNullablePair<String?, String>

class BoundedReceiverNullableSecondPair : BoundedReceiverNullablePair<String, String?>

// a JDK class implementing a Kotlin collection purely, of its own type argument, not a platform type,
// see FakePureImplementationsProvider of kotlinc, unlike a JDK class implementing it through a Java supertype
interface BoundedReceiverJdkSink<in T>

fun BoundedReceiverJdkSink<java.util.ArrayList<String>>.sinkArrayList() {}

fun BoundedReceiverJdkSink<java.util.HashSet<String>>.sinkHashSet() {}

// also through a Java supertype, HashSet<E!>, not implementing MutableSet<E> directly
fun BoundedReceiverJdkSink<java.util.LinkedHashSet<String>>.sinkLinkedHashSet() {}

fun BoundedReceiverJdkSink<java.util.HashMap<String, Int>>.sinkHashMap() {}

fun BoundedReceiverJdkSink<java.util.AbstractList<String>>.sinkAbstractList() {}

fun BoundedReceiverJdkSink<java.util.Vector<String>>.sinkVector() {}

class BoundedReceiverNullableListJdkSink : BoundedReceiverJdkSink<MutableCollection<String?>>

class BoundedReceiverNullableMapJdkSink : BoundedReceiverJdkSink<MutableMap<String, Int?>>

// a type parameter pinned to the class by the invariant type argument of a Java interface,
// so that the member is called instead of the extension
fun <N : JavaFace<N>> N.faceLink(next: N): N = next

class BoundedReceiverJavaFaceLeaf : JavaFaceBase<BoundedReceiverJavaFaceLeaf>() {
    fun faceLink(next: BoundedReceiverJavaFaceLeaf): BoundedReceiverJavaFaceLeaf = next
}

// the read-only List<String> of the Java supertype of the receiver type argument, also a mutable one,
// taking a MutableList<String> in a contravariant position
class BoundedReceiverMutableRowsSink : BoundedReceiverSink<BoundedReceiverRows<MutableList<String>>>

class BoundedReceiverKotlinStringRows : BoundedReceiverKotlinRows<String>()

fun BoundedReceiverSink<JavaListRows<String>>.sinkJavaRows() {}

fun BoundedReceiverSink<BoundedReceiverKotlinRows<String>>.sinkKotlinRows() {}

fun BoundedReceiverSink<BoundedReceiverKotlinStringRows>.sinkKotlinStringRows() {}

// the projections of the type arguments of an invariant type argument, matched exactly,
// where List<*> is still the same type as List<Any?>, as List is covariant,
// and the bounds of a type parameter other than the receiver
interface BoundedReceiverProjectedRows<T>

fun BoundedReceiverProjectedRows<List<*>>.projectedAnyRows() {}

fun BoundedReceiverProjectedRows<List<Any?>>.projectedNullableAnyRows() {}

fun BoundedReceiverProjectedRows<List<Int>>.projectedIntRows() {}

fun BoundedReceiverProjectedRows<MutableList<out String>>.projectedOutStringRows() {}

fun <T : List<String>> BoundedReceiverProjectedRows<T>.projectedStringListRows() {}

fun <T : MutableList<String>> BoundedReceiverProjectedRows<T>.projectedMutableStringListRows() {}

fun <T : Comparable<T>> BoundedReceiverProjectedRows<List<T>>.projectedComparableRows() {}

fun <T : Number> BoundedReceiverProjectedRows<List<T>>.projectedNumberRows() {}

class BoundedReceiverProjectedStringRows : BoundedReceiverProjectedRows<List<String>>

class BoundedReceiverProjectedStarRows : BoundedReceiverProjectedRows<List<*>>

class BoundedReceiverProjectedMutableRows : BoundedReceiverProjectedRows<MutableList<String>>

class BoundedReceiverProjectedOutRows : BoundedReceiverProjectedRows<MutableList<out String>>

class BoundedReceiverProjectedMapRows : BoundedReceiverProjectedRows<Map<String, Int>>

class BoundedReceiverProjectedJavaAnyRows : JavaProjectedAnyRows()

class BoundedReceiverProjectedJavaStringRows : JavaProjectedStringListRows()

// a Java wildcard bounded by Object, out Any!, also a List<Any>
fun BoundedReceiverProjectedRows<List<Any>>.projectedNotNullAnyRows() {}

class BoundedReceiverProjectedJavaObjectRows : JavaProjectedObjectWildcardRows()

// a Java array, seen by Kotlin as the flexible Array<(out) String!>!, either an Array<String>, or an Array<out String>
fun BoundedReceiverProjectedRows<Array<String>>.projectedStringArray() {}

fun BoundedReceiverProjectedRows<Array<out String>>.projectedOutStringArray() {}

fun <T : CharSequence> BoundedReceiverProjectedRows<Array<out T>>.projectedOutCharSequenceArray() {}

fun BoundedReceiverProjectedRows<Array<*>>.projectedAnyArray() {}

class BoundedReceiverProjectedStringArrayRows : JavaProjectedStringArrayRows()

class BoundedReceiverProjectedObjectArrayRows : JavaProjectedObjectArrayRows()

// a use-site projection of a type argument in a covariant position, a subtype only of the same projection of a supertype
interface BoundedReceiverProjectedSource<out T>

fun BoundedReceiverProjectedSource<MutableList<out CharSequence>>.sourceOutCharSequences() {}

fun BoundedReceiverProjectedSource<MutableList<String>>.sourceStrings() {}

fun BoundedReceiverProjectedSource<MutableList<*>>.sourceAny() {}

// the supertypes of a type argument of projected type arguments, keeping their projections
fun BoundedReceiverProjectedSource<List<String>>.sourceStringList() {}

fun <T : Number> BoundedReceiverProjectedSource<List<T>>.sourceNumbers() {}

fun <T : MutableCollection<out CharSequence>> BoundedReceiverProjectedSource<T>.sourceMutableCharSequences() {}

class BoundedReceiverProjectedOutSource : BoundedReceiverProjectedSource<MutableList<out String>>

class BoundedReceiverProjectedStarSource : BoundedReceiverProjectedSource<MutableList<*>>

class BoundedReceiverProjectedWildcardSource : JavaProjectedWildcardSource()

// the subtypes of a type argument in a contravariant position, the supertypes of its projected type arguments
interface BoundedReceiverProjectedSink<in T>

fun BoundedReceiverProjectedSink<MutableList<*>>.sinkAnyList() {}

fun BoundedReceiverProjectedSink<MutableList<out String>>.sinkOutStrings() {}

class BoundedReceiverProjectedStringListSink : BoundedReceiverProjectedSink<List<String>>

class BoundedReceiverProjectedCollectionSink : BoundedReceiverProjectedSink<MutableCollection<String>>

// a Java array, Array<(out) String!>!, of the supertype of the type argument of the extension, an Array<out String> as well
interface BoundedReceiverProjectedSrc<T>

fun BoundedReceiverProjectedSink<JavaProjectedStringArraySource>.sinkJavaArraySource() {}

class BoundedReceiverProjectedOutArraySink : BoundedReceiverProjectedSink<BoundedReceiverProjectedSrc<Array<out String>>>

// a Java list, (Mutable)List<String!>!, of the supertype of the type argument of the extension,
// in an invariant position the same type as any type between MutableList<String!> and List<String!>
fun BoundedReceiverProjectedSink<JavaProjectedStringListSource>.sinkJavaListSource() {}

class BoundedReceiverProjectedOutListSink : BoundedReceiverProjectedSink<BoundedReceiverProjectedSrc<MutableList<out String>>>

// a projected type argument of a class nested in a type argument of its supertype, approximated like by kotlinc,
// like List<MutableList<out String>> of BoundedReceiverNest<out String>
interface BoundedReceiverNest<E> : List<MutableList<E>>

interface BoundedReceiverNestedSource<out T>

fun BoundedReceiverNestedSource<List<MutableList<out String>>>.nestedOutStrings() {}

fun BoundedReceiverNestedSource<List<CharSequence>>.nestedCharSequences() {}

fun <T> BoundedReceiverNestedSource<List<Array<T>>>.nestedArrays() {}

fun <T> BoundedReceiverNestedSource<List<Array<out T>>>.nestedOutArrays() {}

class BoundedReceiverOutNestSource : BoundedReceiverNestedSource<BoundedReceiverNest<out String>>

class BoundedReceiverInArraysSource : BoundedReceiverNestedSource<List<Array<in String>>>

// a class comparable to another type, listing the extensions on Comparable of its package, like next to it,
// but not the ones of the other packages
class BoundedReceiverMoney

class BoundedReceiverPrice : Comparable<BoundedReceiverMoney> {
    override fun compareTo(other: BoundedReceiverMoney): Int = 0
}

fun Comparable<BoundedReceiverMoney>.atLeastMoney() {}

// a supertype reached both through Kotlin and through Java, the one of Kotlin preferred by kotlinc,
// MutableCollection<String>, not a MutableCollection<String?>, unlike MutableCollection<String!> of Java
interface BoundedReceiverKotlinStringCollection : MutableCollection<String>

abstract class BoundedReceiverJavaKotlinStrings : JavaStringCollection, BoundedReceiverKotlinStringCollection

fun BoundedReceiverJdkSink<BoundedReceiverJavaKotlinStrings>.sinkJavaKotlinStrings() {}

// also when the supertype reached through Java is walked first, like MutableList<String!> of a Java list,
// the one of Kotlin preferred, MutableList<String>, its own supertypes walked again from it,
// MutableCollection<String>, not a MutableCollection<String?>, unlike MutableCollection<String!> of Java
interface BoundedReceiverKotlinStringList : MutableList<String>

interface BoundedReceiverDeepKotlinStringList : BoundedReceiverKotlinStringList

abstract class BoundedReceiverJavaDeepKotlinStringList : JavaStringList, BoundedReceiverDeepKotlinStringList

fun BoundedReceiverJdkSink<BoundedReceiverJavaDeepKotlinStringList>.sinkJavaDeepKotlinStringList() {}

fun BoundedReceiverJdkSink<JavaStringList>.sinkJavaStringList() {}

// a class comparable through a raw Java supertype, to the erasure of its type argument, the raw JavaGenericComparable,
// told by its name only, not listing the extensions on Comparable of its package, which kotlinc does not accept
class BoundedReceiverRawGenericComparable : JavaRawGenericComparableMid()

// a type parameter of a contravariant position, inferred by kotlinc as the type argument when its bound refers to a type parameter,
// like T : Comparable<T>, or E of T : List<E>, unlike T : CharSequence, which a subtype of both satisfies
interface BoundedReceiverConsumer<in T>

fun <T : Comparable<T>> BoundedReceiverConsumer<T>.consumeComparable() {}

fun <T : CharSequence> BoundedReceiverConsumer<T>.consumeCharSequence() {}

fun <E, T : List<E>> BoundedReceiverConsumer<T>.consumeElements() {}

// a type parameter constrained only by a star projection, which kotlinc cannot infer, unless a value parameter tells it
fun <T : CharSequence> BoundedReceiverConsumer<List<T>>.consumeCharSequences() {}

fun <T : CharSequence> BoundedReceiverConsumer<List<T>>.consumeCharSequencesOf(value: T) {}

class BoundedReceiverListConsumer : BoundedReceiverConsumer<List<String>>

class BoundedReceiverStarListConsumer : BoundedReceiverConsumer<List<*>>

class BoundedReceiverStringConsumer : BoundedReceiverConsumer<String>

// unless something else constrains the type parameter, like a value parameter, or a bound which is not recursive,
// like T : U, also through the bound of a value parameter, like E of T : List<E> for value: T,
// while a bound referring to the type parameter only covariantly, like T : List<T>, is satisfied by a final class,
// like Int, but not by Any
fun <T : Comparable<T>> BoundedReceiverConsumer<T>.consumeComparableOf(value: T) {}

fun <T : U, U : CharSequence> BoundedReceiverConsumer<T>.consumeBoundedByTypeParameter() {}

fun <E, T : List<E>> BoundedReceiverConsumer<T>.consumeElementsOf(element: E) {}

fun <E, T : List<E>> BoundedReceiverConsumer<List<T>>.consumeListsOf(value: T) {}

// also through the bound of a type parameter bound, like E of U : List<E> for T : U
fun <E, U : List<E>, T : U> BoundedReceiverConsumer<T>.consumeElementsThroughBound() {}

fun <T : List<T>> BoundedReceiverConsumer<T>.consumeSelfList() {}

class BoundedReceiverAnyConsumer : BoundedReceiverConsumer<Any>

class BoundedReceiverIntConsumer : BoundedReceiverConsumer<Int>

// a type parameter in a projection contradicting the type parameter of a supertype, like T of List<in T>,
// the supertype of MutableList<in T>, which kotlinc infers, not told, but by a star projection, which tells nothing of it
fun <T> BoundedReceiverConsumer<MutableList<in T>>.consumeIntoMutable() {}

class BoundedReceiverNullableAnyListConsumer : BoundedReceiverConsumer<List<Any?>>

// a type parameter kotlinc infers as an intersection type, checked against its recursive bound,
// of multiple bounds, which is Nothing for a final class, like Int, which is no CharSequence, satisfying the bounds
fun <T> BoundedReceiverConsumer<T>.consumeComparableCharSequence() where T : Comparable<T>, T : CharSequence {}

class BoundedReceiverPlain

class BoundedReceiverPlainConsumer : BoundedReceiverConsumer<BoundedReceiverPlain>

// a type parameter bounded by another one, which nothing else constrains, like T : U of U : Comparable<U>,
// which kotlinc infers as the same type, satisfying the bound of the other one
fun <T : U, U : Comparable<U>> BoundedReceiverConsumer<T>.consumeThroughComparable() {}

fun <T : Comparable<T>, U : T> BoundedReceiverConsumer<U>.consumeUnderComparable() {}

fun <C : B, B : A, A : Comparable<A>> BoundedReceiverConsumer<C>.consumeThroughComparableChain() {}

// the intersection of two classes, neither of them a subclass of the other, is empty, like of Number and an open class
fun <T> BoundedReceiverConsumer<T>.consumeComparableNumber() where T : Comparable<T>, T : Number {}

open class BoundedReceiverOpenPlain

class BoundedReceiverOpenPlainConsumer : BoundedReceiverConsumer<BoundedReceiverOpenPlain>

// a raw Java List, or a List of a wildcard, which a List<T> is a subtype of, of any T, which kotlinc cannot infer T from
class BoundedReceiverRawListConsumer : JavaRawListConsumer()

class BoundedReceiverWildcardListConsumer : JavaWildcardListConsumer()

// a star projection of an invariant type parameter, the same type as in Nothing, like MutableList<*>, and MutableList<in Nothing>
interface BoundedReceiverMutableRows<T>

fun <T> BoundedReceiverMutableRows<MutableList<in T>>.mutableIntoRows() {}

fun <T : Comparable<T>> BoundedReceiverMutableRows<MutableList<in T>>.mutableComparableIntoRows() {}

class BoundedReceiverStarMutableRows : BoundedReceiverMutableRows<MutableList<*>>

// of multiple contravariant positions, or of the type of another type parameter, like A of B : A
interface BoundedReceiverBiConsumer<in A, in B>

fun <T : Comparable<T>> BoundedReceiverBiConsumer<T, T>.biConsumeComparable() {}

fun <A : Comparable<A>, B : A> BoundedReceiverBiConsumer<B, A>.biConsumeBounded() {}

class BoundedReceiverStringBiConsumer : BoundedReceiverBiConsumer<String, CharSequence>

class BoundedReceiverIntStringBiConsumer : BoundedReceiverBiConsumer<Int, String>

class BoundedReceiverListCollectionBiConsumer : BoundedReceiverBiConsumer<List<String>, Collection<String>>

class BoundedReceiverPlainAnyBiConsumer : BoundedReceiverBiConsumer<BoundedReceiverPlain, Any>

// Any? constrains a type parameter of a contravariant position trivially, inferred after the other ones
class BoundedReceiverStringNullableAnyBiConsumer : BoundedReceiverBiConsumer<String?, Any?>

// a type parameter bounding multiple ones, which kotlinc infers as the type of the first one fixed
fun <A : Comparable<A>, B : A, C : A> BoundedReceiverBiConsumer<B, C>.biConsumeUnder() {}

// a pair of contravariant positions, a type parameter bounded by a type of another one, which is part of its intersection,
// like List<CharSequence> of T : List<U> for U inferred as CharSequence, or bounding multiple ones,
// which kotlinc infers as the type of the first one declared of them, wherever it is declared itself
interface BoundedReceiverPairSink<in A, in B>

fun <U, T : List<U>> BoundedReceiverPairSink<T, U>.sinkListOf() {}

fun <B : A, C : A, A : Comparable<A>> BoundedReceiverPairSink<B, C>.sinkUnderLast() {}

fun <C : A, B : A, A : Comparable<A>> BoundedReceiverPairSink<B, C>.sinkUnderLastMirrored() {}

fun <A : Comparable<A>, B : A> BoundedReceiverPairSink<B, A>.sinkBounded() {}

class BoundedReceiverStringCharSequencePairSink : BoundedReceiverPairSink<String, CharSequence>

class BoundedReceiverCharSequenceIntPairSink : BoundedReceiverPairSink<CharSequence, Int>

// Any! of a Java Object constrains a type parameter of a contravariant position trivially, like Any?
class BoundedReceiverJavaStringObjectPairSink : JavaStringObjectPairSink()

// mutually recursive bounds, which kotlinc satisfies by the bound of the type parameter only they constrain
fun <T : Comparable<U>, U : Comparable<T>> BoundedReceiverPairSink<T, Nothing>.sinkMutuallyComparable() {}

class BoundedReceiverComparableAnyPairSink : BoundedReceiverPairSink<Comparable<Any>, Nothing>

// or a covariant position of the same type parameter, which it is inferred from instead, also through a bound, like B : A
interface BoundedReceiverMixed<out A, in B>

fun <T : Comparable<T>> BoundedReceiverMixed<T, T>.mixComparable() {}

fun <A : Comparable<A>, B : A> BoundedReceiverMixed<B, A>.mixBounded() {}

class BoundedReceiverStringMixed : BoundedReceiverMixed<String, CharSequence>

class BoundedReceiverListMixed : BoundedReceiverMixed<List<String>, Any>

// a type parameter constrained only by Nothing, which kotlinc cannot infer, also when the return type refers to it,
// which only an expected type of the call tells, unlike a value parameter, inferred from the argument
interface BoundedReceiverProducer<out T>

fun <T> BoundedReceiverProducer<T>.produce() {}

fun <T> BoundedReceiverProducer<List<T>>.produceList() {}

fun <T> BoundedReceiverProducer<List<T>>.produceFirst(): T? = null

val <T> BoundedReceiverProducer<List<T>>.firstProduced: T? get() = null

fun <T> BoundedReceiverProducer<List<T>>.produceWith(value: T) {}

fun <E, T : List<E>> BoundedReceiverProducer<T>.produceFirstElement(): E? = null

// or a type parameter bounded by one inferred, like T : R for r: R, fixed by kotlinc as the type of R,
// unlike by one which nothing infers, like R : CharSequence
fun <T : R, R> BoundedReceiverProducer<List<T>>.produceBounded(r: R): T? = null

fun <T : R, R : CharSequence> BoundedReceiverProducer<List<T>>.produceBoundedByUninferred(): T? = null

// also through a chain of bounds, like T : U, U : R, or when R bounds another type parameter as well, like U : R
fun <T : U, U : R, R> BoundedReceiverProducer<List<T>>.produceChained(r: R): T? = null

fun <T : R, U : R, R> BoundedReceiverProducer<List<T>>.produceFanned(r: R): T? = null

fun <E, T : List<E>> BoundedReceiverProducer<T>.produceElements() {}

class BoundedReceiverNothingProducer : BoundedReceiverProducer<Nothing>

// a recursive bound of a type parameter of a covariant position, checked against the type it is inferred from,
// like T : List<T> for List<String>, which is no List<List<String>>, while Nothing is,
// but not Nothing?, which kotlinc infers T : Comparable<T>? as Comparable<Nothing?>? from, no Comparable<Comparable<Nothing?>?>?
fun <T : List<T>> BoundedReceiverProducer<T>.produceSelfList() {}

fun <T : Comparable<T>?> BoundedReceiverProducer<T>.produceNullableComparable() {}

class BoundedReceiverListProducer : BoundedReceiverProducer<List<String>>

class BoundedReceiverNullableNothingProducer : BoundedReceiverProducer<Nothing?>

// a supertype of the type a type parameter of a covariant position is inferred from, satisfying its recursive bound,
// like List<BoundedReceiverSelfList> of MutableList<BoundedReceiverSelfList> for T : List<T>
interface BoundedReceiverSelfList : List<BoundedReceiverSelfList>

class BoundedReceiverMutableSelfListProducer : BoundedReceiverProducer<MutableList<BoundedReceiverSelfList>>

// but not of a nullable one, which a nullable supertype is the only supertype of
class BoundedReceiverNullableMutableSelfListProducer : BoundedReceiverProducer<MutableList<BoundedReceiverSelfList>?>

// or of a supertype of a type argument, which kotlinc can widen it to, like List<BoundedReceiverSelfList>
// of List<BoundedReceiverSubSelfList>, which only a subtype of List can be, unlike List<String>
interface BoundedReceiverSubSelfList : BoundedReceiverSelfList

class BoundedReceiverSubSelfListsProducer : BoundedReceiverProducer<List<BoundedReceiverSubSelfList>>

// a recursive bound of a type parameter of a covariant position, not nullable, which Nothing? does not satisfy
interface BoundedReceiverOutlet<out T>

fun <T : List<T?>> BoundedReceiverOutlet<T>.outletNullableSelfList() {}

class BoundedReceiverNullableNothingOutlet : BoundedReceiverOutlet<Nothing?>

// a type parameter bounding multiple ones, which kotlinc may fix after them, like A of B : A, and C : A, as String of B,
// C inferred as Nothing, the intersection of Int and String, then, or bounded by a type of a type parameter fixed after it,
// like T : Comparable<B>
interface BoundedReceiverTripleSink<in A, in B, in C>

fun <A : Comparable<A>, B : A, C : A> BoundedReceiverTripleSink<B, C, A>.tripleUnder() {}

fun <A : Comparable<A>, T : Comparable<B>, B : A> BoundedReceiverTripleSink<T, A, B>.tripleComparableOfLater() {}

class BoundedReceiverStringIntAnyTripleSink : BoundedReceiverTripleSink<String, Int, Any>

class BoundedReceiverPlainAnyAnyTripleSink : BoundedReceiverTripleSink<BoundedReceiverPlain, Any, Any>

// a type parameter bounding multiple ones, like A of B : A, and C : A, which kotlinc fixes after B, declared before it
fun <B : A, A : Comparable<A>, C : A> BoundedReceiverTripleSink<B, C, A>.tripleUnderMiddle() {}

class BoundedReceiverComparableIntAnyTripleSink : BoundedReceiverTripleSink<Comparable<String>, Int, Any>

// a supertype reached both through Kotlin and through Java, of type arguments which are not platform types themselves,
// like BoundedReceiverPaths<List<String!>> of JavaStringListPaths, unlike its nested String!,
// is kept by kotlinc next to BoundedReceiverPaths<List<String>> of Kotlin, an extension matching either of them accepted
interface BoundedReceiverPaths<T>

interface BoundedReceiverListPaths<T> : BoundedReceiverPaths<List<T>>

interface BoundedReceiverMutableListPaths<T> : BoundedReceiverPaths<MutableList<T>>

interface BoundedReceiverStringListPaths : BoundedReceiverPaths<List<String>>

class BoundedReceiverJavaKotlinListPaths : JavaStringListPaths, BoundedReceiverStringListPaths

fun BoundedReceiverPaths<List<String>>.stringListPaths() {}

fun BoundedReceiverPaths<List<String?>>.nullableStringListPaths() {}

fun BoundedReceiverPaths<MutableList<String>>.mutableStringListPaths() {}

fun <P : BoundedReceiverPaths<List<String?>>> P.nullableStringListPathsOf() {}

// also as the supertype of a type argument
interface BoundedReceiverPathsSource<out T>

fun BoundedReceiverPathsSource<BoundedReceiverPaths<List<String?>>>.nullableStringListPathsSource() {}

class BoundedReceiverJavaKotlinListPathsSource : BoundedReceiverPathsSource<BoundedReceiverJavaKotlinListPaths>

// while the one of a platform type argument, like BoundedReceiverPaths<(Mutable)List<String!>!> of JavaFlexibleListPaths,
// is left out by kotlinc next to another one, like BoundedReceiverPaths<MutableList<String!>> of JavaStringMutableListPaths
class BoundedReceiverJavaMutableListPaths : JavaFlexibleListPaths, JavaStringMutableListPaths

// a raw Kotlin supertype of a Java one, like BoundedReceiverListPaths of JavaRawListPaths,
// takes the bound of the Kotlin type parameter, BoundedReceiverPaths<List<Any?>>, not a platform type Any!,
// also next to the same supertype reached through Kotlin
interface BoundedReceiverAnyListPaths : BoundedReceiverPaths<List<Any?>>

fun BoundedReceiverPaths<List<Any?>>.anyListPaths() {}

fun <T : Any> BoundedReceiverPaths<List<T>>.nonNullListPaths() {}

class BoundedReceiverRawListPaths : JavaRawListPaths

class BoundedReceiverKotlinRawListPaths : BoundedReceiverAnyListPaths, JavaRawListPaths

// a supertype reached through a number of paths doubling at each level, of distinct types,
// like BoundedReceiverDoubling0<MutableList<MutableList<String>>>, and BoundedReceiverDoubling0<MutableList<(Mutable)List<String!>!>>,
// walked only up to a limit, the ones walked still telling the type arguments
interface BoundedReceiverDoublingRows<T>

fun BoundedReceiverDoublingRows<MutableList<MutableList<MutableList<MutableList<MutableList<MutableList<String>>>>>>>.doublingMutableRows() {}

interface BoundedReceiverDoubling0<T> : JavaDoublingPaths.DoublingRoot<T>

interface BoundedReceiverDoubling1<T> : BoundedReceiverDoubling0<MutableList<T>>, JavaDoublingPaths.Doubling1<T>

interface BoundedReceiverDoubling2<T> : BoundedReceiverDoubling1<MutableList<T>>, JavaDoublingPaths.Doubling2<T>

interface BoundedReceiverDoubling3<T> : BoundedReceiverDoubling2<MutableList<T>>, JavaDoublingPaths.Doubling3<T>

interface BoundedReceiverDoubling4<T> : BoundedReceiverDoubling3<MutableList<T>>, JavaDoublingPaths.Doubling4<T>

interface BoundedReceiverDoubling5<T> : BoundedReceiverDoubling4<MutableList<T>>, JavaDoublingPaths.Doubling5<T>

interface BoundedReceiverDoubling6<T> : BoundedReceiverDoubling5<MutableList<T>>, JavaDoublingPaths.Doubling6<T>

interface BoundedReceiverDoubling7<T> : BoundedReceiverDoubling6<MutableList<T>>, JavaDoublingPaths.Doubling7<T>

interface BoundedReceiverDoubling8<T> : BoundedReceiverDoubling7<MutableList<T>>, JavaDoublingPaths.Doubling8<T>

interface BoundedReceiverDoubling9<T> : BoundedReceiverDoubling8<MutableList<T>>, JavaDoublingPaths.Doubling9<T>

interface BoundedReceiverDoubling10<T> : BoundedReceiverDoubling9<MutableList<T>>, JavaDoublingPaths.Doubling10<T>

interface BoundedReceiverDoubling11<T> : BoundedReceiverDoubling10<MutableList<T>>, JavaDoublingPaths.Doubling11<T>

interface BoundedReceiverDoubling12<T> : BoundedReceiverDoubling11<MutableList<T>>, JavaDoublingPaths.Doubling12<T>

interface BoundedReceiverDoubling13<T> : BoundedReceiverDoubling12<MutableList<T>>, JavaDoublingPaths.Doubling13<T>

interface BoundedReceiverDoubling14<T> : BoundedReceiverDoubling13<MutableList<T>>, JavaDoublingPaths.Doubling14<T>

interface BoundedReceiverDoubling15<T> : BoundedReceiverDoubling14<MutableList<T>>, JavaDoublingPaths.Doubling15<T>

interface BoundedReceiverDoubling16<T> : BoundedReceiverDoubling15<MutableList<T>>, JavaDoublingPaths.Doubling16<T>

fun BoundedReceiverDoubling0<*>.doublingPaths() {}

fun BoundedReceiverDoubling0<String>.doublingStrings() {}

class BoundedReceiverDoublingChain : BoundedReceiverDoubling16<String>

// also when the supertype is reached through Kotlin late, after the limit, 2^5 of them, the one of Kotlin, BoundedReceiverDoublingRows<List<…>>,
// replacing one of the ones of Java, BoundedReceiverDoublingRows<(Mutable)List<…>!>, which kotlinc leaves out
interface BoundedReceiverDoublingPad0 : BoundedReceiverDoublingRows<List<MutableList<MutableList<MutableList<MutableList<MutableList<String>>>>>>>

interface BoundedReceiverDoublingPad1 : BoundedReceiverDoublingPad0

interface BoundedReceiverDoublingPad2 : BoundedReceiverDoublingPad1

interface BoundedReceiverDoublingPad3 : BoundedReceiverDoublingPad2

interface BoundedReceiverDoublingPad4 : BoundedReceiverDoublingPad3

interface BoundedReceiverDoublingPad5 : BoundedReceiverDoublingPad4

interface BoundedReceiverDoublingPad6 : BoundedReceiverDoublingPad5

interface BoundedReceiverDoublingPad7 : BoundedReceiverDoublingPad6

interface BoundedReceiverDoublingPad8 : BoundedReceiverDoublingPad7

interface BoundedReceiverDoublingPad9 : BoundedReceiverDoublingPad8

interface BoundedReceiverDoublingPad10 : BoundedReceiverDoublingPad9

interface BoundedReceiverDoublingPad11 : BoundedReceiverDoublingPad10

interface BoundedReceiverDoublingPad12 : BoundedReceiverDoublingPad11

interface BoundedReceiverDoublingPad13 : BoundedReceiverDoublingPad12

interface BoundedReceiverDoublingPad14 : BoundedReceiverDoublingPad13

interface BoundedReceiverDoublingPad15 : BoundedReceiverDoublingPad14

interface BoundedReceiverDoublingPad16 : BoundedReceiverDoublingPad15

interface BoundedReceiverDoublingPad17 : BoundedReceiverDoublingPad16

interface BoundedReceiverDoublingPad18 : BoundedReceiverDoublingPad17

interface BoundedReceiverDoublingPad19 : BoundedReceiverDoublingPad18

interface BoundedReceiverDoublingPad20 : BoundedReceiverDoublingPad19

interface BoundedReceiverDoublingPad21 : BoundedReceiverDoublingPad20

interface BoundedReceiverDoublingPad22 : BoundedReceiverDoublingPad21

interface BoundedReceiverDoublingPad23 : BoundedReceiverDoublingPad22

interface BoundedReceiverDoublingPad24 : BoundedReceiverDoublingPad23

class BoundedReceiverDoublingPadChain : BoundedReceiverDoubling5<String>, BoundedReceiverDoublingPad24

class BoundedReceiverShortDoublingChain : BoundedReceiverDoubling5<String>

// and so beyond the limit of the types of a supertype which are walked, 2^7 of them, reached only through Java,
// or else through Kotlin late, after them
fun BoundedReceiverDoublingRows<MutableList<MutableList<MutableList<MutableList<MutableList<MutableList<MutableList<MutableList<String>>>>>>>>>.doublingMutableLongRows() {}

class BoundedReceiverLongDoublingChain : BoundedReceiverDoubling7<String>

interface BoundedReceiverDoublingLongPad0 : BoundedReceiverDoublingRows<List<MutableList<MutableList<MutableList<MutableList<MutableList<MutableList<MutableList<String>>>>>>>>>

interface BoundedReceiverDoublingLongPad1 : BoundedReceiverDoublingLongPad0

interface BoundedReceiverDoublingLongPad2 : BoundedReceiverDoublingLongPad1

interface BoundedReceiverDoublingLongPad3 : BoundedReceiverDoublingLongPad2

interface BoundedReceiverDoublingLongPad4 : BoundedReceiverDoublingLongPad3

interface BoundedReceiverDoublingLongPad5 : BoundedReceiverDoublingLongPad4

interface BoundedReceiverDoublingLongPad6 : BoundedReceiverDoublingLongPad5

interface BoundedReceiverDoublingLongPad7 : BoundedReceiverDoublingLongPad6

interface BoundedReceiverDoublingLongPad8 : BoundedReceiverDoublingLongPad7

interface BoundedReceiverDoublingLongPad9 : BoundedReceiverDoublingLongPad8

interface BoundedReceiverDoublingLongPad10 : BoundedReceiverDoublingLongPad9

interface BoundedReceiverDoublingLongPad11 : BoundedReceiverDoublingLongPad10

interface BoundedReceiverDoublingLongPad12 : BoundedReceiverDoublingLongPad11

class BoundedReceiverLongDoublingPadChain : BoundedReceiverDoubling7<String>, BoundedReceiverDoublingLongPad12

// a raw Kotlin supertype of a Java one erased to a not nullable bound, like CharSequence of T : CharSequence,
// or through the bound of a type parameter, like Any? of B : A
interface BoundedReceiverCharSequenceListPaths<T : CharSequence> : BoundedReceiverPaths<List<T>>

interface BoundedReceiverBoundedListPaths<A, B : A> : BoundedReceiverPaths<List<B>>

fun BoundedReceiverPaths<List<CharSequence>>.charSequenceListPaths() {}

fun BoundedReceiverPaths<List<CharSequence?>>.nullableCharSequenceListPaths() {}

class BoundedReceiverRawCharSequenceListPaths : JavaRawCharSequenceListPaths

class BoundedReceiverRawBoundedListPaths : JavaRawBoundedListPaths

// the erased type argument, CharSequence, substituted for a type parameter of a Java supertype, is a platform type then,
// like BoundedReceiverPaths<(Mutable)List<CharSequence!>!> of JavaListPathsOf<CharSequence>
interface BoundedReceiverCharSequenceJavaListPaths<T : CharSequence> : JavaListPathsOf<T>

class BoundedReceiverRawCharSequenceJavaListPaths : JavaRawCharSequenceJavaListPaths

// a type parameter of several bounds, like BoundedReceiverPaths<List<E>> and BoundedReceiverPathsOf<E>, satisfied by kotlinc
// through either supertype reached through Kotlin and through Java, like BoundedReceiverPaths<List<String!>> for E of String?,
// unlike BoundedReceiverPaths<List<String>>, which is reached first
interface BoundedReceiverPathsOf<T>

fun <E, P> P.listPathsOf() where P : BoundedReceiverPaths<List<E>>, P : BoundedReceiverPathsOf<E> {}

fun <E, P> P.pathsOfList() where P : BoundedReceiverPathsOf<E>, P : BoundedReceiverPaths<List<E>> {}

interface BoundedReceiverNullablePathsOf : BoundedReceiverStringListPaths, JavaStringListPaths, BoundedReceiverPathsOf<String?>

interface BoundedReceiverJavaNullablePathsOf : JavaStringListPaths, BoundedReceiverStringListPaths, BoundedReceiverPathsOf<String?>

interface BoundedReceiverIntPathsOf : BoundedReceiverStringListPaths, JavaStringListPaths, BoundedReceiverPathsOf<Int>

// also of sibling type arguments, the supertype of the first one, reached through Kotlin and through Java,
// matched together with the second one
interface BoundedReceiverPathsPair<out A, B>

fun <E> BoundedReceiverPathsPair<BoundedReceiverPaths<List<E>>, E>.pathsPair() {}

class BoundedReceiverNullablePathsPair : BoundedReceiverPathsPair<BoundedReceiverJavaKotlinListPaths, String?>

class BoundedReceiverIntPathsPair : BoundedReceiverPathsPair<BoundedReceiverJavaKotlinListPaths, Int>

// a raw Kotlin supertype of a Java one erased to a bound taking type arguments, star projected where it refers to the type parameter,
// like Comparable<*> of T : Comparable<T>, a mutable collection, like MutableList<String> of T : MutableList<String>,
// or to the first one of bounds of different nullability, like CharSequence? of T : CharSequence?, T : Comparable<T>
interface BoundedReceiverComparableListPaths<T : Comparable<T>> : BoundedReceiverPaths<List<T>>

interface BoundedReceiverMutableBoundListPaths<T : MutableList<String>> : BoundedReceiverPaths<List<T>>

interface BoundedReceiverMixedNullabilityListPaths<T> : BoundedReceiverPaths<List<T>> where T : CharSequence?, T : Comparable<T>

fun BoundedReceiverPaths<List<Comparable<*>>>.comparableListPaths() {}

fun BoundedReceiverPaths<List<Comparable<*>?>>.nullableComparableListPaths() {}

fun BoundedReceiverPaths<List<MutableList<String>>>.mutableStringListListPaths() {}

fun BoundedReceiverPaths<List<List<String>>>.stringListListPaths() {}

class BoundedReceiverRawComparableListPaths : JavaRawComparableListPaths

class BoundedReceiverRawMutableBoundListPaths : JavaRawMutableBoundListPaths

class BoundedReceiverRawMixedNullabilityListPaths : JavaRawMixedNullabilityListPaths

// a supertype reached through more paths without platform type arguments than walked, 2^5 of them,
// BoundedReceiverDoubling0<MutableList<…>>, the ones beyond generalized, still telling the one matching only a late one
fun BoundedReceiverDoubling0<MutableList<MutableList<MutableList<List<List<List<String>>>>>>>.doublingLatePaths() {}

class BoundedReceiverDoubling6Chain : BoundedReceiverDoubling6<String>

// and one of the 2^8 of them, beyond the limit of the ones walked
fun BoundedReceiverDoubling0<MutableList<MutableList<MutableList<MutableList<List<List<List<List<String>>>>>>>>>.doublingLongLatePaths() {}

class BoundedReceiverDoubling8Chain : BoundedReceiverDoubling8<String>

// the type arguments of an erased bound are not platform types either, like String of MutableList<String> of T : MutableList<String>
interface BoundedReceiverMutableBoundPaths<T : MutableList<String>> : BoundedReceiverPaths<T>

fun BoundedReceiverPaths<MutableList<String?>>.nullableMutableStringPaths() {}

class BoundedReceiverRawMutableBoundPaths : JavaRawMutableBoundPaths

// a supertype reached through more paths than walked, of nullable type arguments through Kotlin, and platform ones through Java,
// like BoundedReceiverNullableChain0<BoundedReceiverWrap<…?>> and BoundedReceiverNullableChain0<BoundedReceiverWrap<…!>!>,
// none of which takes a String for an Int
interface BoundedReceiverWrap<T>

interface BoundedReceiverNullableChain0<T>

interface BoundedReceiverNullableChain1<T> : BoundedReceiverNullableChain0<BoundedReceiverWrap<T?>>, JavaNullableChainPaths.Chain1<T>

interface BoundedReceiverNullableChain2<T> : BoundedReceiverNullableChain1<BoundedReceiverWrap<T?>>, JavaNullableChainPaths.Chain2<T>

interface BoundedReceiverNullableChain3<T> : BoundedReceiverNullableChain2<BoundedReceiverWrap<T?>>, JavaNullableChainPaths.Chain3<T>

interface BoundedReceiverNullableChain4<T> : BoundedReceiverNullableChain3<BoundedReceiverWrap<T?>>, JavaNullableChainPaths.Chain4<T>

interface BoundedReceiverNullableChain5<T> : BoundedReceiverNullableChain4<BoundedReceiverWrap<T?>>, JavaNullableChainPaths.Chain5<T>

interface BoundedReceiverNullableChain6<T> : BoundedReceiverNullableChain5<BoundedReceiverWrap<T?>>, JavaNullableChainPaths.Chain6<T>

fun BoundedReceiverNullableChain0<*>.nullableChainPaths() {}

fun BoundedReceiverNullableChain0<BoundedReceiverWrap<BoundedReceiverWrap<BoundedReceiverWrap<BoundedReceiverWrap<BoundedReceiverWrap<BoundedReceiverWrap<String?>>?>?>?>?>>.nullableChainStrings() {}

// a platform type made nullable, like BoundedReceiverWrap<…!>! substituted for T of BoundedReceiverWrap<T?>, is nullable
fun <T> BoundedReceiverNullableChain0<BoundedReceiverWrap<BoundedReceiverWrap<BoundedReceiverWrap<BoundedReceiverWrap<BoundedReceiverWrap<BoundedReceiverWrap<T>>>>>>>.nonNullChainPaths() {}

class BoundedReceiverIntNullableChain : BoundedReceiverNullableChain6<Int>

// one of the 2^7 types of a level more, reached through Java first, and through Kotlin at last, among the last ones walked
interface BoundedReceiverNullableChain7<T> : BoundedReceiverNullableChain6<BoundedReceiverWrap<T?>>, JavaNullableChainPaths.Chain7<T>

fun BoundedReceiverNullableChain0<BoundedReceiverWrap<BoundedReceiverWrap<BoundedReceiverWrap<BoundedReceiverWrap<BoundedReceiverWrap<BoundedReceiverWrap<BoundedReceiverWrap<String>>>>>>?>>.lateNullableChainStrings() {}

class BoundedReceiverStringNullableChain : BoundedReceiverNullableChain7<String>

// a type parameter of more bounds, each reached through Kotlin and through Java, than the combinations of paths matched,
// see matchesThroughAnyPaths, none of which takes E of Int
interface BoundedReceiverManyPaths0<T>
interface BoundedReceiverManyPaths1<T>
interface BoundedReceiverManyPaths2<T>
interface BoundedReceiverManyPaths3<T>
interface BoundedReceiverManyPaths4<T>
interface BoundedReceiverManyPaths5<T>
interface BoundedReceiverManyPaths6<T>
interface BoundedReceiverManyPaths7<T>
interface BoundedReceiverManyPaths8<T>

interface BoundedReceiverListManyPaths0<T> : BoundedReceiverManyPaths0<List<T>>
interface BoundedReceiverListManyPaths1<T> : BoundedReceiverManyPaths1<List<T>>
interface BoundedReceiverListManyPaths2<T> : BoundedReceiverManyPaths2<List<T>>
interface BoundedReceiverListManyPaths3<T> : BoundedReceiverManyPaths3<List<T>>
interface BoundedReceiverListManyPaths4<T> : BoundedReceiverManyPaths4<List<T>>
interface BoundedReceiverListManyPaths5<T> : BoundedReceiverManyPaths5<List<T>>
interface BoundedReceiverListManyPaths6<T> : BoundedReceiverManyPaths6<List<T>>
interface BoundedReceiverListManyPaths7<T> : BoundedReceiverManyPaths7<List<T>>
interface BoundedReceiverListManyPaths8<T> : BoundedReceiverManyPaths8<List<T>>

fun <E, P> P.manyPaths() where P : BoundedReceiverManyPaths0<List<E>>, P : BoundedReceiverManyPaths1<List<E>>,
    P : BoundedReceiverManyPaths2<List<E>>, P : BoundedReceiverManyPaths3<List<E>>, P : BoundedReceiverManyPaths4<List<E>>,
    P : BoundedReceiverManyPaths5<List<E>>, P : BoundedReceiverManyPaths6<List<E>>, P : BoundedReceiverManyPaths7<List<E>>,
    P : BoundedReceiverManyPaths8<List<E>>, P : BoundedReceiverPathsOf<E> {}

interface BoundedReceiverIntManyPaths :
    BoundedReceiverManyPaths0<List<String>>, JavaManyListPaths.Strings0,
    BoundedReceiverManyPaths1<List<String>>, JavaManyListPaths.Strings1,
    BoundedReceiverManyPaths2<List<String>>, JavaManyListPaths.Strings2,
    BoundedReceiverManyPaths3<List<String>>, JavaManyListPaths.Strings3,
    BoundedReceiverManyPaths4<List<String>>, JavaManyListPaths.Strings4,
    BoundedReceiverManyPaths5<List<String>>, JavaManyListPaths.Strings5,
    BoundedReceiverManyPaths6<List<String>>, JavaManyListPaths.Strings6,
    BoundedReceiverManyPaths7<List<String>>, JavaManyListPaths.Strings7,
    BoundedReceiverManyPaths8<List<String>>, JavaManyListPaths.Strings8,
    BoundedReceiverPathsOf<Int>

// while the platform type String! of each bound, chosen bound by bound, like kotlinc, takes the E of String?,
// which only one of these combinations does
interface BoundedReceiverNullableStringManyPaths :
    BoundedReceiverManyPaths0<List<String>>, JavaManyListPaths.Strings0,
    BoundedReceiverManyPaths1<List<String>>, JavaManyListPaths.Strings1,
    BoundedReceiverManyPaths2<List<String>>, JavaManyListPaths.Strings2,
    BoundedReceiverManyPaths3<List<String>>, JavaManyListPaths.Strings3,
    BoundedReceiverManyPaths4<List<String>>, JavaManyListPaths.Strings4,
    BoundedReceiverManyPaths5<List<String>>, JavaManyListPaths.Strings5,
    BoundedReceiverManyPaths6<List<String>>, JavaManyListPaths.Strings6,
    BoundedReceiverManyPaths7<List<String>>, JavaManyListPaths.Strings7,
    BoundedReceiverManyPaths8<List<String>>, JavaManyListPaths.Strings8,
    BoundedReceiverPathsOf<String?>

// a platform type substituted for a nullable type parameter, like String! for T of BoundedReceiverWrap<T?>,
// is nullable for a type it is matched against, but infers a type parameter of a nullable type, like T of T?, as not nullable
interface BoundedReceiverNullableWrapPaths<T> : BoundedReceiverPaths<BoundedReceiverWrap<T?>>

interface BoundedReceiverNullableWrapWrapPaths<T> : BoundedReceiverPaths<BoundedReceiverWrap<BoundedReceiverWrap<T?>>>

fun <T : Any> BoundedReceiverPaths<BoundedReceiverWrap<T?>>.nonNullWrapPaths() {}

fun <T : Any> BoundedReceiverPaths<BoundedReceiverWrap<BoundedReceiverWrap<T?>>>.nonNullWrapWrapPaths() {}

fun BoundedReceiverPaths<BoundedReceiverWrap<String>>.stringWrapPaths() {}

class BoundedReceiverJavaNullableWrapPaths : JavaStringNullableWrapPaths.Wrap

class BoundedReceiverJavaNullableWrapWrapPaths : JavaStringNullableWrapPaths.WrapWrap

// a type parameter of a contravariant position, which nothing else constrains than the return type,
// inferred from the receiver alone, as a call without an expected type tells nothing of it,
// like T : Comparable<T> as Any, which is no Comparable<Any>, or T of List<T> for Any, which kotlinc cannot infer
interface BoundedReceiverTaker<in T>

fun <T : Comparable<T>> BoundedReceiverTaker<T>.takeComparable(): T? = null

fun <T> BoundedReceiverTaker<List<T>>.takeFirst(): T? = null

val <T> BoundedReceiverTaker<List<T>>.firstTaken: T? get() = null

// mutually recursive bounds, U fixed by kotlinc as Comparable<T>, which T must be a Comparable of,
// like String, a Comparable<Comparable<String>>, but not List<String>
fun <T : Comparable<U>, U : Comparable<T>> BoundedReceiverTaker<T>.takeMutuallyComparable() {}

class BoundedReceiverAnyTaker : BoundedReceiverTaker<Any>

class BoundedReceiverStringListTaker : BoundedReceiverTaker<List<String>>

class BoundedReceiverStringTaker : BoundedReceiverTaker<String>

// the type parameters inferred like kotlinc does, by the constraints of the receiver and of their bounds, see #292,
// fixed one by one, the first one declared of the readiest ones first:
// a type parameter bounding multiple ones, like A of B : A, and C : A, is fixed first when declared first,
// as the type of its own position, like Any, no Comparable<Any>, and after B, as String, when B is declared first,
// also bounding them through a chain, like A of C : B, B : A, or bounded by a type parameter declared later, like T : Comparable<B>
interface InferredReceiverTripleSink<in A, in B, in C>

fun <A : Comparable<A>, B : A, C : A> InferredReceiverTripleSink<B, C, A>.tripleFanFirst() {}

fun <B : A, A : Comparable<A>, C : A> InferredReceiverTripleSink<B, C, A>.tripleFanMiddle() {}

fun <C : B, B : A, A : Comparable<A>> InferredReceiverTripleSink<A, B, C>.tripleChain() {}

fun <A : Comparable<A>, T : Comparable<B>, B : A> InferredReceiverTripleSink<T, A, B>.tripleComparableOfLater() {}

class InferredReceiverStringStringAnyTripleSink : InferredReceiverTripleSink<String, String, Any>

class InferredReceiverCharSequenceTripleSink : InferredReceiverTripleSink<CharSequence, CharSequence, CharSequence>

class InferredReceiverStringComparableIntTripleSink : InferredReceiverTripleSink<String, Comparable<String>, Int>

// a type parameter bounding the one of the receiver, like T of U : T, takes its type, which satisfies its recursive bound, or not,
// like BoundedReceiverSubSelfList widened to BoundedReceiverSelfList, a List<BoundedReceiverSelfList>, unlike String
interface InferredReceiverSink<in T>

fun <T : List<T>, U : T> InferredReceiverSink<U>.sinkUnderSelfList() {}

class InferredReceiverStringSink : InferredReceiverSink<String>

class InferredReceiverSubSelfListSink : InferredReceiverSink<BoundedReceiverSubSelfList>

// a covariant recursive bound is satisfied by the common supertype of the type, and of its type arguments, if any,
// like List<BoundedReceiverSelfList> of List<BoundedReceiverSubSelfList>, but none of List<List<String>>, as String is no List
interface InferredReceiverSource<out T>

fun <T : List<T>> InferredReceiverSource<T>.sourceSelfList() {}

class InferredReceiverStringListsSource : InferredReceiverSource<List<List<String>>>

class InferredReceiverSubSelfListsSource : InferredReceiverSource<List<List<BoundedReceiverSubSelfList>>>

// a type parameter bounded by another one, in a type argument, like B of List<B>, which nothing constrains for Any,
// is fixed after A, as its type, like String
interface InferredReceiverPairSink<in A, in B>

fun <A : Comparable<A>, B : A> InferredReceiverPairSink<List<B>, A>.pairSinkListBounded() {}

class InferredReceiverAnyStringPairSink : InferredReceiverPairSink<Any, String>

class InferredReceiverStringStringPairSink : InferredReceiverPairSink<String, String>

// a star projection of an invariant type parameter is the same type as its in Nothing, and its out Any?, projections,
// also as a type argument of an invariant position, each of them matching the other ones
interface InferredReceiverCell<T>

fun InferredReceiverCell<MutableList<*>>.cellOfStar() {}

fun InferredReceiverCell<MutableList<in Nothing>>.cellOfInNothing() {}

fun InferredReceiverCell<MutableList<out Any?>>.cellOfOutAny() {}

class InferredReceiverStarCell : InferredReceiverCell<MutableList<*>>

class InferredReceiverInNothingCell : InferredReceiverCell<MutableList<in Nothing>>

class InferredReceiverOutAnyCell : InferredReceiverCell<MutableList<out Any?>>

// where the inference of kotlinc is not modelled, the type parameters are inferred one by one while matching, see #292:
// a function type matched against a class which is none is no receiver for kotlinc, whatever the bounds infer
interface InferredReceiverMixed<out A, in B>

fun <T : Comparable<T>, U : T> InferredReceiverMixed<T, (T) -> U>.mixedFunction() {}

class InferredReceiverStringStringMixed : InferredReceiverMixed<String, String>

// nor is Nothing next to a function type the same type in invariant positions
interface InferredReceiverPair<A, B>

fun <A, B : (A) -> Unit> InferredReceiverPair<B, B>.pairOfFunction() {}

class InferredReceiverNothingFunctionPair : InferredReceiverPair<Nothing, (String) -> Unit>

// a type parameter given explicitly, like W, makes the violated bound of another one an error,
// also next to one inferred as an empty intersection, where it is only a warning when all of them are inferred
fun <V : T, W, T : List<V>> InferredReceiverSink<T>.sinkListExplicit() {}

// type parameters bounded by each other through type arguments, next to Nothing, or Nothing?,
// which kotlinc accepts, also with a warning of an empty intersection, like of the final String and Map
interface InferredReceiverBiSink<in A, in B>

fun <T : List<U>, U : List<T>> InferredReceiverBiSink<T, U>.biSinkMutualLists() {}

fun <T : Map<U, T>, U : T> InferredReceiverBiSink<U, T>.biSinkMap() {}

fun <A : Comparable<A>, B : (A) -> A> InferredReceiverBiSink<A, B>.biSinkFunction() {}

class InferredReceiverAnyNullableNothingBiSink : InferredReceiverBiSink<Any, Nothing?>

class InferredReceiverNothingStringBiSink : InferredReceiverBiSink<Nothing, String>

class InferredReceiverAnyStringBiSink : InferredReceiverBiSink<Any, String>

// Nothing, or Nothing?, anywhere in the types matched, like in a type argument, or in a supertype of one,
// is inferred by kotlinc in a way which is not modelled, the type parameters inferred one by one then
class InferredReceiverNullableNothingListsBiSink : InferredReceiverBiSink<List<Nothing?>, List<Nothing?>>

interface InferredReceiverNothingSource : InferredReceiverSource<Nothing>

fun <T : InferredReceiverSource<U>, U : InferredReceiverSource<T>> InferredReceiverBiSink<T, U>.biSinkMutualSources() {}

class InferredReceiverNothingSourcesBiSink : InferredReceiverBiSink<InferredReceiverNothingSource, InferredReceiverNothingSource>

interface InferredReceiverTriple<out A, in B, in C>

fun <T, U : T> InferredReceiverTriple<T, U, U>.tripleUnder() {}

class InferredReceiverNullableNothingTriple : InferredReceiverTriple<Nothing?, CharSequence, Comparable<String>>

// a type parameter which has to be given explicitly, like W, makes the violated bound of an inferred one an error,
// like String, no Map<String, String>, of T, which is only a warning when all of them are inferred
fun <W, V, T : Map<T, V>> InferredReceiverBiSink<V, T>.biSinkExplicit() {}

class InferredReceiverStringStringBiSink : InferredReceiverBiSink<String, String>
