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
     * An introspector of the isolated test fixtures without the class `Absent`,
     * which they refer to, without the nested classes named `Gone`, and without the other [absentClassFiles].
     */
    private fun introspectorWithoutAbsentClass(vararg absentClassFiles: String): KotlinIntrospector {
        val root = tempDir.resolve("withoutAbsent")
        isolatedTestFixtures.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown()
                .filter {
                    it.isFile && it.name != "Absent.class" && !it.name.endsWith("\$Gone.class") &&
                        it.name !in absentClassFiles
                }
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

    @Test
    fun `should not list declarations hidden since an older Kotlin version`() {
        // given
        val root = tempDir.resolve("hiddenSince")
        isolatedTestFixtures.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val target = root.resolve(file.relativeTo(dir).path).toFile()
                target.parentFile.mkdirs()
                target.writeBytes(
                    if (file.name == "HiddenSinceHolder.class") {
                        hiddenSince(
                            file.readBytes(),
                            "hiddenSincePast" to "1.0",
                            // a property is annotated through the synthetic method for its annotations
                            "getHiddenSinceProperty\$annotations" to "1.0",
                            "hiddenSinceFuture" to "${KotlinVersion.CURRENT.major + 1}.0"
                        )
                    } else {
                        file.readBytes()
                    }
                )
            }
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

    @Test
    fun `should mark overrides of other Java methods when generic signature of one method mismatches its descriptor`() {
        // given
        val root = tempDir.resolve("mismatchedSignature")
        isolatedTestFixtures.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.name != "Absent.class" }.forEach { file ->
                val target = root.resolve(file.relativeTo(dir).path).toFile()
                target.parentFile.mkdirs()
                target.writeBytes(
                    if (file.name == "AbsentJavaBase.class") {
                        // a malformed class file, declaring an array of T as the type of the parameter taking T
                        withMethodSignature(file.readBytes(), "process", "(Ljava/lang/Object;)V", "([TT;)V")
                    } else file.readBytes()
                )
            }
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
                override val label: String
                override fun greet(name: String): String
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

}
