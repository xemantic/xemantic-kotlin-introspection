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

/**
 * The extensions shown with the built-in types, told by the Kotlin modules declaring them, see [isBuiltinExtensionSource].
 */
class BuiltinExtensionSourceTest {

    @Test
    fun `should show extensions of the modules of the standard library and kotlin-reflect of every Kotlin version`() {
        // given
        // kotlin-stdlib-jre7 and -jre8 of Kotlin 1.2, kotlin-stdlib-jdk7 and -jdk8, merged into kotlin-stdlib since 1.8,
        // and kotlin-reflect, whose module is named kotlin-reflect-api from Kotlin 1.2.40 to 1.3.11
        val sources = listOf(
            "kotlin" to "kotlin-stdlib",
            "kotlin.text" to "kotlin-stdlib",
            "kotlin.io.path" to "kotlin-stdlib-jdk7",
            "kotlin.streams" to "kotlin-stdlib-jdk8",
            "kotlin.io" to "kotlin-stdlib-jre7",
            "kotlin.collections" to "kotlin-stdlib-jre8",
            "kotlin.reflect.full" to "kotlin-reflection",
            "kotlin.reflect.full" to "kotlin-reflect-api"
        )

        // when
        val notShown = sources.filterNot { (source, moduleName) -> isBuiltinExtensionSource(source, moduleName) }

        // then
        assert(notShown.isEmpty())
    }

    @Test
    fun `should not show extensions of other libraries declaring kotlin packages`() {
        // given
        val sources = listOf(
            "kotlin.metadata.jvm" to "kotlin.metadata.internal:kotlin-metadata-jvm",
            "kotlin.test" to "org.jetbrains.kotlin:kotlin-test",
            "kotlin.text" to null
        )

        // when
        val shown = sources.filter { (source, moduleName) -> isBuiltinExtensionSource(source, moduleName) }

        // then
        assert(shown.isEmpty())
    }

    @Test
    fun `should not show extensions of internal packages of the standard library`() {
        // when
        val isShown = isBuiltinExtensionSource("kotlin.jvm.internal", "kotlin-stdlib")

        // then
        assert(!isShown)
    }

}
