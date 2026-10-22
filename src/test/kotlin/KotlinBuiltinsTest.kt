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
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.io.path.writeText
import kotlin.test.Test

class KotlinBuiltinsTest {

    @TempDir
    lateinit var tempDir: Path

    private val resources = listOf(
        "kotlin/kotlin.kotlin_builtins",
        "kotlin/collections/collections.kotlin_builtins",
        "kotlin/concurrent/atomics/atomics.kotlin_builtins"
    )

    @Test
    fun `should find all the kotlin_builtins resources of the standard library`() {
        // when
        val found = kotlinBuiltinsResources()

        // then
        assert(
            found.containsAll(
                listOf(
                    "kotlin/kotlin.kotlin_builtins",
                    "kotlin/annotation/annotation.kotlin_builtins",
                    "kotlin/collections/collections.kotlin_builtins",
                    "kotlin/concurrent/atomics/atomics.kotlin_builtins",
                    "kotlin/coroutines/coroutines.kotlin_builtins",
                    "kotlin/internal/internal.kotlin_builtins",
                    "kotlin/ranges/ranges.kotlin_builtins",
                    "kotlin/reflect/reflect.kotlin_builtins"
                )
            )
        )
    }

    @Test
    fun `should find kotlin_builtins resources of standard library in a directory`() {
        // given
        val root = tempDir.resolve("classes")
        (resources + "kotlin/Unit.class").forEach { resource ->
            root.resolve(resource).also { it.parent.createDirectories() }.writeText("")
        }
        val anchor = root.resolve("kotlin/kotlin.kotlin_builtins").toUri().toURL()

        // when
        val found = kotlinBuiltinsResources(anchor)

        // then
        assert(found == resources.sorted())
    }

    @Test
    fun `should find kotlin_builtins resources of standard library in a jar`() {
        // given
        val jar = tempDir.resolve("stdlib.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            (resources + "kotlin/Unit.class" + "other/other.kotlin_builtins").forEach { resource ->
                output.putNextEntry(JarEntry(resource))
                output.closeEntry()
            }
        }
        val anchor = URI("jar:${jar.toUri()}!/kotlin/kotlin.kotlin_builtins").toURL()

        // when
        val found = kotlinBuiltinsResources(anchor)

        // then
        assert(found == resources.sorted())
    }

    @Test
    fun `should find kotlin_builtins resources of standard library under a path prefix of a jar`() {
        // given
        val war = tempDir.resolve("app.war")
        JarOutputStream(war.outputStream()).use { output ->
            // the entry outside of the prefix is not a resource of the class path
            (resources.map { "WEB-INF/classes/$it" } + "kotlin/other.kotlin_builtins").forEach { entry ->
                output.putNextEntry(JarEntry(entry))
                output.closeEntry()
            }
        }
        val anchor = URI("jar:${war.toUri()}!/WEB-INF/classes/kotlin/kotlin.kotlin_builtins").toURL()

        // when
        val found = kotlinBuiltinsResources(anchor)

        // then
        assert(found == resources.sorted())
    }

    @Test
    fun `should fall back to the known kotlin_builtins resources when the listed ones lack the anchor`() {
        // given
        val jar = tempDir.resolve("unrelated.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            output.putNextEntry(JarEntry("kotlin/Unit.class"))
            output.closeEntry()
        }
        val anchor = URI("jar:${jar.toUri()}!/kotlin/kotlin.kotlin_builtins").toURL()

        // when
        val found = kotlinBuiltinsResources(anchor)

        // then
        assert(
            found.containsAll(
                listOf("kotlin/kotlin.kotlin_builtins", "kotlin/collections/collections.kotlin_builtins")
            )
        )
    }

}
