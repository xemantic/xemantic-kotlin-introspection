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

// Class with various function modifiers
class FunctionModifiersTestClass {
    // Suspend function
    suspend fun fetchData(): String = "data"

    // Inline function
    inline fun processData(block: () -> Unit) {
        block()
    }

    // Infix function
    infix fun combine(other: String): String = "combined"

    // Operator function
    operator fun plus(other: FunctionModifiersTestClass): FunctionModifiersTestClass = this

    // Tailrec function
    tailrec fun factorial(n: Int, acc: Int = 1): Int =
        if (n <= 1) acc else factorial(n - 1, n * acc)
}

// Class with property modifiers
class PropertyModifiersTestClass {
    // Const (only in companion object or top-level)
    lateinit var lateInitProperty: String

    // Lazy property (delegated)
    val lazyProperty: String by lazy { "lazy" }
}

// Top-level const val
const val TOP_LEVEL_CONST: String = "constant"

// Top-level suspend function
suspend fun topLevelSuspend(): Unit = Unit

// Top-level inline function
inline fun topLevelInline(block: () -> Unit) {
    block()
}

// Class with suspend function types
class SuspendFunctionTypesTestClass {
    val handler: suspend (String) -> Unit = {}
    fun launch(block: suspend () -> String) {}
    fun launchWithParameters(block: suspend (Int, String) -> Boolean) {}
    fun launchWithReceiver(block: suspend FunctionModifiersTestClass.() -> Unit) {}
    fun launchNullable(block: (suspend () -> Unit)?) {}
    fun supplier(): suspend () -> List<String> = { emptyList() }
}
