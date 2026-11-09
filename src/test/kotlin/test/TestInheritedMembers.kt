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

// Members inherited along a chain of superclasses, overridden halfway
open class InheritedBase {
    open fun greet(name: String): String = name
    val baseValue: Int = 42
    protected fun protectedHelper() {}
    internal fun internalHelper() {}
    @Deprecated("Hidden", level = DeprecationLevel.HIDDEN)
    fun hiddenHelper() {}
}

open class InheritedMiddle : InheritedBase() {
    override fun greet(name: String): String = name
}

class InheritedLeaf : InheritedMiddle()

open class OpenInheritedLeaf : InheritedMiddle()

// Members inherited from a generic interface, through a type argument
interface InheritedContainer<T> {
    fun take(): T
    fun put(item: T)
}

abstract class InheritedStringContainer : InheritedContainer<String> {
    override fun put(item: String) {}
}

// Members of a mapped built-in type inherited through a Java superclass
abstract class InheritedJavaList : java.util.AbstractList<String>()

// An interface extending a Java interface only, still a subtype of Any
interface InheritedJavaRunnable : Runnable

// A member of a direct supertype overridden by a subtype of it, reached deeper, through the superclass
interface InheritedShape {
    fun area(): Double
}

interface InheritedSquareShape : InheritedShape {
    override fun area(): Double = 1.0
}

abstract class InheritedSquareBase : InheritedSquareShape

abstract class InheritedSquare : InheritedShape, InheritedSquareBase()

// Overloads of a superclass, one of them overridden by an intermediate class
open class InheritedOverloadBase {
    open fun greet(name: String): String = name
    fun greet(name: String, times: Int): String = name.repeat(times)
}

open class InheritedOverloadMiddle : InheritedOverloadBase() {
    override fun greet(name: String): String = name
}

class InheritedOverloadLeaf : InheritedOverloadMiddle()

// Overloads declared by unrelated supertypes
interface InheritedIntFoo {
    fun foo(x: Int)
}

interface InheritedStringFoo {
    fun foo(x: String)
}

abstract class InheritedBothFoo : InheritedIntFoo, InheritedStringFoo

// A member inherited by a class whose extension of the same name is found as well
open class InheritedWithExtensionBase {
    fun extended(): Int = 0
}

class InheritedWithExtension : InheritedWithExtensionBase()

fun InheritedWithExtension.extended(times: Int): Int = times

// Overloads told apart only by the nullability of their parameter types
open class InheritedNullableOverloadBase {
    open fun f(x: Int) {}
    fun f(x: Int?) {}
}

class InheritedNullableOverloadLeaf : InheritedNullableOverloadBase()

open class InheritedNullableOverloadMiddle : InheritedNullableOverloadBase() {
    override fun f(x: Int) {}
}

class InheritedNullableOverloadMiddleLeaf : InheritedNullableOverloadMiddle()

// An overload declared with a type parameter named like the one of the supertype, which it does not override
open class InheritedNumberHolder<T : Number> {
    fun h(x: T) {}
}

class InheritedAnyHolder<T> : InheritedNumberHolder<Int>() {
    fun h(x: T) {}
}

// The members inherited by a sealed interface, with an open subclass, or without one
interface SealedInheritedBase {
    fun f() {}
}

sealed interface OpenSealedInheriting : SealedInheritedBase

open class OpenSealedInheritingImpl : OpenSealedInheriting

sealed interface ClosedSealedInheriting : SealedInheritedBase

class ClosedSealedInheritingImpl : ClosedSealedInheriting

// A member of Any inherited by a sealed class with an open subclass, which the user code can override
sealed class OpenSealedInheritingClass {
    open class Sub : OpenSealedInheritingClass()
}

// The members of Any inherited by a sealed interface without an open subclass
sealed interface SealedAnyInheriting {
    fun g(): Int = 1
}

class SealedAnyInheritingImpl : SealedAnyInheriting

// Overrides of JVM-only members of built-in types, taking platform types, like PrintStream! and Consumer<in T!>!
class InheritedJvmOverridingException : Exception() {
    override fun printStackTrace(s: java.io.PrintStream) {}
    override fun initCause(cause: Throwable?): Throwable = this
}

class InheritedJvmException : Exception()

class InheritedJvmOverridingIterable : Iterable<String> {
    override fun iterator(): Iterator<String> = emptyList<String>().iterator()
    override fun forEach(action: java.util.function.Consumer<in String>) {}
}

abstract class InheritedJvmOverridingList : java.util.AbstractList<String>() {
    override fun forEach(action: java.util.function.Consumer<in String>?) {}
}

// The same overrides declared by an intermediate class
open class InheritedJvmOverridingBaseException : Exception() {
    override fun printStackTrace(s: java.io.PrintStream) {}
}

class InheritedJvmOverridingLeafException : InheritedJvmOverridingBaseException()

open class InheritedJvmOverridingBaseIterable : Iterable<String> {
    override fun iterator(): Iterator<String> = emptyList<String>().iterator()
    override fun forEach(action: java.util.function.Consumer<in String>) {}
}

class InheritedJvmOverridingLeafIterable : InheritedJvmOverridingBaseIterable()

// A member with a body of an interface, inherited by a final class
interface InheritedDefaultGreeter {
    fun hello(): String = "hello"
}

class InheritedDefaultGreeterImpl : InheritedDefaultGreeter

// Members of the same JVM signature of unrelated supertypes, one declared with the type parameter of its interface
interface InheritedGenericConsumer<T> {
    fun accept(x: T)
}

interface InheritedStringConsumer {
    fun accept(x: String)
}

abstract class InheritedBothConsumer : InheritedGenericConsumer<String>, InheritedStringConsumer

// Members of the same signature of unrelated supertypes, whose return types are related only through the Java mapping
interface InheritedJavaListReturn {
    fun items(): java.util.ArrayList<String>
}

interface InheritedMutableListReturn {
    fun items(): MutableList<String>
}

abstract class InheritedBothListReturn : InheritedJavaListReturn, InheritedMutableListReturn

// Overloads of an inner class told apart only by the bounded type parameters of its outer class
class InheritedOuter<T : Number, U : CharSequence> {

    open inner class Base {
        open fun f(x: T) {}
        fun f(x: U) {}
    }

    open inner class Middle : Base() {
        override fun f(x: T) {}
    }

    inner class Leaf : Middle()

}

// A member inherited through a chain of generic supertypes, the type argument given at the bottom
abstract class InheritedContainerOf<E> : InheritedContainer<List<E>>

abstract class InheritedIntListContainer : InheritedContainerOf<Int>()

// Methods declared only by a Java superclass
class InheritedJavaArrayList : java.util.ArrayList<String>()

class InheritedJavaHashMap : java.util.HashMap<String, Int>()

class InheritedGenericJavaHashMap<V> : java.util.HashMap<String, V>()

class InheritedNullableJavaHashMap : java.util.HashMap<String?, Int?>()

class InheritedJavaStringKeyMap<V> : com.xemantic.kotlin.introspection.test.other.JavaStringKeyMap<V>()

class InheritedJavaListValueMap<T> : com.xemantic.kotlin.introspection.test.other.JavaListValueMap<T>()

class InheritedJavaListKeyMap<T, V> : com.xemantic.kotlin.introspection.test.other.JavaListKeyMap<T, V>()

class InheritedJavaTypeVariableKeyMap<K : CharSequence, V> : com.xemantic.kotlin.introspection.test.other.JavaTypeVariableKeyMap<K, V>()

class InheritedJavaSelfKeyMap<V : Comparable<V>> : com.xemantic.kotlin.introspection.test.other.JavaSelfKeyMap<V>()

class InheritedJavaRawMap : com.xemantic.kotlin.introspection.test.other.JavaRawMap()

class InheritedJavaFirstList<E> : com.xemantic.kotlin.introspection.test.other.JavaFirstList<E>()

class InheritedJavaComparableFirstList<E : Comparable<E>> : com.xemantic.kotlin.introspection.test.other.JavaComparableFirstList<E>()

class InheritedJavaStringFirstList : com.xemantic.kotlin.introspection.test.other.JavaStringFirstList()

class InheritedJavaOverridingList<E> : com.xemantic.kotlin.introspection.test.other.JavaOverridingList<E>()

class InheritedJavaOverridingMap<K, V> : com.xemantic.kotlin.introspection.test.other.JavaOverridingMap<K, V>()

class InheritedJavaComparableBox : com.xemantic.kotlin.introspection.test.other.JavaComparableBox()

abstract class InheritedJavaAddFirstHolder : com.xemantic.kotlin.introspection.test.other.JavaAddFirstHolder()

class InheritedJavaVector : java.util.Vector<String>()

class InheritedJavaLinkedList : java.util.LinkedList<String>()

class InheritedJavaAtomicReference : java.util.concurrent.atomic.AtomicReference<String>()

abstract class InheritedKotlinContainsList : java.util.AbstractList<String>() {
    override fun contains(element: String): Boolean = false
}

abstract class InheritedKotlinContainsListSub : InheritedKotlinContainsList()

abstract class InheritedJavaCollectionDiamond : java.util.AbstractList<String>(), Collection<String>

open class OverridingJavaComparableFirstList<E : Comparable<E>> :
    com.xemantic.kotlin.introspection.test.other.JavaComparableFirstList<E>() {
    override fun addFirst(element: E?) {}
}

class OverridingJavaComparableFirstListSub : OverridingJavaComparableFirstList<String>()

abstract class InheritedNullableContainsList : java.util.AbstractList<String>() {
    override fun contains(element: String?): Boolean = true
}

interface MergedComputeIfAbsent : MutableMap<String, String> {
    override fun computeIfAbsent(key: String, mappingFunction: java.util.function.Function<in String, out String>): String
}

abstract class MergedJavaComputeIfAbsentJoin :
    com.xemantic.kotlin.introspection.test.other.JavaComputingMap<String, String>(), MergedComputeIfAbsent

interface PrimitivePut {
    fun put(key: Int): String
}

abstract class BoxedJavaPutJoin : com.xemantic.kotlin.introspection.test.other.JavaBoxedPut(), PrimitivePut

abstract class GenericBoxedJavaPutJoin : com.xemantic.kotlin.introspection.test.other.JavaIntegerPut(), PrimitivePut

abstract class NotNullBoxedJavaPutJoin : com.xemantic.kotlin.introspection.test.other.JavaNotNullBoxedPut(), PrimitivePut

abstract class PrimitiveBoxedJavaPutJoin : com.xemantic.kotlin.introspection.test.other.JavaPrimitivePut(), com.xemantic.kotlin.introspection.test.other.JavaBoxedPutInterface

interface GenericPut<T> {
    fun put(t: T): String
}

interface GenericPrimitivePutJoin : GenericPut<Int>, PrimitivePut

abstract class PrimitiveJavaGenericPutJoin : com.xemantic.kotlin.introspection.test.other.JavaPrimitivePut(), GenericPut<Int>

abstract class BoxedJavaGenericPutJoin : com.xemantic.kotlin.introspection.test.other.JavaBoxedPut(), GenericPut<Int>

abstract class NotNullBoxedJavaGenericPutJoin : com.xemantic.kotlin.introspection.test.other.JavaNotNullBoxedPut(), GenericPut<Int>

abstract class NotNullBoxedJavaPrimitiveGenericPutJoin :
    com.xemantic.kotlin.introspection.test.other.JavaNotNullBoxedPut(), PrimitivePut, GenericPut<Int>

interface UIntPut {
    fun put(key: UInt): String
}

interface GenericUIntPutJoin : GenericPut<UInt>, UIntPut

interface SuspendGenericPut<T> {
    suspend fun put(t: T): String
}

interface SuspendPrimitivePut {
    suspend fun put(key: Int): String
}

interface SuspendGenericPrimitivePutJoin : SuspendGenericPut<Int>, SuspendPrimitivePut

interface VarargGenericPut<T> {
    fun put(vararg t: T): String
}

interface VarargPrimitivePut {
    fun put(vararg key: Int): String
}

// put(vararg t: Int) of VarargGenericPut<Int> takes Array<out Int>, the one of VarargPrimitivePut IntArray
interface VarargGenericPrimitivePutJoin : VarargGenericPut<Int>, VarargPrimitivePut

interface PrimitivePutIfAbsentMap : MutableMap<Int, String>, com.xemantic.kotlin.introspection.test.other.JavaPrimitivePutIfAbsent

interface PrimitiveKeyMapMembers {
    fun computeIfAbsent(key: Int, mappingFunction: java.util.function.Function<in Int, out String>): String
    fun putIfAbsent(key: Int, value: String): String?
    fun getOrDefault(key: Int, defaultValue: String): String
    fun remove(key: Int, value: String): Boolean
}

abstract class PrimitiveKeyMapJoin : MutableMap<Int, String>, PrimitiveKeyMapMembers

interface StringKeyMapMembers {
    fun computeIfAbsent(key: String, mappingFunction: java.util.function.Function<in String, out String>): String
    fun putIfAbsent(key: String, value: String): String?
}

abstract class StringKeyMapJoin : MutableMap<String, String>, StringKeyMapMembers

interface GenericMapMembers<K, V> {
    fun putIfAbsent(key: K, value: V): V?
}

abstract class GenericMapJoin : MutableMap<Int, String>, GenericMapMembers<Int, String>

abstract class GenericHashMapJoin : java.util.HashMap<Int, String>(), GenericMapMembers<Int, String>

// the getOrDefault and remove of java.util.HashMap are Java methods taking Object, apart from the ones taking int
abstract class PrimitiveKeyHashMapJoin : java.util.HashMap<Int, String>(), PrimitiveKeyMapMembers

abstract class PrimitiveAndBoxedJavaPut : com.xemantic.kotlin.introspection.test.other.JavaPrimitiveAndBoxedPut()

abstract class BoxedJavaPutBelowPrimitive : com.xemantic.kotlin.introspection.test.other.JavaBoxedPutBelow()

abstract class NotNullBoxedJavaPutBelowPrimitive : com.xemantic.kotlin.introspection.test.other.JavaNotNullBoxedPutBelow()

interface DefaultedPrimitivePut {
    fun put(key: Int = 0): String
}

abstract class BoxedJavaPutInterfaceImplementation : com.xemantic.kotlin.introspection.test.other.JavaBoxedPutInterfaceImplementation()

interface PrimitivePairPut {
    fun put(a: Int, b: Int): String
}

abstract class BoxedPairJavaPut : com.xemantic.kotlin.introspection.test.other.JavaBoxedPairPut()

abstract class MixedPairJavaPut : com.xemantic.kotlin.introspection.test.other.JavaMixedPairPut()

interface PrimitivePutRedeclaring : PrimitivePut

abstract class BoxedJavaPutBelowInterface : com.xemantic.kotlin.introspection.test.other.JavaBoxedPutBelowInterface()

// the put(T) of JavaGenericPut<Integer> implements the put(t: T) of GenericPut<Int>, not the put(key: Int) of PrimitivePut,
// though the two Kotlin members alone are merged
abstract class GenericBoxedJavaPrimitiveGenericPutJoin :
    com.xemantic.kotlin.introspection.test.other.JavaIntegerPut(), PrimitivePut, GenericPut<Int>

interface MixedNullabilityTaker {
    fun take(first: String, second: String?): String
}

interface NotNullableTaker {
    fun take(first: String, second: String): String
}

abstract class MixedNullabilityJavaTakerJoin : com.xemantic.kotlin.introspection.test.other.JavaTwoStringTaker(), MixedNullabilityTaker

abstract class NotNullableJavaTakerJoin : com.xemantic.kotlin.introspection.test.other.JavaTwoStringTaker(), NotNullableTaker

interface NullableElementListTaker {
    fun take(list: List<String?>): String
}

abstract class NullableElementListJavaTakerJoin :
    com.xemantic.kotlin.introspection.test.other.JavaStringListTaker(), NullableElementListTaker

interface OutProjectedMutableListTaker {
    fun take(list: MutableList<out Int>): String
}

abstract class OutProjectedMutableListJavaTakerJoin :
    com.xemantic.kotlin.introspection.test.other.JavaExtendsIntegerListTaker(), OutProjectedMutableListTaker

interface PrimitiveTriplePut {
    fun put(a: Int, b: String, c: Int): String
}

abstract class BoxedTripleJavaPut : com.xemantic.kotlin.introspection.test.other.JavaBoxedTriplePut()

interface NullableLastTriplePut {
    fun put(a: Int, b: Int, c: Int?): String
}

abstract class BoxedNullableLastTripleJavaPut : com.xemantic.kotlin.introspection.test.other.JavaBoxedNullableLastTriplePut()

abstract class GenericJavaPutImplementation : com.xemantic.kotlin.introspection.test.other.JavaGenericPutImplementation()

abstract class GenericAndPrimitiveJavaPutImplementation :
    com.xemantic.kotlin.introspection.test.other.JavaGenericAndPrimitivePutImplementation()

abstract class PrimitiveAndNotNullBoxedJavaPut : com.xemantic.kotlin.introspection.test.other.JavaPrimitiveAndNotNullBoxedPut()

abstract class NotNullBoxedJavaPutOverPrimitive : com.xemantic.kotlin.introspection.test.other.JavaNotNullBoxedPutOverPrimitive()

interface IntGenericPutRedeclaring : GenericPut<Int> {
    override fun put(t: Int): String
}

abstract class RedeclaredGenericJavaPut : com.xemantic.kotlin.introspection.test.other.JavaRedeclaredGenericPutImplementation()

abstract class NullablePrimitiveRedeclaredGenericJavaPutJoin :
    NullablePrimitivePut, com.xemantic.kotlin.introspection.test.other.JavaRedeclaredGenericPutImplementation()

interface MutableListPut {
    fun put(list: MutableList<String>): String
}

// the List<String> given for the T of put(T) is no (Mutable)List<String!>!, so that the two are not merged
abstract class StringListGenericJavaMutableListPutJoin :
    com.xemantic.kotlin.introspection.test.other.JavaGenericPut<List<String>>(), MutableListPut

interface GenericGetOrDefault<K, V> {
    fun getOrDefault(key: K, defaultValue: V): V
}

// java.util.TreeMap inherits the default getOrDefault of java.util.Map, which implements the one of GenericGetOrDefault
abstract class GenericGetOrDefaultTreeMapJoin : java.util.TreeMap<String, String>(), GenericGetOrDefault<String, String>

interface PrimitiveCompareTo {
    fun compareTo(other: Int): Int
}

abstract class BoxedJavaComparablePrimitiveJoin :
    com.xemantic.kotlin.introspection.test.other.JavaIntegerComparable(), PrimitiveCompareTo

abstract class IndexedAddAllJavaListJoin : com.xemantic.kotlin.introspection.test.other.JavaIndexedAddAll(), MutableList<Int>

interface NullablePrimitivePut {
    fun put(key: Int?): String
}

// the put(int) of JavaPrimitivePut is another JVM method than put(Integer), which a concrete subclass still has to implement
abstract class PrimitiveJavaNullablePutJoin : com.xemantic.kotlin.introspection.test.other.JavaPrimitivePut(), NullablePrimitivePut

abstract class PrimitiveJavaGenericNullablePutJoin :
    com.xemantic.kotlin.introspection.test.other.JavaPrimitivePut(), GenericPut<Int>, NullablePrimitivePut

// the put(t: T) of GenericPut<Int?> takes Int?, which the put(int) of JavaPrimitivePut never implements
abstract class PrimitiveJavaNullableGenericPutJoin : com.xemantic.kotlin.introspection.test.other.JavaPrimitivePut(), GenericPut<Int?>

interface FunctionTaker {
    fun take(function: (String) -> Int): String
}

abstract class ProjectedFunctionJavaTakerJoin : com.xemantic.kotlin.introspection.test.other.JavaProjectedFunctionTaker(), FunctionTaker

abstract class BoxedJavaListJoin : com.xemantic.kotlin.introspection.test.other.JavaBoxedMembers(), List<String>

abstract class BoxedJavaComparableJoin : com.xemantic.kotlin.introspection.test.other.JavaBoxedMembers(), Comparable<Int>

abstract class BoxedJavaMutableCollectionJoin :
    com.xemantic.kotlin.introspection.test.other.JavaBoxedMembers(), MutableCollection<Int>

abstract class PrimitiveJavaListJoin : com.xemantic.kotlin.introspection.test.other.JavaPrimitiveGet(), List<String>

interface PrimitiveArrayPut {
    fun put(array: IntArray): String
}

interface BoxedArrayPut {
    fun put(array: Array<Int>): String
}

interface GenericArrayPut<T> {
    fun put(array: Array<T>): String
}

interface PrimitiveArrayArrayPut {
    fun put(array: Array<IntArray>): String
}

abstract class PrimitiveArrayJavaPutJoin : com.xemantic.kotlin.introspection.test.other.JavaPrimitiveArrayPut(), PrimitiveArrayPut

abstract class BoxedArrayJavaPutJoin : com.xemantic.kotlin.introspection.test.other.JavaBoxedArrayPut(), BoxedArrayPut

abstract class PrimitiveArrayJavaGenericPutJoin :
    com.xemantic.kotlin.introspection.test.other.JavaPrimitiveArrayPut(), GenericArrayPut<Int>

interface GenericBoxedArrayPutJoin : GenericArrayPut<Int>, BoxedArrayPut

interface GenericPrimitiveArrayArrayPutJoin : PrimitiveArrayArrayPut, GenericArrayPut<IntArray>

class InheritedFromJava : com.xemantic.kotlin.introspection.test.other.JavaInheritedMid<String>()

open class OpenInheritedFromJava : com.xemantic.kotlin.introspection.test.other.JavaInheritedMid<String>()

open class OpenInheritedJavaArrayList : java.util.ArrayList<String>()

// A Java method declaring a type parameter named like the type parameter of the inheriting class given for its class
class InheritedFuture<U> : java.util.concurrent.CompletableFuture<U>()

// A member declaring a type parameter named like the type parameter of the inheriting class given for its class
open class InheritedBox<E> {
    fun <T> map(transform: (E) -> T): InheritedBox<T> = TODO()
}

class InheritedRenamingBox<T> : InheritedBox<T>()

// A Kotlin member taking a nullable parameter, overridden by a Java class
interface InheritedNullableTaker {
    fun take(x: String?)
}

class InheritedFromNullableTaker : com.xemantic.kotlin.introspection.test.other.JavaNullableTaker()

// A Kotlin member implemented by a Java class, implementing the abstract method of its Java superclass too
interface InheritedGreeter {
    fun greet(): String
}

class InheritedFromJavaGreeter : com.xemantic.kotlin.introspection.test.other.JavaGreeterMid()

// A public method of a package-private Java class, bridged by its public Java subclass
class InheritedThroughPublicJavaMid : com.xemantic.kotlin.introspection.test.other.JavaPublicGreeterMid()

// Methods of a Java superclass, java.util.Dictionary, overridden by its Java subclass, mapped to members of Map
class InheritedHashtable<K : Any, V : Any> : java.util.Hashtable<K, V>()

// A Java override of reversed() of List since JDK 21, which Kotlin hides, with a more specific return type
class InheritedLinkedList<E> : java.util.LinkedList<E>()

// A Java superclass whose members kotlin-reflect cannot describe since JDK 21, failing on its inherited clone()
class InheritedLinkedHashSet<E> : java.util.LinkedHashSet<E>()

// Abstract members of kotlin.Number implemented by a Java superclass, java.util.concurrent.atomic.AtomicInteger,
// but toByte() and toShort(), mapped to the methods of java.lang.Number, which Kotlin sees as abstract
abstract class InheritedAtomicInteger : java.util.concurrent.atomic.AtomicInteger()

// Methods of a Java superclass merged with the members of the same signatures of a Kotlin interface
interface InheritedTaker {
    fun take(x: String)
    fun mixed(a: Any, b: String?)
}

class InheritedFromJavaTakeBase : com.xemantic.kotlin.introspection.test.other.JavaTakeBase(), InheritedTaker

// An abstract member of kotlin.Number implemented by a Java class, and declared abstract again by its Java subclass
abstract class InheritedReabstractedNumber : com.xemantic.kotlin.introspection.test.other.JavaReabstractedNumberMid()

// A public method of a public Java class nested in a package-private one, inherited through its public Java subclass
class InheritedThroughPublicNestedJavaMid : com.xemantic.kotlin.introspection.test.other.JavaPublicNestedGreeterMid()

// Concrete members of kotlin.Any declared abstract again by a Java superclass, java.security.Permission
abstract class InheritedPermission : java.security.Permission("permission")

// A concrete member of a Kotlin class declared abstract again by its Java subclass
open class ConcreteKotlinBase {
    open fun value(): Int = 1
}

abstract class InheritedReabstractedKotlinMember : com.xemantic.kotlin.introspection.test.other.JavaReabstractedKotlinMember()

// A nullable return type of a Kotlin member implemented by a Java method returning a platform type,
// which inherits the nullability, so that the Java method is not more specific
interface NullableReturnKotlinBase {
    fun name(): String?
}

abstract class InheritedNullableReturnImplementation :
    com.xemantic.kotlin.introspection.test.other.JavaNullableReturnImplementation()

// Members of Kotlin interfaces implemented by a Java superclass, java.util.ArrayList, inherited by an abstract class
abstract class AbstractInheritedJavaArrayList : java.util.ArrayList<String>()

// An abstract property of a Kotlin class implemented by the getter of its Java subclass
abstract class AbstractNamed {
    abstract val name: String
}

abstract class InheritedJavaNamed : com.xemantic.kotlin.introspection.test.other.JavaNamed()

// Members of Java superclasses whose return types the compiler enhances as nullable
class InheritedLinkedHashMap<K, V> : java.util.LinkedHashMap<K, V>()

class InheritedWeakReference(referent: String) : java.lang.ref.WeakReference<String>(referent)

class InheritedCovariantWeakReference(referent: String) :
    com.xemantic.kotlin.introspection.test.other.JavaStringWeakReference(referent)

// A Java interface declaring equals abstract again, which the implementation of kotlin.Any still implements
abstract class InheritedJavaComparator : java.util.Comparator<String>

// A Java interface of a mapped type named as a supertype, whose own members kotlin-reflect cannot describe,
// as it describes the read-only built-in type, ListIterator, instead, so that they are converted from the Java reflection
abstract class JavaListIteratorSubclass : java.util.ListIterator<String>

abstract class JavaMapEntrySubclass : java.util.Map.Entry<String, Int>

// Mapped Java interfaces named as supertypes, which Kotlin sees as Java ones, like java.util.Collection,
// or as their built-in types, like java.util.List, see JAVA_VIEWED_BUILTIN_INTERFACES
abstract class JavaCollectionSubclass : java.util.Collection<String>

abstract class JavaListSubclass : java.util.List<String>

// A Java superclass whose members kotlin-reflect cannot describe, failing on all of them at once,
// so that they are converted from the Java reflection
abstract class InheritedJavaUndescribable : com.xemantic.kotlin.introspection.test.other.JavaUndescribableBase<String>()

// A member of a Java superclass whose members kotlin-reflect cannot describe since JDK 21, hidden by another supertype,
// which the compiler does not pick, as the getFirst() of java.util.LinkedHashSet, of the first supertype, returns String too
interface HiddenFirst {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    fun getFirst(): String
}

abstract class InheritedLinkedHashSetHidingFirst : java.util.LinkedHashSet<String>(), HiddenFirst

// The invoke operator of a function type, and of a suspend one, named as a supertype
abstract class InheritedFunctionType : (Int) -> String

abstract class InheritedSuspendFunctionType : suspend (Int) -> String

// JDK members inherited through a Java subclass of java.util.ArrayDeque, merged as through the JDK class itself
abstract class InheritedJavaInheritingDeque : com.xemantic.kotlin.introspection.test.other.JavaInheritingDeque()

// A Java override of a JDK method whose return type the compiler enhances as nullable, annotated not nullable, which wins
abstract class InheritedNotNullWeakReference : com.xemantic.kotlin.introspection.test.other.JavaNotNullWeakReference("")

// A JDK member inherited through a Java class, returning a platform type there, merged with a nullable member of a Kotlin interface,
// more specific, whatever the order of the supertypes
interface NullableGetter {
    fun get(): String?
}

abstract class InheritedJavaSupplierNullableGetter :
    com.xemantic.kotlin.introspection.test.other.JavaInheritingSupplier<String>(), NullableGetter

abstract class InheritedNullableGetterJavaSupplier :
    NullableGetter, com.xemantic.kotlin.introspection.test.other.JavaInheritingSupplier<String>()

interface NullableComputeIfAbsent {
    fun computeIfAbsent(key: String, mappingFunction: java.util.function.Function<in String, out String>): String?
}

abstract class InheritedJavaMapNullableComputeIfAbsent :
    com.xemantic.kotlin.introspection.test.other.JavaInheritingMap<String, String>(), NullableComputeIfAbsent

abstract class JavaCharSequenceSubclass : java.lang.CharSequence

// The generic methods of a Java class reached through a raw Java supertype, which the compiler sees erased
abstract class InheritedJavaRawGeneric : com.xemantic.kotlin.introspection.test.other.JavaRawGenericMid()

// The size() of java.util.LinkedHashSet declared abstract again by a Java subclass, whose members kotlin-reflect cannot describe
abstract class InheritedReabstractedSizeSet : com.xemantic.kotlin.introspection.test.other.JavaReabstractedSizeSet()

// A type variable of a Java class annotated not nullable, given a nullable type argument
abstract class InheritedJavaNotNullTypeVariable : com.xemantic.kotlin.introspection.test.other.JavaNotNullTypeVariableBase<String?>()

// The overrides of a Java class not annotated, inheriting the nullability annotations of the methods they override
abstract class InheritedJavaAnnotationInheriting : com.xemantic.kotlin.introspection.test.other.JavaAnnotationInheriting()

// The type arguments, and the array elements, of a Java class annotated by type annotations
abstract class InheritedJavaTypeUseNullability : com.xemantic.kotlin.introspection.test.other.JavaTypeUseNullability()

// The members of Java classes marked by @NullMarked of JSpecify, by their class, by their outer class, or by their package
abstract class InheritedJavaNullMarked : com.xemantic.kotlin.introspection.test.other.JavaNullMarkedBase<String, String?>()

abstract class InheritedJavaNullUnmarked : com.xemantic.kotlin.introspection.test.other.JavaNullMarkedBase.Unmarked()

abstract class InheritedJavaNullMarkedNested : com.xemantic.kotlin.introspection.test.other.JavaNullMarkedBase.Nested()

abstract class InheritedJavaNullMarkedPackage : com.xemantic.kotlin.introspection.test.marked.JavaNullMarkedPackageBase()

// The invoke operator of a function reference type, and of a suspend one, named as a supertype
abstract class InheritedFunctionReferenceType : kotlin.reflect.KFunction1<Int, String>

abstract class InheritedSuspendFunctionReferenceType : kotlin.reflect.KSuspendFunction1<Int, String>

// A Java class marked by @NullMarked overriding an annotated method of a Java class not marked, not annotated itself
abstract class InheritedJavaNullMarkedOverriding : com.xemantic.kotlin.introspection.test.other.JavaNullMarkedOverriding()

// The members of a generic Java subclass of java.util.HashMap reached through its raw subclass
abstract class InheritedJavaRawSourceMap : com.xemantic.kotlin.introspection.test.other.JavaRawSourceMapMid()

// Java classes marked by @NullUnmarked, by their class, by their outer class, or by their package, overriding annotated methods
abstract class InheritedJavaNullUnmarkedOverriding : com.xemantic.kotlin.introspection.test.other.JavaNullUnmarkedOverriding()

abstract class InheritedJavaNullUnmarkedOuter : com.xemantic.kotlin.introspection.test.other.JavaNullUnmarkedOuter.Inner()

abstract class InheritedJavaNullUnmarkedPackage : com.xemantic.kotlin.introspection.test.unmarked.JavaNullUnmarkedPackageOverriding()

// The member of the nearest class wins over the ones of the interfaces, also in a hierarchy which is all part of the API,
// whatever the order of the supertypes: open of the abstract member a superclass implements, next to an unrelated interface,
// so that a subclass of NearestClassImplementingSub need not override f(),
// open, abstract, or final of the member a superclass overrides, merged with a redeclaration of the root interface,
// so that a subclass of NearestClassReabstractingRedeclaringSub has to override f(),
// and a subclass of NearestClassFinalRedeclaringSub cannot, unchecked by the build
interface NearestClassFace {
    fun f(): Int
}

abstract class NearestClassAbstract {
    abstract fun f(): Int
}

abstract class NearestClassImplementing : NearestClassAbstract() {
    override fun f(): Int = 1
}

abstract class NearestClassImplementingSub : NearestClassFace, NearestClassImplementing()

class NearestClassImplementingConcrete : NearestClassImplementingSub()

interface NearestClassRoot {
    fun f(): Int
}

interface NearestClassRedeclaring : NearestClassRoot {
    override fun f(): Int
}

abstract class NearestClassImplemented : NearestClassRoot {
    override fun f(): Int = 1
}

abstract class NearestClassReabstracting : NearestClassImplemented() {
    abstract override fun f(): Int
}

abstract class NearestClassFinal : NearestClassImplemented() {
    final override fun f(): Int = 2
}

abstract class NearestClassImplementedRedeclaringSub : NearestClassRedeclaring, NearestClassImplemented()

class NearestClassImplementedRedeclaringConcrete : NearestClassImplementedRedeclaringSub()

abstract class NearestClassReabstractingRedeclaringSub : NearestClassRedeclaring, NearestClassReabstracting()

abstract class NearestClassFinalRedeclaringSub : NearestClassRedeclaring, NearestClassFinal()

// and the method of a Java class implementing the abstract member of an internal class, next to an unrelated interface,
// so that a subclass of JavaImplementingInternalSub need not override f()
abstract class JavaImplementingInternalSub : com.xemantic.kotlin.introspection.test.other.JavaImplementingInternal(), NearestClassFace

class JavaImplementingInternalConcrete : JavaImplementingInternalSub()

abstract class JavaImplementingInternalFaceFirstSub : NearestClassFace, com.xemantic.kotlin.introspection.test.other.JavaImplementingInternal()

// and the method of a Java class implementing the member of an internal interface, alone, or next to an unrelated interface,
// so that a subclass of JavaImplementingInternalInterfaceSub, or of JavaImplementingInternalInterfaceFaceSub, need not override f()
internal interface NearestClassInternalFace {
    fun f(): Int
}

abstract class JavaImplementingInternalInterfaceSub : com.xemantic.kotlin.introspection.test.other.JavaImplementingInternalInterface()

class JavaImplementingInternalInterfaceConcrete : JavaImplementingInternalInterfaceSub()

abstract class JavaImplementingInternalInterfaceFaceSub :
    NearestClassFace, com.xemantic.kotlin.introspection.test.other.JavaImplementingInternalInterface()

class JavaImplementingInternalInterfaceFaceConcrete : JavaImplementingInternalInterfaceFaceSub()

// A Kotlin class extending java.util.AbstractList and implementing MutableList, whose members K2 merges,
// picking the Java method of AbstractList, first, when not returning a platform type
abstract class InheritedJavaListDiamond : java.util.AbstractList<String>(), MutableList<String>

// The same diamond of MutableList declared first, whose members K2 picks then
abstract class InheritedKotlinFirstListDiamond : MutableList<String>, java.util.AbstractList<String>()

// The members of java.util.Hashtable, which is no pure implementation of MutableMap, taking platform types
abstract class InheritedStringHashtable : java.util.Hashtable<String, String>()

// java.util.Hashtable merged with MutableMap, whose computeIfAbsent K2 picks, as the one of Hashtable returns V!
abstract class InheritedStringHashtableMapDiamond : java.util.Hashtable<String, String>(), MutableMap<String, String>

// The methods of java.util.ArrayList reached through a Java subclass passing its type variable, taking platform types
abstract class InheritedJavaComparableArrayList : com.xemantic.kotlin.introspection.test.other.JavaComparableArrayList<String>()

// A Java subclass of java.util.LinkedHashSet, whose own add kotlin-reflect cannot describe
abstract class InheritedJavaLinkedHashSetSubclass :
    com.xemantic.kotlin.introspection.test.other.JavaLinkedHashSetSubclass<String>()

// java.util.function.Function reached first through a Java interface giving it platform type arguments, and then directly,
// whose apply K2 takes of the second one, not returning a platform type
abstract class InheritedJavaFunctionDiamond :
    com.xemantic.kotlin.introspection.test.other.JavaSameTypeFunction<String>, java.util.function.Function<String, String>

abstract class InheritedJavaBiFunctionDiamond :
    com.xemantic.kotlin.introspection.test.other.JavaSameTypeBiFunction<String>, java.util.function.BiFunction<String, String, String>

// java.util.function.Consumer reached alike, whose accept returns no platform type, so that K2 takes the first one
abstract class InheritedJavaConsumerDiamond :
    com.xemantic.kotlin.introspection.test.other.JavaTypeConsumer<String>, java.util.function.Consumer<String>

// The bounds of the type parameters of the methods of Java classes, marked by @NullMarked, or annotated not nullable
abstract class InheritedJavaNullMarkedBounds : com.xemantic.kotlin.introspection.test.other.JavaNullMarkedBounds()

abstract class InheritedJavaNotNullBounds : com.xemantic.kotlin.introspection.test.other.JavaNotNullBounds()

// The overrides of a Java class not marked, inheriting the types of a Java class marked by @NullMarked, also through another one
abstract class InheritedJavaNullMarkedBaseOverriding : com.xemantic.kotlin.introspection.test.other.JavaNullMarkedBaseOverriding()

abstract class InheritedJavaNullMarkedBaseOverridingDown :
    com.xemantic.kotlin.introspection.test.other.JavaNullMarkedBaseOverridingDown()

// The overrides of a Java class marked by @NullMarked, inheriting the type variable annotated not nullable, given a nullable type argument
abstract class InheritedJavaNullMarkedTypeVariableOverriding :
    com.xemantic.kotlin.introspection.test.other.JavaNullMarkedTypeVariableOverriding<String?>()

// The overrides of Java classes annotated otherwise than the methods they override, of platform types then
abstract class InheritedJavaConflictingOverriding : com.xemantic.kotlin.introspection.test.other.JavaConflictingOverriding()

abstract class InheritedJavaNullMarkedConflictingOverriding :
    com.xemantic.kotlin.introspection.test.other.JavaNullMarkedConflictingOverriding()

// The override of a Java class not annotated, of the methods of its supertypes annotated otherwise
abstract class InheritedJavaDisagreeingOverriding : com.xemantic.kotlin.introspection.test.other.JavaDisagreeingOverriding()

// The get() of a Java superclass, merged with the one of a Java interface annotated nullable through the bridge of a Java class
abstract class InheritedJavaBridgedGet : com.xemantic.kotlin.introspection.test.other.JavaBridgedGet()

// A Kotlin class extending a generic Java class, reached raw through a Java subclass, see JavaRawThroughKotlinMid
abstract class KotlinRawThroughBase<T> : com.xemantic.kotlin.introspection.test.other.JavaRawThroughKotlinBase<T>()

abstract class InheritedJavaRawThroughKotlin : com.xemantic.kotlin.introspection.test.other.JavaRawThroughKotlinMid()

// The size() of a Java interface, which the size of Set, implemented by java.util.HashSet, does not implement
abstract class InheritedJavaSizeFaceSet : java.util.HashSet<String>(), com.xemantic.kotlin.introspection.test.other.JavaSizeFace

// The vararg contains, and compareTo, of a Java subclass of java.util.LinkedHashSet, whose members kotlin-reflect cannot describe
abstract class InheritedJavaVarargContainsSet : com.xemantic.kotlin.introspection.test.other.JavaVarargContainsSet()

// The overrides of type variables given by Java classes not marked, of a type variable of a Java class marked by @NullMarked,
// passed through by a generic subclass, or given a type argument annotated nullable,
// and of a Java class marked by @NullUnmarked in between, which inherit no mark
abstract class InheritedJavaGenericPassing : com.xemantic.kotlin.introspection.test.other.JavaGenericPassing<String?>()

abstract class InheritedJavaGenericPassingBottom : com.xemantic.kotlin.introspection.test.other.JavaGenericPassingBottom()

abstract class InheritedJavaNullableArgumentPassing : com.xemantic.kotlin.introspection.test.other.JavaNullableArgumentPassing()

abstract class InheritedJavaNullUnmarkedMidBottom : com.xemantic.kotlin.introspection.test.other.JavaNullUnmarkedMidBottom()

// The methods marked by @NullMarked of a Java class not marked, and their override
abstract class InheritedJavaMethodNullMarked : com.xemantic.kotlin.introspection.test.other.JavaMethodNullMarked<String>()

abstract class InheritedJavaMethodNullMarkedOverriding : com.xemantic.kotlin.introspection.test.other.JavaMethodNullMarkedOverriding()

// A Java subclass of java.util.HashSet implementing JavaSizeFace, whose size() implements the one of the interface
abstract class InheritedJavaSizeFaceHashSet : com.xemantic.kotlin.introspection.test.other.JavaSizeFaceHashSet()

// The overrides of the type variable of a Java class marked by @NullMarked, given through the type variables
// of Java classes not marked, which inherit platform types, and given directly, which inherits the mark
abstract class InheritedJavaPassingMidOverriding : com.xemantic.kotlin.introspection.test.other.JavaPassingMidOverriding()

abstract class InheritedJavaPassingMid2Overriding : com.xemantic.kotlin.introspection.test.other.JavaPassingMid2Overriding()

abstract class InheritedJavaPassingDirect : com.xemantic.kotlin.introspection.test.other.JavaPassingDirect()
