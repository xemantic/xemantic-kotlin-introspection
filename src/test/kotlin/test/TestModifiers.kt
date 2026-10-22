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

// Test class with various visibility modifiers
class VisibilityTestClass {
    val publicProperty: String = "public"  // default public
    private val privateProperty: String = "private"
    protected val protectedProperty: String = "protected"
    internal val internalProperty: String = "internal"

    fun publicFunction(): String = "public"  // default public
    private fun privateFunction(): String = "private"
    protected fun protectedFunction(): String = "protected"
    internal fun internalFunction(): String = "internal"
}

// Test open class with modality modifiers
open class OpenTestClass {
    open val openProperty: String = "open"
    open fun openFunction(): String = "open"
}

// Test sealed class
sealed class SealedTestClass {
    class SubClass1(val name: String) : SealedTestClass()
    class SubClass2(val count: Int) : SealedTestClass()
    data class SubData(val id: Int) : SealedTestClass()
    object SubObject : SealedTestClass()
    data object SubDataObject : SealedTestClass()
}

// Test abstract class
abstract class AbstractTestClass {
    abstract val abstractProperty: String
    abstract fun abstractFunction(): String

    open val openProperty: String = "open"
    open fun openFunction(): String = "open"
}

// Test class implementing interface with override
class OverrideTestClass : Comparable<OverrideTestClass> {
    override fun compareTo(other: OverrideTestClass): Int = 0
}

// The sealed subclasses with their own type parameters and other supertypes than the sealed class
sealed class SealedSupertypesHolder {
    class Circle : SealedSupertypesHolder(), Comparable<Circle> {
        override fun compareTo(other: Circle): Int = 0
    }
    class Box<T : CharSequence>(val content: T) : SealedSupertypesHolder(), Iterable<T> {
        override fun iterator(): Iterator<T> = listOf(content).iterator()
    }
    data object Empty : SealedSupertypesHolder()
}

// The inner sealed subclasses declared in other generic classes refer to the type parameters of their outer classes
sealed class GenericSealedHolder<A>

class GenericSealedOuter<T> {
    inner class Sub : GenericSealedHolder<T>()
}

sealed interface InnerSealedInterface

class InnerSealedInterfaceHolder<X> {
    inner class Sub : InnerSealedInterface, Comparable<X> {
        override fun compareTo(other: X): Int = 0
    }
}

// The companion object of another class can be a sealed subclass, unlike the own one, already rendered
sealed class CompanionSealedHolder {
    companion object : CompanionSealedHolder()
    class Sub : CompanionSealedHolder()
}

class CompanionSealedSubclassHolder {
    companion object : CompanionSealedHolder()
}
