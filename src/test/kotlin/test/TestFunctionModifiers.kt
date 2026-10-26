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
    fun launchAll(blocks: List<suspend () -> Unit>) {}
    fun launchWithNullableReceiver(block: suspend (suspend () -> Unit)?.() -> Unit) {}
}

// Class with suspend function types having context parameters
class SuspendContextFunctionTypesTestClass {
    fun launchInContext(block: suspend context(FunctionModifiersTestClass) () -> Unit) {}
    fun launchInContexts(block: suspend context(FunctionModifiersTestClass, String) (Int) -> Boolean) {}
    fun launchInContextWithReceiver(block: suspend context(FunctionModifiersTestClass) String.(Int) -> Unit) {}
    fun launchInContextNullable(block: (suspend context(FunctionModifiersTestClass) () -> Unit)?) {}
}

// Extensions on a suspend function type
fun (suspend () -> Unit).retry() {}

val (suspend () -> Unit).retryCount: Int get() = 0

// Class with function types, rendered in arrow notation
class FunctionTypesSample {
    val handler: (String) -> Unit = {}
    fun launchBoth(block: () -> Unit, suspendBlock: suspend () -> Unit) {}
    fun launchWithReceiver(block: FunctionModifiersTestClass.() -> Unit) {}
    fun launchInContext(block: context(String) () -> Unit) {}
    fun launchInContextWithReceiver(block: context(String) Int.(Long) -> Boolean) {}
    fun launchNullable(block: (() -> Unit)?) {}
    fun launchHigherOrder(block: suspend (() -> Unit) -> Unit) {}
    fun launchWithFunctionReceiver(block: (() -> Unit).() -> Unit) {}
    fun curried(): (Int) -> (Int) -> Int = { a -> { b -> a + b } }
    fun reference(function: kotlin.reflect.KFunction1<Int, String>) {}
    fun referenceWithoutParameters(function: kotlin.reflect.KFunction0<String>) {}
    fun suspendReference(function: kotlin.reflect.KSuspendFunction1<Int, String>) {}
}

// Class with function types above the arity of kotlin.Function22, run as kotlin.jvm.functions.FunctionN
class LargeArityFunctionTypesSample {
    fun function(
        block: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit
    ) {}
    fun suspendFunction(
        block: suspend (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit
    ) {}
}

// Class with function types declaring parameter names
class NamedFunctionTypeParametersSample {
    fun launch(block: suspend (id: Int, name: String) -> Unit) {}
    fun filter(predicate: (index: Int, Char) -> Boolean) {}
    fun launchWithReceiver(block: String.(index: Int) -> Unit) {}
}

// Extensions on function types
fun (() -> Unit).repeat() {}

val (() -> Unit).repeatCount: Int get() = 0

fun (context(String) () -> Unit).runInContext() {}
