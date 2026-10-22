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

// constructor and function value parameters which are vararg or declare a default value
class ValueParametersSample(vararg val names: String, val count: Int = 0, label: String = "") {
    fun join(vararg parts: Int, separator: String = ", "): String = parts.joinToString(separator)
}

fun <T> valueParametersSample(vararg elements: T, limit: Int = Int.MAX_VALUE): List<T> = elements.take(limit)

// members rendered with kotlin-reflect, like the ones of the built-in types,
// with parameter names starting with "arg", which are nevertheless real names
class ReflectedValueParametersSample {
    fun format(vararg args: Any?): String = args.joinToString()
    fun call(argument: String): String = argument
    fun sum(vararg ints: Int): Int = ints.sum()
    fun sumUnsigned(vararg uints: UInt): UInt = uints.sum()
    fun join(vararg parts: String, separator: String = ", "): String = parts.joinToString(separator)
}

// an override cannot redeclare a default value, it inherits the one of the overridden declaration
interface Repo {
    fun find(limit: Int = 10)
}

class SqlRepo : Repo {
    override fun find(limit: Int) {}
    fun find(limit: Long) {}
}

// an override of an override inherits the default value of the original declaration
interface CachedRepo : Repo {
    override fun find(limit: Int)
}

class CachingSqlRepo : CachedRepo {
    override fun find(limit: Int) {}
}

// a member extension of a generic interface
interface Formatter<T> {
    fun T.format(width: Int = 80, pad: Char): String
}

class IntFormatter : Formatter<Int> {
    override fun Int.format(width: Int, pad: Char): String = toString().padStart(width, pad)
}
