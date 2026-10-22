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
}

// the overrides are inherited through a class which cannot be loaded, neither can this one
open class AbsentMid : Absent() {
    open fun foo() {}
}

class AbsentLeaf : AbsentMid() {
    override fun foo() {}
    fun bar() {}
}
