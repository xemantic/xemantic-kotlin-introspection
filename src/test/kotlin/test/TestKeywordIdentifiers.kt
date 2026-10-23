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

// Enum class with entries named like hard keywords, which have to be escaped,
// and like a soft keyword, which does not
enum class KeywordEntryEnum {
    `in`,
    `object`,
    data
}

// Annotation class referring to an enum entry named like a keyword in a default value
annotation class KeywordDefaultAnnotation(
    val `if`: KeywordEntryEnum = KeywordEntryEnum.`object`
)

// Class with declarations named like hard keywords, which have to be escaped,
// like soft keywords, which do not, and with names which are not identifiers at all
class KeywordMembersSample<`in`>(val `is`: `in`, val value: Int) {
    class `interface`
    val `typeof`: String = ""
    var `val`: Int = 0
    fun `fun`(`if`: Int, `when`: (`in`: Int, `my name`: String) -> Unit) {}
    fun <`class`> `return`(value: `class`): `class` = value
    fun open(by: Int, get: String) {}
    fun `with space`() {}
    fun nested(): `interface` = `interface`()
    companion object `object` {
        fun `throw`() {}
    }
}

fun KeywordMembersSample<*>.`for`() {}

val KeywordMembersSample<*>.`null`: Int get() = 0
