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

package com.xemantic.kotlin.introspection.defaultimpls

// Compiled with -jvm-default=disable, so that the bodies of the interface members are relocated
// to the static methods of DefaultImplsGreeter$DefaultImpls,
// and the private members are absent from the class file of the interface
interface DefaultImplsBase {
    val label: String
    fun greet(name: String): String
}

interface DefaultImplsGreeter : DefaultImplsBase {
    override val label: String get() = greet(1)
    override fun greet(name: String): String = greet(1)
    private fun greet(times: Int): String = "hello".repeat(times)
}

// an overload of a supertype member, whose JVM method a test removes from the class file of the interface, like R8 might
interface DefaultImplsOverloader : DefaultImplsBase {
    fun greet(times: Int): String = "hello".repeat(times)
}

// the synthetic methods for the annotations of the interface properties are in its DefaultImpls class
interface DefaultImplsHiddenProperties {
    val visible: Int
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    val hiddenAbstract: Int
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    val hiddenWithGetter: Int get() = 1
}
