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

// Class with overloaded member functions
class OverloadTestClass {
    fun overloaded(): String = "none"
    fun overloaded(x: Int): String = "int $x"
}

// Overloaded top-level functions
fun topLevelOverloaded(): Int = 0
fun topLevelOverloaded(x: Int): Int = x

// Class with a property and a function of the same name,
// not named *TestClass, so that it does not shift the search results of the other tests
class SameNamedPropertyAndFunction {
    fun size(x: Int): Int = x
    val size: Int = 0
}

// Class whose member lookup spans several pages: a property and functions of the same name,
// overloads in the companion object, and extensions from two packages,
// not named *TestClass, so that it does not shift the search results of the other tests
class PagedMemberHolder {
    val paged: Int = 0
    fun paged(x: Int): Int = x
    fun paged(x: String): String = x
    companion object {
        fun paged(): PagedMemberHolder = PagedMemberHolder()
        fun paged(x: Long): PagedMemberHolder = PagedMemberHolder()
    }
}

fun PagedMemberHolder.paged(x: Double): Double = x
