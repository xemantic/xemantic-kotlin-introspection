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

import com.xemantic.kotlin.introspection.othermodule.InternalBase
import com.xemantic.kotlin.introspection.test.other.JavaClassBoundBase
import com.xemantic.kotlin.introspection.test.other.JavaFunctionTaker
import com.xemantic.kotlin.introspection.test.other.JavaGetterBase
import com.xemantic.kotlin.introspection.test.other.JavaLabelBase
import com.xemantic.kotlin.introspection.test.other.JavaPrimitiveBase
import com.xemantic.kotlin.introspection.test.other.JavaProcessorBase
import com.xemantic.kotlin.introspection.test.other.JavaSortBase
import com.xemantic.kotlin.introspection.test.other.JavaVoidBase
import com.xemantic.kotlin.introspection.test.other.PackagePrivateBase

// An overload of an open function of the superclass is not an override
open class OverloadedBase {
    open fun foo() {}
}

class OverloadingSubclass : OverloadedBase() {
    fun foo(x: Int) {}
}

// Final methods of java.lang.Object are not overridable,
// so functions sharing their names are not overrides
open class NonAnyBase

class ObjectMethodNamesSubclass : NonAnyBase() {
    fun notify(msg: String) {}
    fun wait(ms: Int) {}
}

// Protected members can be overridden as well, the subclass is open, otherwise they would not be part of the API
abstract class ProtectedAbstractBase {
    protected abstract fun bar()
}

open class ProtectedOverrideSubclass : ProtectedAbstractBase() {
    override fun bar() {}
}

// The protected override in a final class is not part of the API
class FinalProtectedOverrideSubclass : ProtectedAbstractBase() {
    override fun bar() {}
}

// An override of a generic function, next to its overload
interface Processor<T> {
    fun process(item: T)
}

abstract class StringProcessor : Processor<String> {
    override fun process(item: String) {}
    fun process(item: Int) {}
}

class IntProcessor : Processor<Int> {
    override fun process(item: Int) {}
    fun process(item: String) {}
}

class NullableStringProcessor : Processor<String?> {
    override fun process(item: String?) {}
    fun process(item: Int?) {}
}

// Overrides of functions declared further up the hierarchy
open class IntermediateProcessor : StringProcessor()

class DeepProcessor : IntermediateProcessor() {
    override fun process(item: String) {}
    override fun toString(): String = "DeepProcessor"
}

// Kotlin collection members, seen by Java as declaring Object parameters
abstract class StringList : AbstractList<String>() {
    override fun contains(element: String): Boolean = false
    override fun indexOf(element: String): Int = -1
}

abstract class IntList : AbstractList<Int>() {
    override fun contains(element: Int): Boolean = false
}

abstract class StringKeyMap : AbstractMap<String, Int>() {
    override fun get(key: String): Int? = null
    override fun containsKey(key: String): Boolean = false
}

// Java collection supertypes
abstract class JavaStringList : java.util.AbstractList<String>() {
    override fun get(index: Int): String = ""
    override fun contains(element: String): Boolean = false
    fun contains(element: Int): Boolean = false
}

// Functions taking value class parameters have mangled JVM names
class UIntProcessor : Processor<UInt> {
    override fun process(item: UInt) {}
}

open class UIntBase {
    open fun take(x: UInt) {}
}

class UIntSubclass : UIntBase() {
    override fun take(x: UInt) {}
    fun take(x: Int) {}
}

@JvmInline
value class ValueComparable(val x: Int) : Comparable<ValueComparable> {
    override fun compareTo(other: ValueComparable): Int = 0
}

class ValueComparableHolder : Comparable<ValueComparable> {
    override fun compareTo(other: ValueComparable): Int = 0
}

// A type argument nested in another type
abstract class ArrayProcessor<U> : Processor<Array<U>>

class StringArrayProcessor : ArrayProcessor<String>() {
    override fun process(item: Array<String>) {}
}

interface ItemsProcessor<T> {
    fun process(items: Array<T>)
}

class StringItemsProcessor : ItemsProcessor<String> {
    override fun process(items: Array<String>) {}
    fun process(items: Array<Int>) {}
}

// Overridable members of java.lang.Object, which are not members of Any,
// the class is open, so that its protected member is part of the API
open class Finalizable {
    protected fun finalize() {}
}

class CloneableCopy : Cloneable {
    public override fun clone(): Any = this
}

// A package-private Java method is not visible, therefore not overridable, from another package
class PackagePrivateSubclass : PackagePrivateBase() {
    fun hidden() {}
}

// Members of a Java superclass, the class is open, so that its protected member is part of the API
open class JavaProcessor : JavaProcessorBase<String>() {
    override fun process(item: String) {}
    fun process(item: Int) {}
    override fun handle(items: List<String>) {}
    override fun count(): Int = 0
    fun finish() {}
    override fun <N : Number> measure(value: N) {}
    fun measure(value: String) {}
    override fun all(items: Array<String>) {}
    fun all(items: Array<Int>) {}
}

// An interface overriding a member of its superinterface
interface StringProcessorInterface : Processor<String> {
    override fun process(item: String)
}

// A type parameter of the class passed as a type argument of its supertype
class Box<T> : Processor<T> {
    override fun process(item: T) {}
    fun process(item: List<T>) {}
}

// Suspend functions take a trailing Continuation on the JVM
interface SuspendProcessor<T> {
    suspend fun process(item: T)
}

class UIntSuspendProcessor : SuspendProcessor<UInt> {
    override suspend fun process(item: UInt) {}
}

// Receivers precede the value parameters on the JVM
interface ReceiverProcessor<T> {
    suspend fun Int.handle(x: T)
    fun T.describe(): String
}

class UIntReceiverProcessor : ReceiverProcessor<UInt> {
    override suspend fun Int.handle(x: UInt) {}
    override fun UInt.describe(): String = ""
    fun describe(): String = ""
}

// A function name containing a dash, which is not a value class mangling suffix
class DashedNameProcessor : Processor<String> {
    override fun process(item: String) {}
    fun `process-handler`(item: String) {}
}

// A function of an inner class referring to a type parameter of the outer class
open class ProbeOuter<T> {
    open inner class Inner {
        open fun f(t: T) {}
    }
}

class ProbeOuterString : ProbeOuter<String>() {
    inner class Sub : ProbeOuter<String>.Inner() {
        override fun f(t: String) {}
        fun f(t: Int) {}
    }
}

// Type parameters of functions are matched by their position
interface GenericFunctions {
    fun <T> transform(item: T): T
    fun <K, V> pair(key: K, value: V)
}

class GenericFunctionsImpl : GenericFunctions {
    override fun <R> transform(item: R): R = item
    override fun <A, B> pair(key: A, value: B) {}
    fun <A, B> pair(key: B, value: A, extra: Int) {}
}

// The getter of an isXxx property is named isXxx, not getIsXxx
interface Toggle {
    val isEnabled: Boolean
}

class EnabledToggle : Toggle {
    override val isEnabled: Boolean = true
}

class StandaloneToggle {
    val isEnabled: Boolean = true
}

// A getter renamed with @get:JvmName does not override a supertype function named like the default getter
open class FooGetterBase {
    open fun getFoo(): Int = 0
}

class RenamedFooGetter : FooGetterBase() {
    @get:JvmName("fooValue")
    val foo: Int = 0
}

// A @JvmField property has no getter, so it does not override a supertype function named like one
open class GetBarBase {
    open fun getBar(): Int = 0
}

class JvmFieldBar : GetBarBase() {
    @JvmField
    val bar: Int = 0
}

// A property of value class type, while the supertype has a function named like its getter
open class GetValueBase {
    open fun getValue(): Int = 0
}

class UValueNoOverride : GetValueBase() {
    val value: UInt = 0u
}

// The getter of a member extension property takes the receiver as a parameter
interface StringMeasure {
    val String.measure: Int
}

class StringLengthMeasure : StringMeasure {
    override val String.measure: Int get() = length
    val Int.measure: Int get() = this
}

// A value class receiver of a member extension property overriding a generic one
interface GenericReceiverMeasure<T> {
    val T.measure: Int
}

class UIntReceiverMeasure : GenericReceiverMeasure<UInt> {
    override val UInt.measure: Int get() = 0
}

// The getter of a property of value class type has a mangled JVM name
interface UIntValueHolder {
    val value: UInt
}

class UIntValue : UIntValueHolder {
    override val value: UInt = 0u
}

// Primary constructor properties
interface HasU {
    val u: UInt
}

class HasUImpl(override val u: UInt, val extra: Int) : HasU

// Mapped Java collection members, like Collection.size being size() on the JVM
abstract class SizedCollection : java.util.AbstractCollection<String>() {
    override val size: Int get() = 0
}

// Kotlin built-in members mapped to differently named JVM methods
abstract class RemovingList : java.util.ArrayList<String>() {
    override fun removeAt(index: Int): String = ""
}

abstract class Chars : CharSequence {
    override val length: Int get() = 0
    override fun get(index: Int): Char = ' '
}

abstract class SmallNumber : Number() {
    override fun toInt(): Int = 0
    override fun toByte(): Byte = 0
}

abstract class KeyedMap : Map<String, Int> {
    override val keys: Set<String> get() = emptySet()
    override val entries: Set<Map.Entry<String, Int>> get() = emptySet()
}

// Java members of a mapped built-in type
class StacklessException : Exception() {
    override fun fillInStackTrace(): Throwable = this
    override val message: String? get() = null
}

// Members implemented in an object and its companion object
object ObjectProcessor : Processor<String> {
    override fun process(item: String) {}
}

class CompanionProcessor {
    companion object : Processor<Int> {
        override fun process(item: Int) {}
    }
}

// The invoke function of a function type supertype
class FunctionTypeImpl : (String) -> Int {
    override fun invoke(p1: String): Int = 0
    fun invoke(p1: Int): Int = 0
}

class SuspendFunctionTypeImpl : suspend (String) -> Int {
    override suspend fun invoke(p1: String): Int = 0
    fun invoke(p1: String, p2: Int): Int = 0
}

// java.lang.Void is not Nothing
class JavaVoidVisitor : JavaVoidBase() {
    override fun visit(v: Void?) {}
}

// Properties without a JVM getter named like a Java method,
// a Kotlin property never overrides a Java getter, it would be an accidental override
class JavaGetterSubclass : JavaGetterBase() {
    @JvmField val bar: Int = 0
    private val parent: String = ""
    @get:JvmName("fooValue") val foo: Int = 0
    val value: UInt = 0u
}

// A Java primitive parameter is seen in Kotlin as non-nullable, a boxed one as of flexible nullability
class JavaPrimitiveSubclass : JavaPrimitiveBase() {
    fun prim(x: Int?) {}
    override fun boxed(x: Int) {}
}

class JavaNullablePrimitiveSubclass : JavaPrimitiveBase() {
    override fun prim(x: Int) {}
    override fun boxed(x: Int?) {}
}

// A type parameter bounded by Int? is boxed on the JVM, and not seen as Int by Kotlin
class JavaPrimitiveTypeParameterSubclass<T : Int?> : JavaPrimitiveBase() {
    fun prim(x: T) {}
}

// A Java type variable substituted with Int is of flexible nullability, not primitive
abstract class JavaNullableIntProcessor : JavaProcessorBase<Int>() {
    override fun process(item: Int?) {}
}

// A Java type variable with a parameterized bound is erased to the raw bound
class JavaSorter : JavaSortBase() {
    override fun <T : Comparable<T>> sort(x: T) {}
}

class CharSequenceSorter : JavaSortBase() {
    fun <T : CharSequence> sort(x: T) {}
}

// A Java method type variable bounded by a type variable of the class is erased to its type argument
class JavaClassBoundSubclass : JavaClassBoundBase<CharSequence>() {
    override fun <T : CharSequence> bounded(x: T) {}
    fun <T : Number> bounded(x: T) {}
}

// Members of Any, reached only through Java interfaces
data class SerializableData(val a: Int) : java.io.Serializable

class AnyMembersRunnable : Runnable {
    override fun run() {}
    override fun toString(): String = ""
    override fun equals(other: Any?): Boolean = false
    override fun hashCode(): Int = 0
}

// JDK members of mapped collection interfaces, not declared by the Kotlin built-in types
abstract class JdkList : List<String> {
    override fun spliterator(): java.util.Spliterator<String> = TODO()
    override fun forEach(action: java.util.function.Consumer<in String>) {}
    override fun stream(): java.util.stream.Stream<String> = TODO()
}

abstract class JdkMutableList : AbstractMutableList<String>() {
    override fun removeIf(filter: java.util.function.Predicate<in String>): Boolean = false
    override fun replaceAll(operator: java.util.function.UnaryOperator<String>) {}
}

abstract class JdkMap : MutableMap<String, Int> {
    override fun putIfAbsent(key: String, value: Int): Int? = null
}

// A Java parameter of a Kotlin function type
class KotlinFunctionTaker : JavaFunctionTaker() {
    override fun onEvent(callback: (String) -> Unit) {}
    fun onEvent(callback: (Int) -> Unit, extra: Int) {}
}

// Redundant use-site projections, the same as the declaration-site variance
interface RedundantProjection {
    @Suppress("REDUNDANT_PROJECTION")
    fun f(x: List<out String>)
    fun c(x: Comparable<String>)
}

abstract class RedundantProjectionImpl : RedundantProjection {
    override fun f(x: List<String>) {}
    @Suppress("REDUNDANT_PROJECTION")
    override fun c(x: Comparable<in String>) {}
}

interface PlainProjection {
    fun f(x: List<String>)
}

abstract class PlainProjectionImpl : PlainProjection {
    @Suppress("REDUNDANT_PROJECTION")
    override fun f(x: List<out String>) {}
}

interface RunnableExtension : Runnable

class RunnableExtensionImpl : RunnableExtension {
    override fun run() {}
    override fun hashCode(): Int = 1
}

// Context parameters are a part of the signature, a function with other ones is an overload
class Logger

class Ctx

open class ContextBase {
    context(log: Logger)
    open fun run() {}
    context(log: Logger)
    open val label: String get() = ""
}

class ContextOverloadingSubclass : ContextBase() {
    fun run() {}
    @Suppress("CONTEXTUAL_OVERLOAD_SHADOWED")
    context(c: Ctx)
    fun run() {}
    context(c: Ctx)
    val label: String get() = ""
}

class ContextOverridingSubclass : ContextBase() {
    context(log: Logger)
    override fun run() {}
    context(log: Logger)
    override val label: String get() = ""
}

interface ContextProcessor<T> {
    context(c: Ctx)
    fun process(item: T)
}

class UIntContextProcessor : ContextProcessor<UInt> {
    context(c: Ctx)
    override fun process(item: UInt) {}
    fun process(item: Int) {}
}

@JvmInline
value class ValueContextProcessor(val value: Int) : ContextProcessor<UInt> {
    context(c: Ctx)
    override fun process(item: UInt) {}
}

interface Contextual<C> {
    context(c: C)
    fun act()
}

class CtxContextual : Contextual<Ctx> {
    context(c: Ctx)
    override fun act() {}
    context(log: Logger)
    fun act() {}
}

// A Java method has no context parameters
class ContextRunnable : Runnable {
    override fun run() {}
    @Suppress("CONTEXTUAL_OVERLOAD_SHADOWED")
    context(c: Ctx)
    fun run() {}
}

// A Kotlin member with context parameters does not override a Java method taking them as its parameters
abstract class ContextConsumer : java.util.function.Consumer<Ctx> {
    context(c: Ctx)
    fun accept() {}
}

abstract class ContextLabel : JavaLabelBase<Ctx>() {
    context(c: Ctx)
    val label: String get() = ""
}

// The internal members of a class of another module cannot be overridden
class OtherModuleSubclass : InternalBase() {
    fun reset() {}
    val state: Int get() = 1
    override fun keep() {}
}
