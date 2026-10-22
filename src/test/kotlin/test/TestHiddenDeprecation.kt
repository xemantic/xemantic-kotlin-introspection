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

// Declarations deprecated with the HIDDEN level, which cannot be called from the source code,
// like the overloads kept only for the binary compatibility, next to their replacements
class HiddenDeprecationTarget(val value: Int) {

    fun member(): Int = value

    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    fun member(factor: Int): Int = value * factor

    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    fun hiddenMember(): Int = value

    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    val hiddenProperty: Int get() = value

    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    var hiddenVariable: Int = 0

    // deprecated with a level other than HIDDEN, which can still be called
    @Deprecated("replaced", level = DeprecationLevel.ERROR)
    fun errorMember(): Int = value

    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    constructor(name: String) : this(name.length)

    constructor(value: Long) : this(value.toInt())

    companion object {
        fun create(): HiddenDeprecationTarget = HiddenDeprecationTarget(0)
        @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
        fun create(value: Int): HiddenDeprecationTarget = HiddenDeprecationTarget(value)
        @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
        const val HIDDEN_CONSTANT: Int = 42
        @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
        @JvmField
        val hiddenField: Int = 42
    }

}

// the only constructor which can be called is hidden
class HiddenOnlyConstructor private constructor(val a: Int) {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    constructor(s: String) : this(s.length)
}

fun HiddenDeprecationTarget.extension(): Int = value

@Deprecated("replaced", level = DeprecationLevel.HIDDEN)
fun HiddenDeprecationTarget.extension(factor: Int): Int = value * factor

@Deprecated("replaced", level = DeprecationLevel.HIDDEN)
val HiddenDeprecationTarget.hiddenExtensionProperty: Int get() = value

@Deprecated("replaced", level = DeprecationLevel.HIDDEN)
fun hiddenDeprecationTopLevel(): Int = 42

fun hiddenDeprecationTopLevel(value: Int): Int = value

object HiddenDeprecationExtensions {
    fun HiddenDeprecationTarget.objectExtension(): Int = value
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    fun HiddenDeprecationTarget.objectExtension(factor: Int): Int = value * factor
}
