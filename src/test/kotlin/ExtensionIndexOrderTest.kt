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
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmValueParameter
import kotlin.test.Test

class ExtensionIndexOrderTest {

    private fun extension(name: String, sourceClass: String, parameterCount: Int = 0) = IndexedExtension(
        function = KmFunction(name).apply {
            repeat(parameterCount) { valueParameters += KmValueParameter("p$it") }
        },
        sourceClass = sourceClass,
        source = sourceClass.substringBeforeLast('.'),
        isObjectMember = false
    )

    private fun extensionProperty(name: String, sourceClass: String) = IndexedExtensionProperty(
        property = KmProperty(name),
        sourceClass = sourceClass,
        source = sourceClass.substringBeforeLast('.'),
        isObjectMember = false,
        setterVisibility = null
    )

    @Test
    fun `should sort extensions by name, then by declaring class, keeping the metadata order of overloads`() {
        // given
        // in the classpath scan order, which depends on the file system
        val index = mapOf(
            "com.example.Subject" to listOf(
                extension("foo", "com.example.BKt"),
                extension("foo", "com.example.AKt", parameterCount = 2),
                extension("bar", "com.example.BKt"),
                extension("foo", "com.example.AKt", parameterCount = 1)
            )
        )

        // when
        val sorted = index.sortedByNameAndSourceClass()

        // then
        assert(
            sorted.getValue("com.example.Subject").map {
                "${it.sourceClass}.${it.function.name}/${it.function.valueParameters.size}"
            } == listOf(
                "com.example.BKt.bar/0",
                "com.example.AKt.foo/2",
                "com.example.AKt.foo/1",
                "com.example.BKt.foo/0"
            )
        )
    }

    @Test
    fun `should sort extension properties by name, then by declaring class`() {
        // given
        // in the classpath scan order, which depends on the file system
        val index = mapOf(
            "com.example.Subject" to listOf(
                extensionProperty("foo", "com.example.BKt"),
                extensionProperty("foo", "com.example.AKt"),
                extensionProperty("bar", "com.example.BKt")
            )
        )

        // when
        val sorted = index.sortedByNameAndSourceClass()

        // then
        assert(
            sorted.getValue("com.example.Subject").map { "${it.sourceClass}.${it.property.name}" } == listOf(
                "com.example.BKt.bar",
                "com.example.AKt.foo",
                "com.example.BKt.foo"
            )
        )
    }

}
