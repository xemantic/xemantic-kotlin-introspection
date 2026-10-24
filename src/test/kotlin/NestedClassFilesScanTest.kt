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

    private val isolatedTestFixtures: List<File> = testFixtures("isolatedTestFixtures")

    private val java8TestFixtures: List<File> = testFixtures("java8TestFixtures")

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
        val skipping = scan(classLoader)
        val readingEveryClassFile = scan(classLoader, skipNestedClassFiles = false)

        // then
        assert(readingEveryClassFile.skippedClassFiles.isEmpty())
        assert(skipping.skippedClassFileCount == skipping.skippedClassFiles.size)
        assert(skipping.readClassFileCount + skipping.skippedClassFileCount == readingEveryClassFile.readClassFileCount)
        assert(
            skipping.skippedClassFiles.containsAll(
                listOf(
                    "com/xemantic/kotlin/introspection/isolated/JavaOuter\$Nested",
                    "com/xemantic/kotlin/introspection/isolated/JavaOuter\$Nested\$Deep",
                    "com/xemantic/kotlin/introspection/isolated/JavaOuter\$Member",
                    "com/xemantic/kotlin/introspection/isolated/WhenMappingsHolder\$WhenMappings"
                )
            )
        )
        skipping.indexDump() sameAs readingEveryClassFile.indexDump()
    }

    @Test
    fun `should skip nested class files of Java class while indexing Kotlin class named like nested one`() {
        // given
        val root = tempDir.resolve("classes")
        nestedClassFiles.forEach { (path, file) ->
            file.copyTo(root.resolve(path).toFile())
        }

        // when
        val skipping = scan(classLoader(root))

        // then
        // neither JavaOuter$Impostor, nor EntryWithBody$WITH_BODY
        assert(
            skipping.skippedClassFiles.sorted() == listOf(
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
        skipping.indexDump() sameAs scan(classLoader(root), skipNestedClassFiles = false).indexDump()
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
        val skipping = scan(classLoader(root), skipNestedClassFiles = false)

        // then
        assert(whenMappings == listOf("com/xemantic/kotlin/introspection/isolated/WhenMappingsHolder\$WhenMappings.class"))
        assert("whenmappings" !in skipping.classIndex)
        assert(skipping.classIndex["whenmappingsholder"] == listOf("com.xemantic.kotlin.introspection.isolated.WhenMappingsHolder"))
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
        val skipping = scan(classLoader(jar))

        // then
        assert(skipping.skippedClassFiles.size == 4)
        skipping.indexDump() sameAs scan(classLoader(jar), skipNestedClassFiles = false).indexDump()
    }

    @Test
    fun `should skip generated nested class files not declared in Kotlin class metadata`() {
        // given
        val root = tempDir.resolve("classes")
        val defaultImpls = "com/xemantic/kotlin/introspection/defaultimpls/DefaultImplsGreeter"
        // compiled without the JVM default methods, and on the test classpath
        listOf(defaultImpls, "$defaultImpls\$DefaultImpls").forEach { name ->
            javaClass.classLoader.getResourceAsStream("$name.class")!!.use { input ->
                root.resolve("$name.class").toFile().apply { parentFile.mkdirs() }.outputStream().use { input.copyTo(it) }
            }
        }
        isolatedTestFixtures.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.name.startsWith("EntriesMappingsHolder") }.forEach { file ->
                file.copyTo(root.resolve(file.relativeTo(dir).path).toFile())
            }
        }

        // when
        val skipping = scan(classLoader(root))

        // then
        assert(
            skipping.skippedClassFiles.sorted() == listOf(
                "$defaultImpls\$DefaultImpls",
                "com/xemantic/kotlin/introspection/isolated/EntriesMappingsHolder\$EntriesMappings"
            )
        )
        skipping.indexDump() sameAs scan(classLoader(root), skipNestedClassFiles = false).indexDump()
    }

    @Test
    fun `should read nested class file of Kotlin class not declaring it in metadata`() {
        // given
        // like a compiler plugin might generate the class with the class metadata, without declaring it in the outer one
        val root = rewrittenOuterRoot { bytes -> withoutNestedClassInMetadata(bytes, "Nested") }

        // when
        val skipping = scan(classLoader(root))

        // then
        assert(skipping.skippedClassFiles.isEmpty())
        assert(skipping.classIndex["nested"] == listOf("com.xemantic.kotlin.introspection.isolated.RewrittenOuter.Nested"))
        skipping.indexDump() sameAs scan(classLoader(root), skipNestedClassFiles = false).indexDump()
    }

    @Test
    fun `should skip nested class files of Kotlin class with metadata stripped, as they cannot be indexed anyway`() {
        // given
        // like R8 might strip the metadata of a class, while keeping the one of its nested classes
        val root = rewrittenOuterRoot { bytes -> withoutMetadata(bytes) }

        // when
        val skipping = scan(classLoader(root))
        val readingEveryClassFile = scan(classLoader(root), skipNestedClassFiles = false)

        // then
        // RewrittenOuter declares only its own member classes, like Java8Outer, so that RewrittenOuter$Nested$Deep is read
        assert(skipping.skippedClassFiles == listOf("com/xemantic/kotlin/introspection/isolated/RewrittenOuter\$Nested"))
        // found when read, but not indexed, as the outer class is unknown
        assert(
            readingEveryClassFile.classes.map { it.name }.sorted() == listOf(
                "com/xemantic/kotlin/introspection/isolated/RewrittenOuter.Nested",
                "com/xemantic/kotlin/introspection/isolated/RewrittenOuter.Nested.Deep"
            )
        )
        assert(readingEveryClassFile.classIndex.isEmpty())
        skipping.indexDump(withClasses = false) sameAs readingEveryClassFile.indexDump(withClasses = false)
    }

    @Test
    fun `should skip nested class files of class with metadata of unknown kind, as they cannot be indexed anyway`() {
        // given
        // like a future version of Kotlin might introduce
        val root = rewrittenOuterRoot { bytes -> withMetadataKind(bytes, kind = 99) }

        // when
        val skipping = scan(classLoader(root))
        val readingEveryClassFile = scan(classLoader(root), skipNestedClassFiles = false)

        // then
        // RewrittenOuter declares only its own member classes, like Java8Outer, so that RewrittenOuter$Nested$Deep is read
        assert(skipping.skippedClassFiles == listOf("com/xemantic/kotlin/introspection/isolated/RewrittenOuter\$Nested"))
        // found when read, but not indexed, as the outer class is unknown
        assert(readingEveryClassFile.classes.size == 2)
        assert(readingEveryClassFile.classIndex.isEmpty())
        skipping.indexDump(withClasses = false) sameAs readingEveryClassFile.indexDump(withClasses = false)
    }

    @Test
    fun `should skip nested class files of Java class compiled for Java 8 declared by class files read`() {
        // given
        val root = tempDir.resolve("classes")
        java8TestFixtures.forEach { dir -> dir.copyRecursively(root.toFile()) }

        // when
        val skipping = scan(classLoader(root))

        // then
        // without the NestMembers attribute, Java8Outer declares only its own member classes,
        // so that Java8Outer$Nested$Deep, declared only by the skipped Java8Outer$Nested, is read,
        // telling in turn that Java8Outer$Nested$Deep$Deeper is nested in it
        assert(skipping.readClassFileCount == 2)
        assert(
            skipping.skippedClassFiles.sorted() == listOf(
                "com/xemantic/kotlin/introspection/isolated/Java8Outer\$Member",
                "com/xemantic/kotlin/introspection/isolated/Java8Outer\$Nested",
                "com/xemantic/kotlin/introspection/isolated/Java8Outer\$Nested\$Deep\$Deeper"
            )
        )
    }

    /**
     * A root with the class files of `RewrittenOuter`, [rewritten][rewrite], and of its nested classes.
     */
    private fun rewrittenOuterRoot(rewrite: (ByteArray) -> ByteArray): Path {
        val root = tempDir.resolve("rewritten")
        isolatedTestFixtures.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.name.startsWith("RewrittenOuter") }.forEach { file ->
                val target = root.resolve(file.relativeTo(dir).path).toFile()
                target.parentFile.mkdirs()
                target.writeBytes(if (file.name == "RewrittenOuter.class") rewrite(file.readBytes()) else file.readBytes())
            }
        }
        return root
    }

}

private fun scan(
    classLoader: ClassLoader,
    skipNestedClassFiles: Boolean = true
): DefaultKotlinIntrospector.ClasspathScan = DefaultKotlinIntrospector(classLoader, scanEntireClasspath = false)
    .scanClasspath(skipNestedClassFiles, collectSkippedClassFiles = true)

/**
 * The extensions and classes indexed by the classpath scan, one per line, in the scan order,
 * and, [withClasses], all the Kotlin classes found, with their flags, which decide what is indexed.
 */
private fun DefaultKotlinIntrospector.ClasspathScan.indexDump(withClasses: Boolean = true): String = buildList {
    extensionIndex.forEach { (receiver, extensions) ->
        extensions.forEach {
            add("fun $receiver.${it.function.name} in ${it.sourceClass} from ${it.source} object=${it.isObjectMember}")
        }
    }
    extensionPropertyIndex.forEach { (receiver, extensions) ->
        extensions.forEach {
            add(
                "val $receiver.${it.property.name} in ${it.sourceClass} from ${it.source} " +
                    "object=${it.isObjectMember} assignable=${it.setterVisibility != null}"
            )
        }
    }
    classIndex.forEach { (simpleName, classes) ->
        classes.forEach { add("indexed $simpleName: $it") }
    }
    if (withClasses) classes.forEach {
        add(
            "class ${it.name} (${it.binaryName}) ${it.scope.visibility} subclassable=${it.scope.isSubclassable} " +
                "companion=${it.scope.isCompanion} usable=${it.scope.isUsable} sealed=${it.scope.sealedSubclasses} " +
                "extensions=${it.extensions.map { e -> e.function.name }} " +
                "properties=${it.extensionProperties.map { p -> p.property.name }}"
        )
    }
}.joinToString("\n")

private fun testFixtures(property: String): List<File> = System.getProperty(property)
    .split(File.pathSeparator)
    .map { File(it) }
    .filter { it.exists() }
