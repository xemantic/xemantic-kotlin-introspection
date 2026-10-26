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

package com.xemantic.kotlin.introspection.test.other

/**
 * A custom type from a different package, used to test cross-package type references.
 */
class CustomProcessor {
    fun process(input: String): String = input.uppercase()
}

/**
 * Another custom type for testing multiple cross-package references.
 */
data class CustomResult(
    val value: String,
    val success: Boolean
)
/**
 * An extension on Comparable of another package than the one of a comparable class,
 * not listed for the class, like the generic extensions on the other types of the standard library.
 */
fun <T : Comparable<T>> T.isAtLeast(other: T): Boolean = this >= other
