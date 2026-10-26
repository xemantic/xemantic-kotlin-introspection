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
