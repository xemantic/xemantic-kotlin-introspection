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
        // the JVM class declaring them, the Kotlin package, or the object, to import them from, and the module
        val sources = listOf(
            Triple("kotlin.ComparisonsKt", "kotlin", "kotlin-stdlib"),
            Triple("kotlin.text.StringsKt__StringsKt", "kotlin.text", "kotlin-stdlib"),
            Triple("kotlin.io.path.PathsKt__PathUtilsKt", "kotlin.io.path", "kotlin-stdlib-jdk7"),
            // relocated to another JVM package by @JvmPackageName
            Triple("kotlin.streams.jdk8.StreamsKt", "kotlin.streams", "kotlin-stdlib-jdk8"),
            Triple("kotlin.io.ExceptionsKt", "kotlin.io", "kotlin-stdlib-jre7"),
            Triple("kotlin.collections.jdk8.CollectionsJDK8Kt", "kotlin.collections", "kotlin-stdlib-jre8"),
            Triple("kotlin.reflect.full.KClasses", "kotlin.reflect.full", "kotlin-reflection"),
            Triple("kotlin.reflect.full.KClasses", "kotlin.reflect.full", "kotlin-reflect-api"),
            // an object, the member extensions are imported from by its located name
            Triple("kotlin.time.TimeSource\$Monotonic", "kotlin.time.TimeSource.Monotonic", "kotlin-stdlib")
        )

        // when
        val notShown = sources.filterNot { (sourceClass, source, moduleName) ->
            isBuiltinExtensionSource(sourceClass, source, moduleName)
        }

        // then
        assert(notShown.isEmpty())
    }

    @Test
    fun `should not show extensions of other libraries declaring kotlin packages`() {
        // given
        val sources = listOf(
            Triple("kotlin.metadata.jvm.JvmExtensionsKt", "kotlin.metadata.jvm", "kotlin.metadata.internal:kotlin-metadata-jvm"),
            Triple("kotlin.test.AssertionsKt__AssertionsKt", "kotlin.test", "org.jetbrains.kotlin:kotlin-test"),
            Triple("kotlin.text.StringsKt__StringsKt", "kotlin.text", null)
        )

        // when
        val shown = sources.filter { (sourceClass, source, moduleName) ->
            isBuiltinExtensionSource(sourceClass, source, moduleName)
        }

        // then
        assert(shown.isEmpty())
    }

    @Test
    fun `should not show extensions of internal packages of the standard library`() {
        // given
        val sources = listOf(
            Triple("kotlin.jvm.internal.ClassReferenceKt", "kotlin.jvm.internal", "kotlin-stdlib"),
            // the member extensions of an object in an internal package
            Triple("kotlin.jvm.internal.Holder\$Companion", "kotlin.jvm.internal.Holder.Companion", "kotlin-stdlib")
        )

        // when
        val shown = sources.filter { (sourceClass, source, moduleName) ->
            isBuiltinExtensionSource(sourceClass, source, moduleName)
        }

        // then
        assert(shown.isEmpty())
    }

    @Test
    fun `should not show extensions of standard library relocated by shading keeping its Kotlin package`() {
        // when - the metadata keeps the Kotlin package of the relocated file facade, like a Kotlin-aware relocation might
        val isShown = isBuiltinExtensionSource("shaded.kotlin.text.StringsKt__StringsKt", "kotlin.text", "kotlin-stdlib")

        // then
        assert(!isShown)
    }

}
