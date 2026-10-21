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

import kotlin.reflect.KClass

// Data class
data class DataTestClass(
    val id: Int,
    val name: String
)

// Value class (inline class)
@JvmInline
value class ValueTestClass(val value: String)

// Object (singleton)
object ObjectTestClass {
    val name: String = "singleton"
    fun greet(): String = "Hello from $name"
}

// Simple enum class
enum class EnumTestClass {
    FIRST,
    SECOND,
    THIRD
}

// Rich enum class with constructor, properties and functions
enum class RichEnumTestClass(val code: Int, val label: String) {
    ALPHA(1, "Alpha"),
    BETA(2, "Beta"),
    GAMMA(3, "Gamma");

    val displayName: String get() = "$label ($code)"

    fun isFirst(): Boolean = this == ALPHA
}

// Enum class whose only member after the entries is a companion object
enum class CompanionOnlyEnum {
    RED,
    GREEN;

    companion object {
        fun default(): CompanionOnlyEnum = RED
    }
}

// Annotation class
annotation class AnnotationTestClass(
    val message: String = ""
)

// Annotation class with default values of various types
annotation class AnnotationDefaultsTestClass(
    val times: Int = 3,
    val label: String = "say \"hi\"",
    val enabled: Boolean = true,
    val ratio: Double = 0.5,
    val big: Long = 10L,
    val letter: Char = 'k',
    val level: DeprecationLevel = DeprecationLevel.ERROR,
    val type: KClass<*> = String::class,
    val tags: Array<String> = ["a", "b"],
    val required: String
)

// Class with companion object
class CompanionTestClass {
    companion object {
        const val CONSTANT: String = "const"
        private const val PRIVATE_CONSTANT: String = "private"
        fun create(): CompanionTestClass = CompanionTestClass()
    }
}

// Nested class with companion object
class NestedCompanionTestClass {
    class Inner {
        companion object {
            fun create(): Inner = Inner()
        }
    }
}

// Class with enough members for the companion object to straddle a page boundary
class PaginatedCompanionClass {
    val a1: Int = 1
    val a2: Int = 2
    val a3: Int = 3
    val a4: Int = 4
    val a5: Int = 5
    fun f6() {}
    fun f7() {}
    fun f8() {}
    fun f9() {}
    companion object {
        const val CONSTANT: String = "const"
        fun create(): PaginatedCompanionClass = PaginatedCompanionClass()
    }
}

// Class with nested classes of all kinds, including the hidden and synthetic ones,
// not named *TestClass, so that it does not shift the search results of the other tests
class NestedClassesHolder {
    class NestedClass
    inner class NestedInnerClass
    interface NestedInterface
    object NestedObject
    enum class NestedEnum { A, B }
    data class NestedData(val value: String) : NestedInterface
    protected class NestedProtectedClass
    private class NestedPrivateClass
    internal class NestedInternalClass
    // named like the synthetic nested classes, e.g. the $serializer of kotlinx.serialization
    class `Synthetic$Nested`
    companion object {
        fun create(): NestedClassesHolder = NestedClassesHolder()
    }
    // compiled into an anonymous NestedClassesHolder$anonymous$1 class
    fun anonymous(): Any = object {}
    // compiled with the synthetic NestedClassesHolder$WhenMappings class
    fun whenMapping(nestedEnum: NestedEnum): Int = when (nestedEnum) {
        NestedEnum.A -> 1
        NestedEnum.B -> 2
    }
}
