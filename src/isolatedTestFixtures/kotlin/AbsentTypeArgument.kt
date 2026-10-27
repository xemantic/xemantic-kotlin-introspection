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

package com.xemantic.kotlin.introspection.isolated

// Tests leave the class file of Absent out of the class loader,
// so that the members and the generic supertypes referring to it cannot be reflected,
// and the classes extending it cannot be loaded at all
open class Absent

class AbsentTypeArgumentComparable : Comparable<Absent> {
    override fun compareTo(other: Absent): Int = 0
}

// only the generic supertype refers to Absent, not the members
abstract class AbsentTypeArgumentHolder : Comparable<Absent> {
    fun hold() {}
}

interface AbsentArrayProcessor<T, A> {
    fun process(items: Array<T>)
    fun absent(item: A)
}

abstract class StringAbsentArrayProcessor : AbsentArrayProcessor<String, Absent> {
    override fun process(items: Array<String>) {}
    fun process(items: Array<Absent>) {}
}

class DollarHolder {
    class `Dollar$Nested`
    class `$Named`
}

// the Java superclass declares a method taking Absent, and one taking a class nested in DollarHolder
class AbsentDollarJavaSubclass : AbsentDollarJavaBase() {
    override fun take(named: DollarHolder.`$Named`) {}
}

// the Java superclass declares a method taking Absent
class AbsentJavaSubclass : AbsentJavaBase<String>() {
    override fun process(item: String) {}
    fun process(item: Int) {}
    override fun run() {}
    fun run(times: Int) {}
    fun prim(x: Int?) {}
    override fun <S : String> bounded(x: S) {}
    fun <S : Number> bounded(x: S) {}
    override fun take(nested: DollarHolder.`Dollar$Nested`) {}
}

// a type parameter bounded by Int? does not override the Java method taking int
class AbsentJavaTypeParameterSubclass<P : Int?> : AbsentJavaBase<String>() {
    fun prim(x: P) {}
}

// the Java superclass refers to Absent only in the generic types of its methods
class AbsentBoundJavaSubclass : AbsentBoundJavaBase() {
    override fun run() {}
    override fun <T : Comparable<Absent>> compare(item: T) {}
    fun <T : CharSequence> compare(item: T) {}
    fun compare(item: Int) {}
}

// the Java superclass takes arrays of its type variable, and declares a method taking Absent
class AbsentJavaArraySubclass : AbsentJavaBase<String>() {
    override fun process(items: Array<String>) {}
    fun process(items: Array<Int>) {}
    override fun <S : String> boundedArray(items: Array<S>) {}
    fun <S : Number> boundedArray(items: Array<S>) {}
    override fun nested(items: Array<Array<String>>) {}
    fun nested(items: Array<Array<Int>>) {}
    override fun lists(items: Array<List<String>>) {}
    fun lists(items: Array<Set<String>>) {}
}

// the type variables of the Java superclass are bound per declaring class, also when shadowed,
// while each of its classes declares a method taking Absent
class AbsentJavaOuterSubclass : AbsentJavaOuter<String>() {
    override fun <T : Number> shadowing(x: T) {}
    fun <T : String> shadowing(x: T) {}

    inner class InnerSubclass : AbsentJavaOuter<String>.Inner<Int>() {
        override fun outer(x: String) {}
        fun outer(x: Int) {}
        override fun inner(x: Int) {}
        fun inner(x: String) {}
    }

    inner class ShadowingSubclass : AbsentJavaOuter<String>.Shadowing<Int>() {
        override fun shadowed(x: Int) {}
        fun shadowed(x: String) {}
    }
}

// the type variable of the raw Java superclass, declaring a method taking Absent, is erased to its bound
class AbsentRawJavaSubclass : AbsentRawJavaMid() {
    override fun process(items: Array<Any?>) {}
    fun process(items: Array<Int>) {}
    fun <S : Number> bounded(x: S) {}
}

// the type parameter is unknown through the Java superclass, whose generic superclass refers to Absent,
// so that both overloads of f match the function taking String, but it inherits no default value from the generic one,
// as the other one matches on the known types, also on a star projection, like the overloads of h;
// the overload of g is matched only through the unknown type, and by the Java override of the superclass,
// which declares no default value, but is matched only by the erasures, so the default value is inherited
open class AbsentArgumentDefaultsBase<T> {
    open fun f(x: T, y: Int = 0) {}
    open fun f(x: String, y: Int) {}
    open fun g(x: T, y: Int = 0) {}
    open fun h(x: T, y: List<*>, z: Int = 0) {}
    open fun h(x: String, y: List<*>, z: Int) {}
}

class AbsentArgumentDefaultsLeaf : AbsentArgumentJavaMid() {
    override fun f(x: String, y: Int) {}
    override fun g(x: Absent, y: Int) {}
    override fun h(x: String, y: List<*>, z: Int) {}
}

// the nested classes extending Absent cannot be loaded, only their class files can be read
class AbsentNestedHolder {
    class Present
    class AbsentSubclass : Absent()
    private class HiddenAbsentSubclass : Absent()
    // tests leave this class file out as well
    class Gone
}

sealed interface AbsentSealed {
    class Present : AbsentSealed
    class AbsentSubclass : Absent(), AbsentSealed
    // tests leave this class file out as well
    class Gone : AbsentSealed
    // listed after the sealed subclasses, also after the ones which cannot be found
    class NotASubclass
}

// the overrides are inherited through a class which cannot be loaded, neither can this one
open class AbsentMid : Absent() {
    open fun foo() {}
}

class AbsentLeaf : AbsentMid() {
    override fun foo() {}
    fun bar() {}
}

// the outer class cannot be loaded, only its class file can be read
open class AbsentOuter : Absent() {
    class Nested {
        fun f() {}
    }
}

// tests strip the metadata of the outer class, which can still be private
private class StrippedOuter {
    class Nested
}

// tests strip the metadata of a subclass
sealed interface StrippedSealed {
    class Kept : StrippedSealed
    class Stripped : StrippedSealed
}

// tests leave out the class file of a subclass named like a hard keyword, or strip its metadata
sealed interface KeywordSubclassSealed {
    class Kept : KeywordSubclassSealed
    class `in` : KeywordSubclassSealed
}

// tests leave the class Absent out, and fail reading this class file, so that its members cannot be reflected
open class AbsentParameterHidden @Deprecated("replaced", level = DeprecationLevel.HIDDEN) constructor(absent: Absent) {
    protected fun forSubclasses() {}
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    fun hidden(absent: Absent) {}
    fun visible() {}
}

// tests leave the class file of the outer class out
class MissingOuter {
    class Nested {
        fun f() {}
    }
}

// tests leave the class file of the generic outer class out, so that the names of its type parameters are not known
class MissingGenericOuter<T> {
    inner class Inner : Comparable<T> {
        override fun compareTo(other: T): Int = 0
        // declaring its own type parameter, which is not one of the outer class
        fun <R> map(f: (T) -> R): R = f(TODO())
    }
}

// tests leave the class file of the generic outer class out, so that the bounds of its type parameters are not known,
// while the inner class inherits a member taking its type parameter as definitely not nullable
class MissingNotNullOuter<T : Any> {
    inner class Inner : DefinitelyNonNullHolder<T>()
}

open class DefinitelyNonNullHolder<T> {
    fun orDefault(value: T, default: T & Any): T & Any = value ?: default
}

// tests leave the class file of the generic outer class out, while the inner class declares the names given to its type parameters
class ClashingGenericOuter<T> {
    inner class Inner<T0> : Comparable<T> {
        override fun compareTo(other: T): Int = 0
        fun <T0_> pair(a: T0, b: T0_): Pair<T, T0_> = TODO()
        inner class Deep : Comparable<T> {
            override fun compareTo(other: T): Int = 0
        }
    }
}

// tests leave the class file of the middle class out, while the outermost one is there
class MissingMidHolder<A> {
    inner class Mid<B> {
        inner class Leaf : Comparable<B> {
            override fun compareTo(other: B): Int = 0
            fun pair(): Pair<A, B> = TODO()
        }
    }
}

// tests leave the class file of the middle class out, which is nested, not inner, in the generic outermost one
class NestedMidHolder<X> {
    class Mid<T> {
        inner class Leaf : Comparable<T> {
            override fun compareTo(other: T): Int = 0
        }
    }
}

// the Java superclass cannot be loaded, so that its members and its generic supertypes are read from its class file
abstract class AbsentGenericJavaLeaf : AbsentGenericJavaMid<String>() {
    override fun process(item: String) {}
    fun process(item: Int) {}
    override fun run() {}
    fun finish(code: Int) {}
    override fun compareTo(other: String): Int = 0
    fun compareTo(other: Int): Int = 0
}

// the type variable of the raw Java superclass, read from its class file, is erased to its bound
abstract class AbsentRawGenericJavaLeaf : AbsentRawGenericJavaMid() {
    override fun process(item: Any?) {}
    fun process(item: String) {}
    override fun compareTo(other: Any?): Int = 0
}
