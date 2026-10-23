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

@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.xemantic.kotlin.introspection.test

// Declarations deprecated with the WARNING and ERROR levels, which can still be referred to from the source code

@Deprecated("Use DeprecationTarget instead", ReplaceWith("DeprecationTarget"))
class DeprecatedClass {
    @Deprecated("Nested in deprecated")
    class Nested
}

class DeprecationTarget @Deprecated("Use create()") constructor(val value: Int) {

    @Deprecated("Use the Int constructor", level = DeprecationLevel.ERROR)
    constructor(value: String) : this(value.length)

    @Deprecated("No \"replacement\" for \$value")
    val deprecatedProperty: Int get() = value

    @get:Deprecated("Getter")
    val deprecatedGetter: Int get() = value

    @Deprecated("Use newMember()", ReplaceWith("newMember()"))
    fun warningMember(): Int = value

    @Deprecated("Use newMember()", ReplaceWith("newMember()"), level = DeprecationLevel.ERROR)
    fun errorMember(): Int = value

    fun newMember(): Int = value

    companion object {
        @Deprecated("Use the constructor")
        fun create(): DeprecationTarget = DeprecationTarget(0)
    }

}

// only the first line of a message is rendered
class MultilineDeprecationTarget {
    @Deprecated("First line.\nSecond line.")
    fun f() {}
    @Deprecated("\nUse f() instead.")
    fun g() {}
}

@Deprecated("Use newMember()", ReplaceWith("newMember()"))
fun DeprecationTarget.deprecatedExtension(): Int = value

@Deprecated("Gone", level = DeprecationLevel.ERROR)
val DeprecationTarget.deprecatedExtensionProperty: Int get() = value

@Deprecated("Use deprecationTopLevel(value)", ReplaceWith("deprecationTopLevel(0)"))
fun deprecationTopLevel(): Int = 42

fun deprecationTopLevel(value: Int): Int = value

interface DeprecationInterface {
    @Deprecated("Abstract")
    val abstractProperty: Int
    @Deprecated("With getter")
    val propertyWithGetter: Int get() = 1
}

// a hidden getter hides the property, whatever the deprecation of the property itself
class HiddenGetterHolder {
    @Deprecated("Old")
    val hiddenGetter: Int
        @Deprecated("Replaced", level = DeprecationLevel.HIDDEN) get() = 1
    val visible: Int = 0
}

// a hidden companion object cannot be referred to, neither its members
class HiddenCompanionHolder {
    fun f() {}
    @Deprecated("Replaced", level = DeprecationLevel.HIDDEN)
    companion object {
        fun create(): HiddenCompanionHolder = HiddenCompanionHolder()
    }
}

// a deprecated companion object, declared with each of its members found by a member lookup on the outer class
class DeprecatedCompanionHolder {
    @Deprecated("Use the constructor")
    companion object {
        fun create(): DeprecatedCompanionHolder = DeprecatedCompanionHolder()
    }
}

// the constructor of an enum class is always private, called only by its entries
enum class DeprecatedConstructorEnum @Deprecated("Old") constructor(val v: Int) {
    A(1)
}

class DeprecatedConstructorProperty(@Deprecated("Use baz", level = DeprecationLevel.ERROR) val bar: Int) {
    val baz: Int get() = bar
}

// a data class, or a value class, cannot be declared without a property for each parameter, also a hidden one
data class HiddenPropertyDataClass(@property:Deprecated("Replaced", level = DeprecationLevel.HIDDEN) val a: Int, val b: Int)

@JvmInline
value class HiddenPropertyValueClass(@property:Deprecated("Replaced", level = DeprecationLevel.HIDDEN) val raw: Int)
