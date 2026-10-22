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

import com.xemantic.kotlin.introspection.test.TestClass
import com.xemantic.kotlin.test.sameAs
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.test.Test

/**
 * Introspection of classes visible only to the class loader passed to [KotlinIntrospector].
 */
class ClassLoaderIntrospectionTest {

    @TempDir
    lateinit var tempDir: Path

    private val isolatedTestFixtures: List<File> = System.getProperty("isolatedTestFixtures")
        .split(File.pathSeparator)
        .map { File(it) }

    private val isolatedTestClassSignature = /* language=kotlin */ """
        // com.xemantic.kotlin.introspection.isolated.IsolatedTestClass [page 1/1]
        class IsolatedTestClass(val value: Int)
        // from com.xemantic.kotlin.introspection.isolated
        fun IsolatedTestClass.isolatedExtension(): Int
    """.trimIndent()

    @Test
    fun `should index classes visible only to the given class loader`() {
        // given
        val classLoader = URLClassLoader(
            isolatedTestFixtures.map { it.toURI().toURL() }.toTypedArray(),
            javaClass.classLoader
        )
        val introspector = KotlinIntrospector(classLoader)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.IsolatedTestClass")
        val searchResult = introspector.search("IsolatedTestClass")

        // then
        signature sameAsKotlin isolatedTestClassSignature
        searchResult sameAs """
            // search results for "IsolatedTestClass" [page 1/1]
            com.xemantic.kotlin.introspection.isolated.IsolatedTestClass
        """.trimIndent()
    }

    @Test
    fun `should index classes referenced by the Class-Path of a pathing jar`() {
        // given
        val pathingJar = tempDir.resolve("pathing.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.CLASS_PATH] = isolatedTestFixtures.joinToString(" ") {
                it.toURI().toString()
            }
        }
        JarOutputStream(pathingJar.outputStream(), manifest).close()
        val classLoader = URLClassLoader(
            arrayOf(pathingJar.toUri().toURL()),
            javaClass.classLoader
        )
        val introspector = KotlinIntrospector(classLoader)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.IsolatedTestClass")

        // then
        signature sameAsKotlin isolatedTestClassSignature
    }

    /**
     * An introspector of the isolated test fixtures without the class `Absent`,
     * which they refer to.
     */
    private fun introspectorWithoutAbsentClass(): KotlinIntrospector {
        val root = tempDir.resolve("withoutAbsent")
        isolatedTestFixtures.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.name != "Absent.class" }
                .forEach { it.copyTo(root.resolve(it.relativeTo(dir).path).toFile(), overwrite = true) }
        }
        val classLoader = URLClassLoader(
            arrayOf(root.toUri().toURL()),
            javaClass.classLoader
        )
        return KotlinIntrospector(classLoader)
    }

    @Test
    fun `should resolve class whose generic supertype refers to a class missing from the classpath`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentTypeArgumentHolder"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentTypeArgumentHolder [page 1/1]
            abstract class AbsentTypeArgumentHolder : Comparable<Absent> {
                fun hold()
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of function taking class missing from the classpath`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentTypeArgumentComparable"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentTypeArgumentComparable [page 1/1]
            class AbsentTypeArgumentComparable : Comparable<Absent> {
                override operator fun compareTo(other: Absent): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of function taking array of type parameter when supertype refers to class missing from the classpath`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.StringAbsentArrayProcessor"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.StringAbsentArrayProcessor [page 1/1]
            abstract class StringAbsentArrayProcessor : AbsentArrayProcessor<String, Absent> {
                override fun process(items: Array<String>)
                fun process(items: Array<Absent>)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of Java superclass methods when another method refers to class missing from the classpath`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentJavaSubclass"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentJavaSubclass [page 1/1]
            class AbsentJavaSubclass : AbsentJavaBase<String> {
                override fun process(item: String)
                fun process(item: Int)
                override fun run()
                fun run(times: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of Java superclass methods when generic types of methods refer to class missing from the classpath`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentBoundJavaSubclass"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentBoundJavaSubclass [page 1/1]
            class AbsentBoundJavaSubclass : AbsentBoundJavaBase {
                override fun run()
                override fun <T : Comparable<Absent>> compare(item: T)
                fun compare(item: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should not duplicate declarations of a class present twice on the classpath`() {
        // given
        val testClasses = File(TestClass::class.java.protectionDomain.codeSource.location.toURI())
        val extensionsClass = "com/xemantic/kotlin/introspection/test/ext/TestClassExtensionsKt.class"
        val duplicateRoot = tempDir.resolve("duplicate")
        duplicateRoot.resolve(extensionsClass).parent.createDirectories()
        testClasses.resolve(extensionsClass).copyTo(duplicateRoot.resolve(extensionsClass).toFile())
        val classLoader = URLClassLoader(
            arrayOf(duplicateRoot.toUri().toURL()),
            javaClass.classLoader
        )
        val introspector = KotlinIntrospector(classLoader)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.test.TestClass")

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestClass [page 1/1]
            class TestClass(val name: String) {
                fun greet(): String
            }
            // from com.xemantic.kotlin.introspection.test
            fun TestClass.farewell(): String
            // from com.xemantic.kotlin.introspection.test.ext
            val TestClass.uppercaseName: String
            fun TestClass.describe(): String
        """.trimIndent()
    }

}
