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
import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.sameAs
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Collections
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
                fun prim(x: Int?)
                override fun <S : String> bounded(x: S)
                fun <S : Number> bounded(x: S)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of Java methods taking arrays of type variable when another method refers to class missing from the classpath`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentJavaArraySubclass"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentJavaArraySubclass [page 1/1]
            class AbsentJavaArraySubclass : AbsentJavaBase<String> {
                override fun process(items: Array<String>)
                fun process(items: Array<Int>)
                override fun <S : String> boundedArray(items: Array<S>)
                fun <S : Number> boundedArray(items: Array<S>)
                override fun nested(items: Array<Array<String>>)
                fun nested(items: Array<Array<Int>>)
                override fun lists(items: Array<List<String>>)
                fun lists(items: Array<Set<String>>)
            }
        """.trimIndent()
    }

    @Test
    fun `should erase type variable of raw Java superclass to its bound when another method refers to class missing from the classpath`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentRawJavaSubclass"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentRawJavaSubclass [page 1/1]
            class AbsentRawJavaSubclass : AbsentRawJavaMid {
                override fun process(items: Array<Any?>)
                fun process(items: Array<Int>)
                fun <S : Number> bounded(x: S)
            }
        """.trimIndent()
    }

    @Test
    fun `should not bind Java method type variable shadowing type variable of its class when another method refers to class missing from the classpath`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentJavaOuterSubclass"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentJavaOuterSubclass [page 1/1]
            class AbsentJavaOuterSubclass : AbsentJavaOuter<String> {
                inner class InnerSubclass : AbsentJavaOuter.Inner<Int>
                inner class ShadowingSubclass : AbsentJavaOuter.Shadowing<Int>
                override fun <T : Number> shadowing(x: T)
                fun <T : String> shadowing(x: T)
            }
        """.trimIndent()
    }

    @Test
    fun `should bind type variable of outer Java class when another method refers to class missing from the classpath`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentJavaOuterSubclass.InnerSubclass"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentJavaOuterSubclass.InnerSubclass [page 1/1]
            inner class InnerSubclass : AbsentJavaOuter.Inner<Int> {
                override fun outer(x: String)
                fun outer(x: Int)
                override fun inner(x: Int)
                fun inner(x: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should bind type variable of inner Java class shadowing type variable of outer class when another method refers to class missing from the classpath`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentJavaOuterSubclass.ShadowingSubclass"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentJavaOuterSubclass.ShadowingSubclass [page 1/1]
            inner class ShadowingSubclass : AbsentJavaOuter.Shadowing<Int> {
                override fun shadowed(x: Int)
                fun shadowed(x: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark function taking type parameter as override of Java method taking primitive when another method refers to class missing from the classpath`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentJavaTypeParameterSubclass"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentJavaTypeParameterSubclass [page 1/1]
            class AbsentJavaTypeParameterSubclass<P : Int?> : AbsentJavaBase<String> {
                fun prim(x: P)
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
                fun <T : CharSequence> compare(item: T)
                fun compare(item: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of members of Kotlin superclass without metadata`() {
        // given
        val root = tempDir.resolve("stripped")
        isolatedTestFixtures.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val target = root.resolve(file.relativeTo(dir).path).toFile()
                target.parentFile.mkdirs()
                target.writeBytes(
                    when (file.name) {
                        "MetadataStrippedBase.class" -> shrunk(withoutMetadata(file.readBytes()))
                        "MetadataStrippedSubclass.class" -> shrunk(file.readBytes())
                        else -> file.readBytes()
                    }
                )
            }
        }
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.MetadataStrippedSubclass",
            pageSize = 20
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.MetadataStrippedSubclass [page 1/1]
            abstract class MetadataStrippedSubclass : MetadataStrippedBase<String> {
                override val presentable: String
                override val isReady: Boolean
                override var count: Int
                override val String.measure: Int
                val Int.measure: Int
                override fun String.describe(x: Int): String
                fun describe(x: Int): String
                override suspend fun load(id: String): String
                fun load(id: String, extra: Int): String
                override fun onEvent(callback: suspend (String) -> Unit)
                override fun process(item: String)
                fun process(item: Int)
                override fun shrunk(x: Int)
                override fun shrunk(x: String)
                fun shrunkAlone()
            }
        """.trimIndent()
    }

    @Test
    fun `should neither find nor list synthetic nested class declared in metadata of outer class`() {
        // given
        val root = tempDir.resolve("synthetic")
        isolatedTestFixtures.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val target = root.resolve(file.relativeTo(dir).path).toFile()
                target.parentFile.mkdirs()
                target.writeBytes(
                    if (file.name == "SyntheticNestedHolder\$\$serializer.class") {
                        synthetic(file.readBytes())
                    } else {
                        file.readBytes()
                    }
                )
            }
        }
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val searchResult = introspector.search("\$serializer")
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.SyntheticNestedHolder")

        // then
        assert(searchResult == null)
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.SyntheticNestedHolder [page 1/1]
            class SyntheticNestedHolder {
                class Visible
            }
        """.trimIndent()
    }

    /**
     * Adds the `ACC_SYNTHETIC` flag to the class, like kotlinx.serialization does for the `$serializer`,
     * leaving the flags of the `InnerClasses` attribute, which [Class.isSynthetic] reports, intact.
     */
    private fun synthetic(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                super.visit(version, access or Opcodes.ACC_SYNTHETIC, name, signature, superName, interfaces)
            }
        }, 0)
        return writer.toByteArray()
    }

    /**
     * Removes the JVM methods `shrunk(int)` and `shrunkAlone()` from the class bytes, like R8 might.
     */
    private fun shrunk(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? = if ((name == "shrunk" && descriptor == "(I)V") || name == "shrunkAlone") {
                null
            } else {
                super.visitMethod(access, name, descriptor, signature, exceptions)
            }
        }, 0)
        return writer.toByteArray()
    }

    /**
     * Removes the `@Metadata` annotation from the class bytes, like R8 or shading might.
     */
    private fun withoutMetadata(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                if (descriptor == "Lkotlin/Metadata;") null else super.visitAnnotation(descriptor, visible)
        }, 0)
        return writer.toByteArray()
    }

    /**
     * An introspector of the isolated test fixtures without the class `Absent`,
     * whose class loader loads every class, but serves the class files of the [unreadableClasses]
     * with an unsupported major version, like a class file too new for ASM.
     */
    private fun introspectorWithUnreadableClassFiles(vararg unreadableClasses: String): KotlinIntrospector {
        val root = tempDir.resolve("unreadable")
        isolatedTestFixtures.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.name != "Absent.class" }
                .forEach { it.copyTo(root.resolve(it.relativeTo(dir).path).toFile(), overwrite = true) }
        }
        val unreadableResources = unreadableClasses.map { it.replace('.', '/') + ".class" }.toSet()
        val classLoader = object : URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader) {
            override fun getResourceAsStream(name: String): InputStream? {
                val stream = super.getResourceAsStream(name)
                return if (stream != null && name in unreadableResources) {
                    val bytes = stream.use { it.readBytes() }
                    // the major version, following the magic number and the minor version
                    bytes[6] = 0x7F
                    bytes[7] = 0x7F
                    bytes.inputStream()
                } else stream
            }
        }
        return KotlinIntrospector(classLoader)
    }

    @Test
    fun `should mark overrides of class whose class file cannot be parsed`() {
        // given
        val introspector = introspectorWithUnreadableClassFiles(
            "com.xemantic.kotlin.introspection.isolated.AbsentJavaSubclass"
        )

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
                fun prim(x: Int?)
                override fun <S : String> bounded(x: S)
                fun <S : Number> bounded(x: S)
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve class whose Java superclass can be neither reflected nor parsed`() {
        // given
        val introspector = introspectorWithUnreadableClassFiles(
            "com.xemantic.kotlin.introspection.isolated.AbsentJavaBase"
        )

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentJavaSubclass"
        )

        // then
        // the methods of the superclass are unknown, so the overrides cannot be told,
        // and are rendered as open, as an override is implicitly open
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentJavaSubclass [page 1/1]
            class AbsentJavaSubclass : AbsentJavaBase<String> {
                open fun process(item: String)
                fun process(item: Int)
                open fun run()
                fun run(times: Int)
                fun prim(x: Int?)
                open fun <S : String> bounded(x: S)
                fun <S : Number> bounded(x: S)
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

    @Test
    fun `should not look up the built-in types through the class loader`() {
        // given
        val missingClasses = Collections.synchronizedList(mutableListOf<String>())
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = try {
                super.loadClass(name, resolve)
            } catch (e: ClassNotFoundException) {
                missingClasses += name
                throw e
            }
        }
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.test.KeyedMap")

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.KeyedMap [page 1/1]
            abstract class KeyedMap : Map<String, Int> {
                override val keys: Set<String>
                override val entries: Set<Map.Entry<String, Int>>
            }
        """.trimIndent()
        assert(missingClasses.none { it.startsWith("kotlin.") })
    }

}
