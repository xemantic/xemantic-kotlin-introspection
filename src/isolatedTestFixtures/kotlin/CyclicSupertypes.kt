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

// tests rewrite the metadata of the interfaces, so that each one is a supertype of the other,
// which the compiler would reject, while the JVM does not read the metadata
interface CyclicFirst {
    fun first()
}

interface CyclicSecond {
    fun second()
}

abstract class CyclicLeaf : CyclicFirst

interface CyclicOverloadBase {
    fun f(x: Int)
}

interface CyclicOverloadMid : CyclicOverloadBase {
    fun f(x: String)
}

abstract class CyclicOverloadLeaf : CyclicOverloadMid

// a test rewrites the metadata of the second one, making it a subtype of `CyclicGenericFirst<List<T>>`,
// so that the type arguments grow on each lap of the cycle
interface CyclicGenericSecond<T> {
    fun g(x: T)
}

interface CyclicGenericFirst<T> : CyclicGenericSecond<List<T>>

abstract class CyclicGenericFunctionLeaf :
    CyclicFunctionImpl<String>(), java.util.function.Function<String, String>, CyclicGenericFirst<String>
