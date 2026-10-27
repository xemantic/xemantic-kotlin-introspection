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
import com.xemantic.kotlin.introspection.test.other.JavaArrayBase
import com.xemantic.kotlin.introspection.test.other.JavaClassBoundBase
import com.xemantic.kotlin.introspection.test.other.JavaDollarBase
import com.xemantic.kotlin.introspection.test.other.JavaFunctionTaker
import com.xemantic.kotlin.introspection.test.other.JavaGetterBase
import com.xemantic.kotlin.introspection.test.other.JavaLocalizedNameBase
import com.xemantic.kotlin.introspection.test.other.JavaLabelBase
import com.xemantic.kotlin.introspection.test.other.JavaOuterBase
import com.xemantic.kotlin.introspection.test.other.JavaPrimitiveBase
import com.xemantic.kotlin.introspection.test.other.JavaProcessorBase
import com.xemantic.kotlin.introspection.test.other.JavaRawArrayMid
import com.xemantic.kotlin.introspection.test.other.JavaRawClassBoundMid
import com.xemantic.kotlin.introspection.test.other.JavaRawComparable
import com.xemantic.kotlin.introspection.test.other.JavaRawInnerMid
import com.xemantic.kotlin.introspection.test.other.JavaRawKotlinMid
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

// A Java superclass method taking a nested class named with a $
class JavaDollarTaker : JavaDollarBase() {
    override fun take(nested: NestedClassesHolder.`Dollar$Nested`) {}
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

// An interface overloading a member of its superinterface, compiled without DefaultImpls
interface OverloadingProcessorInterface : Processor<String> {
    fun process(item: Int)
}

// An interface overloading a member of a Java superinterface, compiled without DefaultImpls
interface OverloadingRunnableInterface : Runnable {
    fun run(times: Int)
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

// A property named like a function of the superclass, which is overridden as well
open class LenBase {
    open fun length(): Int = 0
}

class LenProbe : LenBase() {
    val length: Int = 0
    override fun length(): Int = 1
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

// compiled against a JDK older than 21, like the libraries targeting old JVMs, without List.addFirst to override,
// so that the JVM method takes String, not the Object of the erased E of java.util.List, and no bridge is generated
abstract class SequencedStringList : AbstractMutableList<String>() {
    fun addFirst(element: String) {}
}

// like SequencedStringList, taking String?, which still overrides the addFirst(E) of JDK 21 on the class,
// whose parameter is of a platform type, String! of the substituted E
abstract class NullableSequencedStringList : AbstractMutableList<String>() {
    fun addFirst(element: String?) {}
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

// A Java method taking an array of a type variable is erased to the array of its type argument
class JavaArraySubclass : JavaArrayBase<String>() {
    override fun process(items: Array<String>) {}
    fun process(items: Array<Int>) {}
    override fun <S : String> bounded(items: Array<S>) {}
    fun <S : Number> bounded(items: Array<S>) {}
    override fun nested(items: Array<Array<String>>) {}
    fun nested(items: Array<Array<Int>>) {}
    override fun lists(items: Array<List<String>>) {}
    fun lists(items: Array<Set<String>>) {}
}

// The Java type variables are bound per declaring class, of an inner class, of its outer class, or of a method
class JavaOuterSubclass : JavaOuterBase<String>() {
    override fun <T : Number> shadowing(x: T) {}
    fun <T : String> shadowing(x: T) {}

    inner class InnerSubclass : JavaOuterBase<String>.Inner<Int>() {
        override fun outer(x: String) {}
        fun outer(x: Int) {}
        override fun inner(x: Int) {}
        fun inner(x: String) {}
    }

    inner class ShadowingSubclass : JavaOuterBase<String>.Shadowing<Int>() {
        override fun shadowed(x: Int) {}
        fun shadowed(x: String) {}
    }
}

// The type variable of a raw Java superclass is erased to its bound
class JavaRawArraySubclass : JavaRawArrayMid() {
    override fun process(items: Array<Any?>) {}
    fun process(items: Array<Int>) {}
}

class JavaRawClassBoundSubclass : JavaRawClassBoundMid() {
    fun <T : Number> bounded(x: T) {}
}

// The type parameters of a raw Kotlin supertype of a Java class are erased to their bounds,
// also when bound by another type parameter, or by a type referring to the type parameter itself
open class KotlinBoundBase<T : CharSequence, C : Comparable<C>, U : T> {
    open fun bounded(x: T) {}
    open fun compared(x: C) {}
    open fun chained(x: U) {}
}

class JavaRawKotlinSubclass : JavaRawKotlinMid() {
    override fun bounded(x: CharSequence) {}
    fun bounded(x: String) {}
    override fun compared(x: Comparable<*>) {}
    fun compared(x: String) {}
    override fun chained(x: CharSequence) {}
    fun chained(x: String) {}
}

// The type parameters of a raw inner Kotlin supertype of a Java class, and of its outer class, are erased to their bounds,
// also when bound by a type parameter of the outer class
open class KotlinRawOuter<A : CharSequence> {
    open inner class Inner<B : Number, C : A> {
        open fun outer(x: A) {}
        open fun inner(x: B) {}
        open fun chained(x: C) {}
    }
}

class JavaRawInnerSubclass(outer: KotlinRawOuter<String>) : JavaRawInnerMid(outer) {
    override fun outer(x: CharSequence) {}
    fun outer(x: String) {}
    override fun inner(x: Number) {}
    fun inner(x: Int) {}
    override fun chained(x: CharSequence) {}
    fun chained(x: String) {}
}

// The type parameter of a raw built-in supertype of a Java class is erased to its bound
abstract class JavaRawComparableSubclass : JavaRawComparable() {
    override fun compareTo(other: Any?): Int = 0
    fun compareTo(other: String): Int = 0
}

// An override keeps its modality, abstract or final, while an open override is implicitly open
open class ModalityBase {
    open fun abstracted() {}
    open val abstractedValue: Int = 0
    open fun finalized() {}
    open val finalizedValue: Int = 0
    protected open fun protectedFinalized() {}
    open fun reopened() {}
}

abstract class ModalityOverrides : ModalityBase() {
    abstract override fun abstracted()
    abstract override val abstractedValue: Int
    final override fun finalized() {}
    final override val finalizedValue: Int = 0
    protected final override fun protectedFinalized() {}
    override fun reopened() {}
}

// A final override in a final class is redundant, as all its members are final
class FinalClassOverrides : ModalityBase() {
    final override fun finalized() {}
    override fun reopened() {}
}

// A final override declared in the primary constructor
abstract class FinalConstructorOverride(final override val u: UInt) : HasU

// An open property declared in the primary constructor
open class OpenConstructorProperty(open val a: Int, val b: Int)

// An open class whose only constructor is private cannot be subclassed by the user code,
// so that it can override neither its open members, nor its overrides, and cannot use its protected members
open class PrivateConstructorModality private constructor() : ModalityBase() {
    open fun f() {}
    protected open fun g() {}
    final override fun finalized() {}
}

// A sealed class can be subclassed by the user code only through an open subclass
sealed class ClosedSealedModality {
    open fun f() {}
    class Sub : ClosedSealedModality()
}

sealed class OpenSealedModality {
    open fun f() {}
    open class Sub : OpenSealedModality()
}

// Enum classes, whose entries can override their members
interface EnumOverridable {
    fun f(): Int
    fun g()
}

enum class EntryBodiesEnum : EnumOverridable {
    A { override fun f() = 2 },
    B;
    override fun f() = 1
    final override fun g() {}
}

enum class PlainEnum : EnumOverridable {
    A, B;
    override fun f() = 1
    final override fun g() {}
}

enum class PlainConstructorOverrideEnum(final override val u: UInt) : HasU {
    A(1u)
}

// An interface overriding a member with a default body, either keeping a default body, or making it abstract again
interface DefaultBodyBase {
    fun f() {}
}

interface DefaultBodyOverride : DefaultBodyBase {
    override fun f() {}
}

interface ReabstractedOverride : DefaultBodyBase {
    override fun f()
}

// Sealed classes, objects and companion objects as owners of overrides
sealed class SealedModalityOverrides : ModalityBase() {
    abstract override fun abstracted()
    final override fun finalized() {}
    override fun reopened() {}
}

object ObjectModalityOverrides : ModalityBase() {
    final override fun finalized() {}
    override fun reopened() {}
}

class CompanionModalityOverrides {
    companion object : ModalityBase() {
        final override fun finalized() {}
        override fun reopened() {}
    }
}

// Vararg properties declared in the primary constructor
open class VarargConstructorProperty(vararg open val xs: Int)

interface HasXs {
    val xs: IntArray
}

class VarargConstructorOverride(vararg override val xs: Int) : HasXs

// The @PublishedApi internal members of a class of another module cannot be overridden either
class PublishedApiSubclass : com.xemantic.kotlin.introspection.othermodule.PublishedApiBase() {
    fun publish() {}
}

// A member of the same signature as an internal member of a class of another module, overloaded by a public one
class OverloadedInternalSubclass : com.xemantic.kotlin.introspection.othermodule.OverloadedInternalBase() {
    fun reset() {}
}

// A member overloading both an internal member of a class of another module and a public one
class OverloadingInternalSubclass : com.xemantic.kotlin.introspection.othermodule.OverloadedInternalBase() {
    fun reset(name: String) {}
}

// A property named like a Java method taking a parameter, which is not its getter
class JavaLocalizedNameSubclass : JavaLocalizedNameBase() {
    val name: String get() = ""
}

// Overloads of members of JDK classes, which a shrinker never removes, also of the ones mapped to built-in types
class IndentedException : Exception() {
    fun toString(indent: Int): String = ""
}

abstract class OrderedList : java.util.AbstractList<String>() {
    fun sort(descending: Boolean) {}
}

abstract class OrderedMutableList : MutableList<String> {
    fun sort(descending: Boolean) {}
}

// A class of a JDK module defined by the application class loader, like jdk.compiler
abstract class DepthScanner : com.sun.source.util.TreeScanner<Unit, Unit>() {
    fun scan(depth: Int) {}
}

// A property whose getter, getProperty(int), is named like Properties.getProperty(String)
class IndexedProperties : java.util.Properties() {
    val Int.property: String get() = ""
}
