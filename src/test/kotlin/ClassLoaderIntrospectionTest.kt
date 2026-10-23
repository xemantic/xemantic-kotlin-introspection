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

import com.xemantic.kotlin.introspection.friendmodule.FriendModuleSubclass
import com.xemantic.kotlin.introspection.othermodule.InternalBase
import com.xemantic.kotlin.introspection.test.TestClass
import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.sameAs
import org.junit.jupiter.api.io.TempDir
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
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.Attributes
import java.util.jar.JarEntry
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
     * Copies the isolated test fixtures, without the class files of the [absentClassFiles],
     * and the ones [isAbsent] tells by their file names, to the [name] directory,
     * transforming the class bytes by their file names.
     */
    private fun isolatedTestFixturesCopy(
        name: String,
        vararg absentClassFiles: String,
        isAbsent: (fileName: String) -> Boolean = { false },
        transform: (fileName: String, bytes: ByteArray) -> ByteArray = { _, bytes -> bytes }
    ): Path {
        val root = tempDir.resolve(name)
        isolatedTestFixtures.filter { it.exists() }.forEach { dir ->
            val files = dir.walkTopDown().filter { it.isFile && it.name !in absentClassFiles && !isAbsent(it.name) }
            files.forEach { file ->
                val target = root.resolve(file.relativeTo(dir).path).toFile()
                target.parentFile.mkdirs()
                target.writeBytes(transform(file.name, file.readBytes()))
            }
        }
        return root
    }

    /**
     * An introspector of the isolated test fixtures without the class `Absent`,
     * which they refer to, without the nested classes named `Gone`, and without the other [absentClassFiles].
     */
    private fun introspectorWithoutAbsentClass(vararg absentClassFiles: String): KotlinIntrospector {
        val root = isolatedTestFixturesCopy(
            "withoutAbsent",
            "Absent.class",
            *absentClassFiles,
            isAbsent = { it.endsWith("\$Gone.class") }
        )
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
                override fun take(nested: DollarHolder.`Dollar${'$'}Nested`)
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
    fun `should not mark parameter inheriting default value of overload matching only on type parameter unknown through class missing from the classpath`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentArgumentDefaultsLeaf"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentArgumentDefaultsLeaf [page 1/1]
            class AbsentArgumentDefaultsLeaf : AbsentArgumentJavaMid {
                override fun f(x: String, y: Int)
                override fun g(x: Absent, y: Int = …)
                override fun h(x: String, y: List<*>, z: Int)
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
                inner class InnerSubclass : AbsentJavaOuter<String>.Inner<Int>
                inner class ShadowingSubclass : AbsentJavaOuter<String>.Shadowing<Int>
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
            inner class InnerSubclass : AbsentJavaOuter<String>.Inner<Int> {
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
            inner class ShadowingSubclass : AbsentJavaOuter<String>.Shadowing<Int> {
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
    fun `should mark overrides of Java superclass methods taking nested class whose outer class is missing from the classpath`() {
        // given
        // like in a shaded or shrunk jar, the nested class is present, while its outer class is not
        val introspector = introspectorWithoutAbsentClass("DollarHolder.class")

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
                override fun take(nested: DollarHolder.`Dollar${'$'}Nested`)
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
    fun `should list nested classes which cannot be loaded, as read from their class files`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentNestedHolder"
        )

        // then
        // the private one is still not listed, and the one without a class file cannot be listed at all
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentNestedHolder [page 1/1]
            class AbsentNestedHolder {
                class Present
                class AbsentSubclass : Absent
            }
        """.trimIndent()
    }

    @Test
    fun `should list sealed subclasses which cannot be loaded, as read from their class files`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentSealed"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentSealed [page 1/1]
            sealed interface AbsentSealed {
                class AbsentSubclass : Absent, AbsentSealed
                class Present : AbsentSealed
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve nested class which cannot be loaded, as read from its class file`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentNestedHolder.AbsentSubclass"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentNestedHolder.AbsentSubclass [page 1/1]
            class AbsentSubclass : Absent
        """.trimIndent()
    }

    @Test
    fun `should mark overrides inherited through class which cannot be loaded`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentLeaf"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentLeaf [page 1/1]
            class AbsentLeaf : AbsentMid {
                override fun foo()
                fun bar()
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve class by name with slashes`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com/xemantic/kotlin/introspection/isolated/AbsentNestedHolder"
        )

        // then
        assert(signature == null)
    }

    @Test
    fun `should not resolve nested class without class file`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentNestedHolder.Gone"
        )

        // then
        assert(signature == null)
    }

    /**
     * A class loader counting the classes requested by their names.
     */
    private class CountingClassLoader(
        parent: ClassLoader,
        private val failingOnce: Set<String> = emptySet()
    ) : ClassLoader(parent) {
        val loads = ConcurrentHashMap<String, Int>()
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            val count = loads.merge(name, 1, Int::plus)
            // like a transient failure, unlike a class missing from the classpath
            if (name in failingOnce && count == 1) throw LinkageError("transient failure of $name")
            return super.loadClass(name, resolve)
        }
    }

    @Test
    fun `should load each nested class once for repeated and paged resolves`() {
        // given
        val classLoader = CountingClassLoader(javaClass.classLoader)
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)
        val holders = listOf(
            // sealed subclasses, also nested in the companion object, the nested classes of the companion object
            "com.xemantic.kotlin.introspection.test.CompanionNestedSealedHolder",
            // the inner classes, referring to the type parameters of their outer classes
            "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder",
            "com.xemantic.kotlin.introspection.test.InnerOfInnerHolder.Mid"
        )

        // when
        val pages = holders.flatMap { holder ->
            listOf(
                introspector.resolve(holder),
                introspector.resolve(holder),
                introspector.resolve(holder, page = 1, pageSize = 1),
                introspector.resolve(holder, page = 2, pageSize = 1)
            )
        }

        // then
        assert(pages.all { it != null })
        // the class names of the declarations within the holders, like Holder.Sub or Holder${'$'}Sub
        val nestedLoads = classLoader.loads.filterKeys { name ->
            holders.any { holder -> name != holder && name.replace('$', '.').startsWith("$holder.") }
        }
        assert(nestedLoads.isNotEmpty())
        assert(nestedLoads.values.all { it == 1 })
        // only by their binary names, as known from the metadata, without trying the other ones
        assert(nestedLoads.keys.none { name -> '.' in name.removePrefix("com.xemantic.kotlin.introspection.test.") })
    }

    @Test
    fun `should load nested class again after a transient linkage error`() {
        // given
        val nested = "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder\$Sorted"
        val classLoader = CountingClassLoader(javaClass.classLoader, failingOnce = setOf(nested))
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signatures = List(2) {
            introspector.resolve("com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder")
        }

        // then
        // read from the class file the first time, and loaded the second time
        assert(signatures.all { it != null && "class Sorted<T>" in it })
        assert(classLoader.loads[nested] == 2)
    }

    @Test
    fun `should mark overrides of members of Kotlin superclass without metadata`() {
        // given
        val root = isolatedTestFixturesCopy("stripped") { fileName, bytes ->
            when (fileName) {
                "MetadataStrippedBase.class" -> shrunk(withoutMetadata(bytes))
                "MetadataStrippedSubclass.class" -> shrunk(bytes)
                else -> bytes
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
        val root = isolatedTestFixturesCopy("synthetic") { fileName, bytes ->
            if (fileName == "SyntheticNestedHolder\$\$serializer.class") synthetic(bytes) else bytes
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

    @Test
    fun `should not list declarations hidden since an older Kotlin version`() {
        // given
        val root = isolatedTestFixturesCopy("hiddenSince") { fileName, bytes ->
            if (fileName == "HiddenSinceHolder.class") {
                hiddenSince(
                    bytes,
                    "hiddenSincePast" to "1.0",
                    // a property is annotated through the synthetic method for its annotations
                    "getHiddenSinceProperty\$annotations" to "1.0",
                    "hiddenSinceFuture" to "${KotlinVersion.CURRENT.major + 1}.0"
                )
            } else bytes
        }
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.HiddenSinceHolder")

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.HiddenSinceHolder [page 1/1]
            class HiddenSinceHolder {
                fun hiddenSinceFuture(): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should tell declarations hidden since a Kotlin version by the standard library of the class loader`() {
        // given
        val root = tempDir.resolve("olderStdlib")
        isolatedTestFixtures.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val target = root.resolve(file.relativeTo(dir).path).toFile()
                target.parentFile.mkdirs()
                target.writeBytes(
                    if (file.name == "HiddenSinceHolder.class") {
                        hiddenSince(file.readBytes(), "hiddenSincePast" to "1.0", "hiddenSinceFuture" to "2.0")
                    } else {
                        file.readBytes()
                    }
                )
            }
        }
        // the KotlinVersion of a standard library 1.9.0 in a directory, which has no manifest telling its version
        listOf("KotlinVersion", "KotlinVersion\$Companion", "KotlinVersionCurrentValue").forEach { name ->
            val bytes = KotlinVersion::class.java.getResourceAsStream("/kotlin/$name.class")!!.use { it.readBytes() }
            val target = root.resolve("kotlin/$name.class").toFile()
            target.parentFile.mkdirs()
            target.writeBytes(if (name == "KotlinVersionCurrentValue") withKotlinVersion(bytes, 1, 9, 0) else bytes)
        }
        // the standard library of the root is preferred, like by the isolated class loader of a plugin
        val classLoader = object : URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name.startsWith("kotlin.KotlinVersion")) {
                    synchronized(getClassLoadingLock(name)) { findLoadedClass(name) ?: findClass(name) }
                } else {
                    super.loadClass(name, resolve)
                }
        }
        val introspector = KotlinIntrospector(classLoader)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.HiddenSinceHolder")

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.HiddenSinceHolder [page 1/1]
            class HiddenSinceHolder {
                @Deprecated("replaced") val hiddenSinceProperty: Int
                fun hiddenSinceFuture(): Int
            }
        """.trimIndent()
    }

    /**
     * Replaces the version of the standard library, which the `KotlinVersionCurrentValue.get()`
     * returns as `KotlinVersion.CURRENT`, by the [major], [minor] and [patch] one.
     */
    private fun withKotlinVersion(bytes: ByteArray, major: Int, minor: Int, patch: Int): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                val visitor = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name != "get") return visitor
                val versions = ArrayDeque(listOf(major, minor, patch))
                // the version numbers pushed as the constructor arguments of KotlinVersion, and nothing else
                fun nextVersion(): Int = versions.removeFirstOrNull()
                    ?: error("KotlinVersionCurrentValue.get() pushes more than the 3 version numbers, update the test")
                return object : MethodVisitor(Opcodes.ASM9, visitor) {
                    override fun visitInsn(opcode: Int) {
                        if (opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5) {
                            super.visitIntInsn(Opcodes.BIPUSH, nextVersion())
                        } else {
                            super.visitInsn(opcode)
                        }
                    }

                    override fun visitIntInsn(opcode: Int, operand: Int) {
                        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                            super.visitIntInsn(Opcodes.BIPUSH, nextVersion())
                        } else {
                            super.visitIntInsn(opcode, operand)
                        }
                    }

                    override fun visitLdcInsn(value: Any?) {
                        if (value is Int) super.visitIntInsn(Opcodes.BIPUSH, nextVersion()) else super.visitLdcInsn(value)
                    }

                    override fun visitEnd() {
                        check(versions.isEmpty()) {
                            "KotlinVersionCurrentValue.get() pushes fewer than the 3 version numbers, update the test"
                        }
                        super.visitEnd()
                    }
                }
            }
        }, 0)
        return writer.toByteArray()
    }

    /**
     * Annotates the methods of the class with `@DeprecatedSinceKotlin(hiddenSince = …)`,
     * which only the standard library can declare, by the method name.
     */
    private fun hiddenSince(bytes: ByteArray, vararg versionsByMethod: Pair<String, String>): ByteArray {
        val versions = versionsByMethod.toMap()
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                val visitor = super.visitMethod(access, name, descriptor, signature, exceptions)
                versions[name]?.let { version ->
                    visitor.visitAnnotation("Lkotlin/DeprecatedSinceKotlin;", true).apply {
                        visit("hiddenSince", version)
                        visitEnd()
                    }
                }
                return visitor
            }
        }, 0)
        return writer.toByteArray()
    }

    /**
     * Adds the `ACC_SYNTHETIC` flag to the class, like a bytecode tool might,
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
    private fun shrunk(bytes: ByteArray): ByteArray = withoutMethods(bytes) { name, descriptor ->
        (name == "shrunk" && descriptor == "(I)V") || name == "shrunkAlone"
    }

    @Test
    fun `should mark overrides of other Java methods when generic signature of one method mismatches its descriptor`() {
        // given
        val root = isolatedTestFixturesCopy("mismatchedSignature", "Absent.class") { fileName, bytes ->
            if (fileName == "AbsentJavaBase.class") {
                // a malformed class file, declaring an array of T as the type of the parameter taking T
                withMethodSignature(bytes, "process", "(Ljava/lang/Object;)V", "([TT;)V")
            } else bytes
        }
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentJavaSubclass"
        )

        // then
        // only the method of the mismatched signature is matched by its descriptor, erasing T to Object,
        // so its override, implicitly open, is not told
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentJavaSubclass [page 1/1]
            class AbsentJavaSubclass : AbsentJavaBase<String> {
                open fun process(item: String)
                fun process(item: Int)
                override fun run()
                fun run(times: Int)
                fun prim(x: Int?)
                override fun <S : String> bounded(x: S)
                fun <S : Number> bounded(x: S)
                override fun take(nested: DollarHolder.`Dollar${'$'}Nested`)
            }
        """.trimIndent()
    }

    /**
     * Replaces the generic signature of the method of the [name] and the [descriptor] in the class bytes.
     */
    private fun withMethodSignature(bytes: ByteArray, name: String, descriptor: String, signature: String): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                methodName: String,
                methodDescriptor: String,
                methodSignature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? = super.visitMethod(
                access,
                methodName,
                methodDescriptor,
                if (methodName == name && methodDescriptor == descriptor) signature else methodSignature,
                exceptions
            )
        }, 0)
        return writer.toByteArray()
    }

    /**
     * An introspector of the isolated test fixtures without the class `Absent`,
     * whose class loader loads every class, but serves the class files of the [unreadableClasses]
     * with an unsupported major version, like a class file too new for ASM.
     */
    private fun introspectorWithUnreadableClassFiles(vararg unreadableClasses: String): KotlinIntrospector {
        val root = isolatedTestFixturesCopy("unreadable", "Absent.class")
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
    fun `should mark overrides of internal members of friend module by class whose class file cannot be parsed`() {
        // given
        val unreadableResource = "com/xemantic/kotlin/introspection/friendmodule/FriendModuleSubclass.class"
        // the class is still loaded by the parent class loader, only its class file is served unparsable
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun getResourceAsStream(name: String): InputStream? {
                val stream = super.getResourceAsStream(name)
                return if (stream != null && name == unreadableResource) {
                    val bytes = stream.use { it.readBytes() }
                    // the major version, following the magic number and the minor version
                    bytes[6] = 0x7F
                    bytes[7] = 0x7F
                    bytes.inputStream()
                } else stream
            }
        }
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.friendmodule.FriendModuleSubclass")

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.friendmodule.FriendModuleSubclass [page 1/1]
            class FriendModuleSubclass : com.xemantic.kotlin.introspection.othermodule.InternalBase {
                override val state: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should tell overrides of internal members only of the same module name by classes which can be neither read nor reflected`() {
        // given
        val root = tempDir.resolve("unreflectable")
        listOf(InternalBase::class.java, FriendModuleSubclass::class.java).forEach { fixture ->
            val dir = File(fixture.protectionDomain.codeSource.location.toURI())
            dir.walkTopDown()
                .filter { it.isFile && it.name != "OtherModuleMarker.class" }
                .forEach { it.copyTo(root.resolve(it.relativeTo(dir).path).toFile(), overwrite = true) }
        }
        val unreadableResources = setOf(
            "com/xemantic/kotlin/introspection/othermodule/UnreflectableSameModuleSubclass.class",
            "com/xemantic/kotlin/introspection/friendmodule/UnreflectableFriendModuleSubclass.class"
        )
        // loading the fixtures of both modules itself, without the marker class, which their methods refer to
        val classLoader = object : URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name.startsWith("com.xemantic.kotlin.introspection.othermodule.") ||
                    name.startsWith("com.xemantic.kotlin.introspection.friendmodule.")
                ) {
                    synchronized(getClassLoadingLock(name)) { findLoadedClass(name) ?: findClass(name) }
                } else {
                    super.loadClass(name, resolve)
                }

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
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val sameModule = introspector.resolve(
            "com.xemantic.kotlin.introspection.othermodule.UnreflectableSameModuleSubclass"
        )
        val friendModule = introspector.resolve(
            "com.xemantic.kotlin.introspection.friendmodule.UnreflectableFriendModuleSubclass"
        )

        // then
        // a module of the same name is assumed to be the same module
        sameModule sameAsKotlin """
            // com.xemantic.kotlin.introspection.othermodule.UnreflectableSameModuleSubclass [page 1/1]
            class UnreflectableSameModuleSubclass : InternalBase {
                override val state: Int
                fun take(marker: OtherModuleMarker)
            }
        """.trimIndent()
        // while a friend module is told only by the bridge, which cannot be seen
        friendModule sameAsKotlin """
            // com.xemantic.kotlin.introspection.friendmodule.UnreflectableFriendModuleSubclass [page 1/1]
            class UnreflectableFriendModuleSubclass : com.xemantic.kotlin.introspection.othermodule.InternalBase {
                open val state: Int
                fun take(marker: com.xemantic.kotlin.introspection.othermodule.OtherModuleMarker)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark shrunk overrides of internal members of superclass of the same module named by default`() {
        // given
        val shrunkResource = "com/xemantic/kotlin/introspection/defaultmodule/DefaultModuleSubclass.class"
        // like R8 removing both the override of clear and its bridge clear$main, keeping the metadata
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun getResourceAsStream(name: String): InputStream? {
                val stream = super.getResourceAsStream(name)
                return if (stream != null && name == shrunkResource) {
                    val writer = ClassWriter(0)
                    ClassReader(stream.use { it.readBytes() }).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?
                        ): MethodVisitor? = if (name == "clear" || name == "clear\$main") {
                            null
                        } else {
                            super.visitMethod(access, name, descriptor, signature, exceptions)
                        }
                    }, 0)
                    writer.toByteArray().inputStream()
                } else stream
            }
        }
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.defaultmodule.DefaultModuleSubclass")

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultmodule.DefaultModuleSubclass [page 1/1]
            class DefaultModuleSubclass : DefaultModuleBase {
                override val state: Int
                override fun clear()
            }
        """.trimIndent()
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
                override fun take(nested: DollarHolder.`Dollar${'$'}Nested`)
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
                open fun take(nested: DollarHolder.`Dollar${'$'}Nested`)
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
    fun `should list same-named extensions of built-in type from different files sorted by declaring file regardless of scan order`() {
        // given
        // the standard library files declaring Char.titlecase(), in the reverse order of their names,
        // which the standard library jar itself keeps sorted
        val stdlibFiles = listOf("kotlin/text/CharsKt__CharKt.class", "kotlin/text/CharsKt__CharJVMKt.class")
        val reversedJar = tempDir.resolve("reversed.jar")
        JarOutputStream(reversedJar.outputStream()).use { jar ->
            stdlibFiles.forEach { file ->
                jar.putNextEntry(JarEntry(file))
                Unit::class.java.classLoader.getResourceAsStream(file)!!.use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        // without the standard library jar, which would shadow the reversed one
        val classLoader = URLClassLoader(arrayOf(reversedJar.toUri().toURL()), ClassLoader.getPlatformClassLoader())
        val introspector = KotlinIntrospector(classLoader)

        // when
        val signature = introspector.resolve("kotlin.Char", pageSize = 10000)

        // then
        assert(
            signature!!.lines().filter { "fun titlecase(" in it } == listOf(
                "    fun titlecase(locale: java.util.Locale): String",
                "    fun titlecase(): String"
            )
        )
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

    @Test
    fun `should read DefaultImpls class files of interfaces without loading the classes`() {
        // given
        val requestedClasses = Collections.synchronizedList(mutableListOf<String>())
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                requestedClasses += name
                return super.loadClass(name, resolve)
            }
        }
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        // the JVM methods of an interface owning an overload are read, also the ones relocated to DefaultImpls,
        // which most interfaces do not have, compiled with the default -jvm-default
        val greeter = introspector.resolve("com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsGreeter")
        val processor = introspector.resolve("com.xemantic.kotlin.introspection.test.OverloadingProcessorInterface")

        // then
        greeter sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsGreeter [page 1/1]
            interface DefaultImplsGreeter : DefaultImplsBase {
                open override val label: String
                open override fun greet(name: String): String
            }
        """.trimIndent()
        processor sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OverloadingProcessorInterface [page 1/1]
            interface OverloadingProcessorInterface : Processor<String> {
                fun process(item: Int)
            }
        """.trimIndent()
        assert(requestedClasses.none { it.endsWith("\$DefaultImpls") })
    }

    /**
     * Removes the JVM methods which are [removed], by their names and descriptors, from the class bytes, like R8 might.
     */
    private fun withoutMethods(bytes: ByteArray, removed: (name: String, descriptor: String) -> Boolean): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? = if (removed(name, descriptor)) {
                null
            } else {
                super.visitMethod(access, name, descriptor, signature, exceptions)
            }
        }, 0)
        return writer.toByteArray()
    }

    @Test
    fun `should mark shrunk overload of interface member as override when its DefaultImpls can be neither read nor loaded`() {
        // given
        val interfaceResource = "com/xemantic/kotlin/introspection/defaultimpls/DefaultImplsOverloader.class"
        val defaultImplsName = "com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsOverloader\$DefaultImpls"
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == defaultImplsName) throw ClassNotFoundException(name) else super.loadClass(name, resolve)

            override fun getResourceAsStream(name: String): InputStream? {
                val stream = super.getResourceAsStream(name) ?: return null
                val bytes = stream.use { it.readBytes() }
                return when (name) {
                    // the JVM method of the overload is removed, like by R8
                    interfaceResource -> withoutMethods(bytes) { methodName, descriptor ->
                        methodName == "greet" && descriptor == "(I)Ljava/lang/String;"
                    }
                    // the major version, following the magic number and the minor version, unsupported
                    defaultImplsName.replace('.', '/') + ".class" -> bytes.also {
                        it[6] = 0x7F
                        it[7] = 0x7F
                    }
                    else -> bytes
                }.inputStream()
            }
        }
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsOverloader")

        // then
        // the JVM methods of the interface itself still tell the overload as shrunk,
        // an override with a default body, which a subinterface can still override
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsOverloader [page 1/1]
            interface DefaultImplsOverloader : DefaultImplsBase {
                open override fun greet(times: Int): String
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of other Java methods when generic signature or descriptor of one method is malformed`() {
        // given
        val root = isolatedTestFixturesCopy("malformedMethods", "Absent.class")
        // the class is defined from the intact class file, while the one served as a resource is malformed
        val classLoader = object : URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader) {
            override fun getResourceAsStream(name: String): InputStream? {
                val stream = super.getResourceAsStream(name) ?: return null
                if (!name.endsWith("/AbsentJavaBase.class")) return stream
                val bytes = stream.use { it.readBytes() }
                return withMethodDescriptor(
                    withMethodSignature(bytes, "process", "(Ljava/lang/Object;)V", "(!)V"),
                    "run", "()V", "(!)V"
                ).inputStream()
            }
        }
        val introspector = KotlinIntrospector(classLoader)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.AbsentJavaSubclass")

        // then
        // the method of the malformed signature is matched by its descriptor, erasing T to Object,
        // and the method of the malformed descriptor is unknown, so their overrides, implicitly open, are not told
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentJavaSubclass [page 1/1]
            class AbsentJavaSubclass : AbsentJavaBase<String> {
                open fun process(item: String)
                fun process(item: Int)
                open fun run()
                fun run(times: Int)
                fun prim(x: Int?)
                override fun <S : String> bounded(x: S)
                fun <S : Number> bounded(x: S)
                override fun take(nested: DollarHolder.`Dollar${'$'}Nested`)
            }
        """.trimIndent()
    }

    /**
     * Replaces the descriptor of the method of the [name] and the [descriptor] in the class bytes.
     */
    private fun withMethodDescriptor(bytes: ByteArray, name: String, descriptor: String, replacement: String): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                methodName: String,
                methodDescriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? = super.visitMethod(
                access,
                methodName,
                if (methodName == name && methodDescriptor == descriptor) replacement else methodDescriptor,
                signature,
                exceptions
            )
        }, 0)
        return writer.toByteArray()
    }

    @Test
    fun `should mark override of Java method taking dollar-named nested class missing from the classpath`() {
        // given
        val introspector = introspectorWithoutAbsentClass("DollarHolder.class", "DollarHolder\$\$Named.class")

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.AbsentDollarJavaSubclass")

        // then
        // a missing class is assumed to be nested at every `$`, except for one starting a simple name
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentDollarJavaSubclass [page 1/1]
            class AbsentDollarJavaSubclass : AbsentDollarJavaBase {
                override fun take(named: DollarHolder.`${'$'}Named`)
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve class through class loader throwing when looking up resources`() {
        // given
        // like a closed or a restricted class loader
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun getResource(name: String): java.net.URL = throw SecurityException("no resources: $name")
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
    }

    @Test
    fun `should not list hidden nested class whose class file is not served as a resource`() {
        // given
        val root = isolatedTestFixturesCopy("unservedNested")
        // like an in-memory class loader, defining the class without serving its class file
        val classLoader = object : URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader) {
            override fun getResourceAsStream(name: String): InputStream? =
                if (name.endsWith("HiddenNestedHolder\$Hidden.class")) null else super.getResourceAsStream(name)
        }
        val introspector = KotlinIntrospector(classLoader)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.HiddenNestedHolder")

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.HiddenNestedHolder [page 1/1]
            class HiddenNestedHolder {
                class Visible
            }
        """.trimIndent()
    }

    /**
     * A class loader defining the nested classes of `DeprecatedNestedHolder` without serving their class files,
     * like an in-memory class loader.
     */
    private fun unservedDeprecatedNestedClassLoader(): ClassLoader {
        val root = isolatedTestFixturesCopy("unservedDeprecatedNested")
        return object : URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader) {
            override fun getResourceAsStream(name: String): InputStream? =
                if ("DeprecatedNestedHolder$" in name) null else super.getResourceAsStream(name)
        }
    }

    @Test
    fun `should render deprecation of nested class whose class file is not served as a resource`() {
        // given
        val introspector = KotlinIntrospector(unservedDeprecatedNestedClassLoader())

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.DeprecatedNestedHolder")

        // then - the hidden companion object is not listed either
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.DeprecatedNestedHolder [page 1/1]
            class DeprecatedNestedHolder {
                @Deprecated("Old") class Old
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve member of hidden companion object whose class file is not served as a resource`() {
        // given
        val introspector = KotlinIntrospector(unservedDeprecatedNestedClassLoader())

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.DeprecatedNestedHolder.create")

        // then
        assert(signature == null)
    }

    @Test
    fun `should mark override of Java method taking dollar-named nested class without outer class and class file`() {
        // given
        // like in a shaded or shrunk jar, the nested class is present, while its outer class is not
        val root = isolatedTestFixturesCopy("dollarNamed", "Absent.class", "DollarHolder.class")
        // like an in-memory class loader, defining the nested class without serving its class file
        val classLoader = object : URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader) {
            override fun getResourceAsStream(name: String): InputStream? =
                if (name.endsWith("DollarHolder\$\$Named.class")) null else super.getResourceAsStream(name)
        }
        val introspector = KotlinIntrospector(classLoader)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.AbsentDollarJavaSubclass")

        // then
        // the `$` starting the simple name is told from the one separating it from the name of the outer class
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentDollarJavaSubclass [page 1/1]
            class AbsentDollarJavaSubclass : AbsentDollarJavaBase {
                override fun take(named: DollarHolder.`${'$'}Named`)
            }
        """.trimIndent()
    }

}
