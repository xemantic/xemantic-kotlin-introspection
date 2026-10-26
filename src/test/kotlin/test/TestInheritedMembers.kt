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
