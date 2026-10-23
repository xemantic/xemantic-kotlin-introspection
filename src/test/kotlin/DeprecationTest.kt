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

class DeprecationTest {

    // whether the version is not newer than 2.1, the one the user code is compiled with
    private val isSince: (String) -> Boolean = { version ->
        val (major, minor) = version.split('.').map { it.toInt() }
        KotlinVersion(major, minor) <= KotlinVersion(2, 1)
    }

    @Test
    fun `should not deprecate declaration without annotations`() {
        // given
        val annotations = DeprecationAnnotations()

        // when
        val deprecation = annotations.deprecationOf(isSince)

        // then
        assert(deprecation == null)
    }

    @Test
    fun `should deprecate with WARNING level by default`() {
        // given
        val annotations = DeprecationAnnotations().apply {
            isDeprecated = true
            message = "Use bar()"
            replaceWith = "bar()"
        }

        // when
        val deprecation = annotations.deprecationOf(isSince)

        // then
        assert(deprecation?.level == DeprecationLevel.WARNING)
        assert(deprecation?.message == "Use bar()")
        assert(deprecation?.replaceWith == "bar()")
    }

    @Test
    fun `should deprecate with explicit level`() {
        // given
        val annotations = DeprecationAnnotations().apply {
            isDeprecated = true
            level = "ERROR"
            replaceWith = ""
        }

        // when
        val deprecation = annotations.deprecationOf(isSince)

        // then
        assert(deprecation?.level == DeprecationLevel.ERROR)
        assert(deprecation?.replaceWith == null)
    }

    @Test
    fun `should deprecate with the highest level reached since Kotlin version`() {
        // given
        val annotations = DeprecationAnnotations().apply {
            isDeprecated = true
            isDeprecatedSinceKotlin = true
            warningSince = "1.5"
            errorSince = "2.1"
            hiddenSince = "2.2"
        }

        // when
        val deprecation = annotations.deprecationOf(isSince)

        // then
        assert(deprecation?.level == DeprecationLevel.ERROR)
    }

    @Test
    fun `should hide declaration hidden since older Kotlin version`() {
        // given
        val annotations = DeprecationAnnotations().apply {
            isDeprecated = true
            isDeprecatedSinceKotlin = true
            warningSince = "1.3"
            hiddenSince = "1.6"
        }

        // when
        val deprecation = annotations.deprecationOf(isSince)

        // then
        assert(deprecation?.isHidden == true)
    }

    @Test
    fun `should not deprecate declaration before any Kotlin version it is deprecated since`() {
        // given
        val annotations = DeprecationAnnotations().apply {
            isDeprecated = true
            isDeprecatedSinceKotlin = true
            warningSince = "2.2"
            errorSince = "2.3"
        }

        // when
        val deprecation = annotations.deprecationOf(isSince)

        // then
        assert(deprecation == null)
    }

}
