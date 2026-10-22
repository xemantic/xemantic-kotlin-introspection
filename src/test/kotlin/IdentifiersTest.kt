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

package com.xemantic.kotlin.introspection

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

class IdentifiersTest {

    @Test
    fun `should not escape identifiers`() {
        // given
        val names = listOf("name", "_name", "name2", "Ünicode", "data", "value", "open", "by", "get", "it")

        // when
        val escaped = names.map { escapeIdentifier(it) }

        // then
        assert(escaped == names)
    }

    @Test
    fun `should escape hard keywords`() {
        // given
        val names = listOf("in", "is", "object", "fun", "typeof", "null", "true")

        // when
        val escaped = names.map { escapeIdentifier(it) }

        // then
        assert(escaped == listOf("`in`", "`is`", "`object`", "`fun`", "`typeof`", "`null`", "`true`"))
    }

    @Test
    fun `should escape names which are not identifiers`() {
        // given
        val names = listOf("my name", "Dollar\$Nested", "\$Prefixed", " SECOND", "}", "2nd", "a-b")

        // when
        val escaped = names.map { escapeIdentifier(it) }

        // then
        assert(
            escaped == listOf("`my name`", "`Dollar\$Nested`", "`\$Prefixed`", "` SECOND`", "`}`", "`2nd`", "`a-b`")
        )
    }

    @Test
    fun `should not escape empty name or name containing backtick`() {
        // given
        val names = listOf("", "a`b")

        // when
        val escaped = names.map { escapeIdentifier(it) }

        // then
        assert(escaped == names)
    }

    @Test
    fun `should escape each segment of qualified name`() {
        // when
        val escaped = escapeQualifiedName("com.example.in.Outer.Dollar\$Nested")

        // then
        assert(escaped == "com.example.`in`.Outer.`Dollar\$Nested`")
    }

}
