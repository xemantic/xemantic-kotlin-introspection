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
import java.net.JarURLConnection
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes
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
    fun `should find no deprecated built-in collection declarations`() {
        // the introspector cannot tell whether a member of a mutable collection type is deprecated as hidden,
        // see BuiltinClass, as the annotations of the built-ins are not read into their metadata,
        // and kotlin-reflect describes the read-only counterpart, sharing the same JVM type, instead,
        // so a standard library deprecating one has to be supported first;
        // the names of the annotations would be in the string table of the resource

        // when
        val resource = KotlinVersion::class.java.getResourceAsStream("/kotlin/collections/collections.kotlin_builtins")
            ?.use { String(it.readBytes(), Charsets.ISO_8859_1) }

        // then
        assert(resource != null && "Deprecated" !in resource)
    }

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
    fun `should find kotlin_builtins resources of standard library in a jar of a URL with an unencoded space`() {
        // given
        val jar = tempDir.resolve("my libs").createDirectories().resolve("stdlib.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            resources.forEach { resource ->
                output.putNextEntry(JarEntry(resource))
                output.closeEntry()
            }
        }
        // like a URL of the deprecated File.toURL(), which is not a valid URI
        @Suppress("DEPRECATION")
        val anchor = URL("jar:file:${jar.toAbsolutePath().toString().replace('\\', '/')}!/kotlin/kotlin.kotlin_builtins")

        // when
        val found = kotlinBuiltinsResources(anchor)

        // then
        assert(found == resources.sorted())
    }

    @Suppress("DEPRECATION")
    @Test
    fun `should tell the file of a file URL only when it is a valid file URI`() {
        assert(URL("file:/tmp/stdlib.jar").toFile() == java.io.File("/tmp/stdlib.jar"))
        assert(URL("file:/tmp/my libs/stdlib.jar").toFile() == null)
        assert(URL("file://server/share/stdlib.jar").toFile() == null)
        assert(URL("jar:file:/tmp/stdlib.jar!/kotlin/kotlin.kotlin_builtins").toFile() == null)
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

    @Test
    fun `should read kotlin_builtins resources from the container of the anchor`() {
        // given
        // another standard library, like an older or a shaded one, whose collections resource declares other types,
        // while the standard library of the class path still serves the original one
        val root = tempDir.resolve("other-stdlib")
        fun copy(resource: String, target: String) {
            val bytes = KotlinVersion::class.java.getResourceAsStream("/$resource")!!.use { it.readBytes() }
            root.resolve(target).also { it.parent.createDirectories() }.writeBytes(bytes)
        }
        copy("kotlin/kotlin.kotlin_builtins", "kotlin/kotlin.kotlin_builtins")
        copy("kotlin/ranges/ranges.kotlin_builtins", "kotlin/collections/collections.kotlin_builtins")
        val anchor = root.resolve("kotlin/kotlin.kotlin_builtins").toUri().toURL()

        // when
        val classes = readKotlinBuiltinClasses(anchor)

        // then
        assert("kotlin/Any" in classes)
        assert("kotlin/ranges/IntRange" in classes && "kotlin/collections/List" !in classes)
    }

    @Test
    fun `should close jar file of custom URL handler only when it is not cached`() {
        // given
        val jar = tempDir.resolve("nested.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            resources.forEach { resource ->
                output.putNextEntry(JarEntry(resource))
                output.closeEntry()
            }
        }
        val closedJarFiles = mutableListOf<Boolean>()
        val openedJarFiles = mutableListOf<JarFile>()
        // like the handler of a nested jar of a fat jar, whose jar file is not a file of a file: URL
        fun anchor(useCaches: Boolean): URL {
            val handler = object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection = object : JarURLConnection(url) {
                    init {
                        this.useCaches = useCaches
                    }
                    override fun connect() {}
                    override fun getJarFile(): JarFile = object : JarFile(jar.toFile()) {
                        override fun close() {
                            closedJarFiles += useCaches
                            super.close()
                        }
                    }.also { openedJarFiles += it }
                }
            }
            @Suppress("DEPRECATION")
            return URL(null, "jar:http://localhost/nested.jar!/kotlin/kotlin.kotlin_builtins", handler)
        }

        // when
        val found = listOf(
            kotlinBuiltinsResources(anchor(useCaches = true)),
            kotlinBuiltinsResources(anchor(useCaches = false))
        )
        val closed = closedJarFiles.toList()
        openedJarFiles.forEach { it.close() }

        // then
        assert(found == listOf(resources.sorted(), resources.sorted()))
        // the cached jar file is shared with the class loader, so it is left open
        assert(closed == listOf(false))
    }

}
