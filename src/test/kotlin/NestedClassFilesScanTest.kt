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
import com.xemantic.kotlin.test.sameAs
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream
import kotlin.test.Test

/**
 * The classpath scan skipping the nested class files which cannot contain a Kotlin class, without reading them,
 * which must not change the index.
 */
class NestedClassFilesScanTest {

    @TempDir
    lateinit var tempDir: Path

    private val isolatedTestFixtures: List<File> = System.getProperty("isolatedTestFixtures")
        .split(File.pathSeparator)
        .map { File(it) }
        .filter { it.exists() }

    /**
     * The class files of the Java class `JavaOuter`, with its nested classes,
     * and of the Kotlin classes compiled with nested classes not declared as such in the metadata,
     * copied from the isolated test fixtures into a single root, so that the outer and nested classes are together.
     */
    private val nestedClassFiles: List<Pair<String, File>> = isolatedTestFixtures.flatMap { dir ->
        dir.walkTopDown()
            .filter {
                it.isFile && listOf("JavaOuter", "EntryWithBody", "WhenMappingsHolder").any { prefix ->
                    it.name.startsWith(prefix)
                }
            }
            .map { it.relativeTo(dir).invariantSeparatorsPath to it }
            .toList()
    }

    // the classes scanned are only the ones of the given root, not of the test classpath
    private fun classLoader(root: Path) = URLClassLoader(
        arrayOf(root.toUri().toURL()),
        ClassLoader.getPlatformClassLoader()
    )

    @Test
    fun `should index the same classes and extensions as when reading every nested class file`() {
        // given
        val classLoader = URLClassLoader(
            isolatedTestFixtures.map { it.toURI().toURL() }.toTypedArray(),
            javaClass.classLoader
        )

        // when
        val scan = scan(classLoader)
        val readingEveryClassFile = scan(classLoader, skipNestedClassFiles = false)

        // then
        assert(readingEveryClassFile.skippedClassFiles.isEmpty())
        assert(scan.readClassFileCount + scan.skippedClassFiles.size == readingEveryClassFile.readClassFileCount)
        assert(
            scan.skippedClassFiles.containsAll(
                listOf(
                    "com/xemantic/kotlin/introspection/isolated/JavaOuter\$Nested",
                    "com/xemantic/kotlin/introspection/isolated/JavaOuter\$Nested\$Deep",
                    "com/xemantic/kotlin/introspection/isolated/JavaOuter\$Member",
                    "com/xemantic/kotlin/introspection/isolated/WhenMappingsHolder\$WhenMappings"
                )
            )
        )
        scan.indexDump() sameAs readingEveryClassFile.indexDump()
    }

    @Test
    fun `should skip nested class files of Java class while indexing Kotlin class named like nested one`() {
        // given
        val root = tempDir.resolve("classes")
        nestedClassFiles.forEach { (path, file) ->
            file.copyTo(root.resolve(path).toFile())
        }

        // when
        val scan = scan(classLoader(root))

        // then
        // neither JavaOuter$Impostor, nor EntryWithBody$WITH_BODY
        assert(
            scan.skippedClassFiles.sorted() == listOf(
                "com/xemantic/kotlin/introspection/isolated/JavaOuter\$Member",
                "com/xemantic/kotlin/introspection/isolated/JavaOuter\$Nested",
                "com/xemantic/kotlin/introspection/isolated/JavaOuter\$Nested\$Deep",
                "com/xemantic/kotlin/introspection/isolated/WhenMappingsHolder\$WhenMappings"
            )
        )
        DefaultKotlinIntrospector(classLoader(root)).search("Impostor") sameAs """
            // search results for "Impostor" [page 1/1]
            com.xemantic.kotlin.introspection.isolated.JavaOuter${'$'}Impostor
        """.trimIndent()
        scan.indexDump() sameAs scan(classLoader(root), skipNestedClassFiles = false).indexDump()
    }

    @Test
    fun `should not index synthetic nested class without class metadata even when reading it`() {
        // given
        val root = tempDir.resolve("classes")
        nestedClassFiles.forEach { (path, file) ->
            file.copyTo(root.resolve(path).toFile())
        }
        val whenMappings = nestedClassFiles.map { (path, _) -> path }.filter { it.endsWith("\$WhenMappings.class") }

        // when
        val scan = scan(classLoader(root), skipNestedClassFiles = false)

        // then
        assert(whenMappings == listOf("com/xemantic/kotlin/introspection/isolated/WhenMappingsHolder\$WhenMappings.class"))
        assert("whenmappings" !in scan.classIndex)
        assert(scan.classIndex["whenmappingsholder"] == listOf("com.xemantic.kotlin.introspection.isolated.WhenMappingsHolder"))
    }

    @Test
    fun `should skip nested class files of jar listing them before their outer class files`() {
        // given
        val jar = tempDir.resolve("nested-first.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            nestedClassFiles.sortedByDescending { (path, _) -> path.count { it == '$' } }.forEach { (path, file) ->
                out.putNextEntry(JarEntry(path))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }

        // when
        val scan = scan(classLoader(jar))

        // then
        assert(scan.skippedClassFiles.size == 4)
        scan.indexDump() sameAs scan(classLoader(jar), skipNestedClassFiles = false).indexDump()
    }

}

private fun scan(
    classLoader: ClassLoader,
    skipNestedClassFiles: Boolean = true
): DefaultKotlinIntrospector.ClasspathScan = DefaultKotlinIntrospector(classLoader, scanEntireClasspath = false)
    .scanClasspath(skipNestedClassFiles)

/**
 * The extensions and classes found by the classpath scan, one per line, in the scan order,
 * with the flags of the classes, which decide what is indexed.
 */
private fun DefaultKotlinIntrospector.ClasspathScan.indexDump(): String = buildList {
    extensionIndex.forEach { (receiver, extensions) ->
        extensions.forEach {
            add("fun $receiver.${it.function.name} in ${it.sourceClass} from ${it.source} object=${it.isObjectMember}")
        }
    }
    extensionPropertyIndex.forEach { (receiver, extensions) ->
        extensions.forEach {
            add(
                "val $receiver.${it.property.name} in ${it.sourceClass} from ${it.source} " +
                    "object=${it.isObjectMember} assignable=${it.isAssignable}"
            )
        }
    }
    classIndex.forEach { (simpleName, classes) ->
        classes.forEach { add("indexed $simpleName: $it") }
    }
    classes.forEach {
        add(
            "class ${it.name} (${it.binaryName}) ${it.scope.visibility} open=${it.scope.isOpen} " +
                "companion=${it.scope.isCompanion} usable=${it.isUsable} searchable=${it.isSearchable} " +
                "extensions=${it.extensions.map { e -> e.function.name }} " +
                "properties=${it.extensionProperties.map { p -> p.property.name }}"
        )
    }
}.joinToString("\n")
