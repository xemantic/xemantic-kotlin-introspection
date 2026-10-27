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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
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
import kotlin.io.path.writeBytes
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.jvm.KotlinModuleMetadata
import kotlin.metadata.jvm.moduleName
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

    // the types of the metadata are not relocated, so qualified, as not in the package of the relocated class
    private val relocatedListenerImplSignature = /* language=kotlin */ """
        // shaded.com.xemantic.kotlin.introspection.isolated.RelocatedListenerImpl [page 1/1]
        class RelocatedListenerImpl : com.xemantic.kotlin.introspection.isolated.RelocatedListener {
            override fun on(event: com.xemantic.kotlin.introspection.isolated.RelocatedEvent)
            fun on(event: com.xemantic.kotlin.introspection.isolated.RelocatedOther)
        }
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
    ): Path = testFixturesCopy(
        isolatedTestFixtures.filter { it.exists() }, tempDir.resolve(name),
        isAbsent = { it in absentClassFiles || isAbsent(it) }, transform = transform
    )

    @Test
    fun `should resolve and find class relocated without its metadata`() {
        // given
        // like by shading, relocating the binary name of the class, but not its name in the Kotlin metadata
        val root = isolatedTestFixturesCopy("relocated", "IsolatedTestClass.class")
        relocatedCopy(root, "IsolatedTestClass.class")
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("shaded.com.xemantic.kotlin.introspection.isolated.IsolatedTestClass")
        val searchResult = introspector.search("IsolatedTestClass")

        // then
        signature sameAsKotlin """
            // shaded.com.xemantic.kotlin.introspection.isolated.IsolatedTestClass [page 1/1]
            class IsolatedTestClass(val value: Int)
        """.trimIndent()
        // found by the name it is resolved by
        searchResult sameAs """
            // search results for "IsolatedTestClass" [page 1/1]
            shaded.com.xemantic.kotlin.introspection.isolated.IsolatedTestClass
        """.trimIndent()
    }

    @Test
    fun `should qualify relocated inner class with type parameters of its relocated outer class`() {
        // given
        val classFiles = arrayOf("RelocatedGenericOuter.class", "RelocatedGenericOuter\$Cursor.class")
        val root = isolatedTestFixturesCopy("relocatedInner", *classFiles)
        relocatedCopy(root, *classFiles)
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("shaded.com.xemantic.kotlin.introspection.isolated.RelocatedGenericOuter.Cursor")

        // then
        // the outer class is found by its relocated binary name, telling the names of its type parameters
        signature sameAsKotlin """
            // shaded.com.xemantic.kotlin.introspection.isolated.RelocatedGenericOuter.Cursor [page 1/1]
            inner class RelocatedGenericOuter<K>.Cursor : Comparable<K> {
                override operator fun compareTo(other: K): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not take overload of relocated inner class with bounded type parameter of its relocated outer class for override`() {
        // given
        val classFiles = arrayOf("RelocatedBoundedOuter.class", "RelocatedBoundedOuter\$Comparing.class")
        val root = isolatedTestFixturesCopy("relocatedBoundedInner", *classFiles)
        relocatedCopy(root, *classFiles)
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve(
            "shaded.com.xemantic.kotlin.introspection.isolated.RelocatedBoundedOuter.Comparing"
        )

        // then
        // the outer class is found by its relocated binary name, telling the bound K is erased to,
        // so that compare(Number, Number) does not override compare(String, String) of Comparator<String>
        signature sameAsKotlin """
            // shaded.com.xemantic.kotlin.introspection.isolated.RelocatedBoundedOuter.Comparing [page 1/1]
            inner class RelocatedBoundedOuter<K>.Comparing : java.util.Comparator<String> {
                override fun compare(a: String, b: String): Int
                fun compare(a: K, b: K): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should tell override of member of relocated supertype of relocated class`() {
        // given
        val classFiles = arrayOf("RelocatedBase.class", "RelocatedDerived.class")
        val root = isolatedTestFixturesCopy("relocatedSupertype", *classFiles)
        relocatedCopy(root, *classFiles)
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("shaded.com.xemantic.kotlin.introspection.isolated.RelocatedDerived")

        // then
        // the supertype in the package of the relocated class is found by its relocated binary name
        signature sameAsKotlin """
            // shaded.com.xemantic.kotlin.introspection.isolated.RelocatedDerived [page 1/1]
            class RelocatedDerived : com.xemantic.kotlin.introspection.isolated.RelocatedBase<String> {
                override fun accept(value: String)
                fun accept(value: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should tell override of method of relocated Java supertype taking relocated type`() {
        // given
        val classFiles = arrayOf(
            "RelocatedListener.class", "RelocatedListenerImpl.class", "RelocatedEvent.class", "RelocatedOther.class"
        )
        val root = isolatedTestFixturesCopy("relocatedJavaSupertype", *classFiles)
        relocatedCopy(root, *classFiles)
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("shaded.com.xemantic.kotlin.introspection.isolated.RelocatedListenerImpl")

        // then
        // the relocated parameter type of the Java method is the type of the metadata, which is not relocated,
        // and the JVM methods of the relocated class, taking the relocated types, are not taken for shrunk ones
        signature sameAsKotlin relocatedListenerImplSignature
    }

    @Test
    fun `should tell override of method of relocated Java supertype taking relocated type next to the original one`() {
        // given
        val classFiles = arrayOf(
            "RelocatedListener.class", "RelocatedListenerImpl.class", "RelocatedEvent.class", "RelocatedOther.class"
        )
        val shadedRoot = tempDir.resolve("relocatedJavaSupertypeNextToOriginal")
        relocatedCopy(shadedRoot, *classFiles)
        val root = isolatedTestFixturesCopy("originalJavaSupertype")
        val introspector = KotlinIntrospector(
            URLClassLoader(arrayOf(shadedRoot.toUri().toURL(), root.toUri().toURL()), javaClass.classLoader)
        )

        // when
        val signature = introspector.resolve("shaded.com.xemantic.kotlin.introspection.isolated.RelocatedListenerImpl")

        // then
        signature sameAsKotlin relocatedListenerImplSignature
    }

    @Test
    fun `should tell override of member of relocated Kotlin supertype through relocated Java superclass`() {
        // given
        val classFiles = arrayOf(
            "RelocatedBase.class", "RelocatedJavaMid.class", "RelocatedViaJavaImpl.class",
            "RelocatedEvent.class", "RelocatedOther.class"
        )
        val root = isolatedTestFixturesCopy("relocatedViaJava", *classFiles)
        relocatedCopy(root, *classFiles)
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("shaded.com.xemantic.kotlin.introspection.isolated.RelocatedViaJavaImpl")

        // then
        // the type argument of RelocatedBase, named by its relocated JVM name in the Java superclass,
        // is the class named by its metadata name in the metadata of the overriding function
        signature sameAsKotlin """
            // shaded.com.xemantic.kotlin.introspection.isolated.RelocatedViaJavaImpl [page 1/1]
            class RelocatedViaJavaImpl : com.xemantic.kotlin.introspection.isolated.RelocatedJavaMid {
                override fun accept(value: com.xemantic.kotlin.introspection.isolated.RelocatedEvent)
                fun accept(value: com.xemantic.kotlin.introspection.isolated.RelocatedOther)
            }
        """.trimIndent()
    }

    @Test
    fun `should not take type of real package starting like relocation prefix for relocated one`() {
        // given
        // relocated to com/com/xemantic/…, so that the prefix com/ is the first segment of the packages not relocated,
        // like com/xemantic/… of IsolatedTestClass and RelocatedHolder
        val classFiles = arrayOf("RelocatedPrefixListener.class", "RelocatedPrefixListenerImpl.class")
        val root = isolatedTestFixturesCopy("relocatedByPrefixOfRealPackage", *classFiles)
        relocatedCopy(root, *classFiles, prefix = "com/")
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("com.com.xemantic.kotlin.introspection.isolated.RelocatedPrefixListenerImpl")

        // then
        // neither the parameter type of the Java method, nor the ones of the JVM methods, are taken for relocated ones
        signature sameAsKotlin """
            // com.com.xemantic.kotlin.introspection.isolated.RelocatedPrefixListenerImpl [page 1/1]
            class RelocatedPrefixListenerImpl : com.xemantic.kotlin.introspection.isolated.RelocatedPrefixListener {
                override fun on(value: com.xemantic.kotlin.introspection.isolated.IsolatedTestClass)
                fun on(value: com.xemantic.kotlin.introspection.isolated.RelocatedHolder)
            }
        """.trimIndent()
    }

    /**
     * The signature of the `RelocatedListenerImpl`, which is not relocated itself, but its dependencies are,
     * see [relocatedCopy], named by the metadata names.
     */
    private val dependentListenerImplSignature = /* language=kotlin */ """
        // com.xemantic.kotlin.introspection.isolated.RelocatedListenerImpl [page 1/1]
        class RelocatedListenerImpl : RelocatedListener {
            override fun on(event: RelocatedEvent)
            fun on(event: RelocatedOther)
        }
    """.trimIndent()

    @Test
    fun `should tell override of method of relocated Java supertype of class which is not relocated`() {
        // given
        // like an app shading its dependencies, relocating them, and rewriting the references of its own classes to them,
        // but not the Kotlin metadata
        val dependencies = arrayOf("RelocatedListener.class", "RelocatedEvent.class", "RelocatedOther.class")
        val root = isolatedTestFixturesCopy("relocatedDependencies", *dependencies, "RelocatedListenerImpl.class")
        relocatedCopy(root, *dependencies, dependents = listOf("RelocatedListenerImpl.class"))
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.RelocatedListenerImpl")

        // then
        // the supertype, named by its metadata name, is found by its relocated name the class file refers to it by
        signature sameAsKotlin dependentListenerImplSignature
    }

    @Test
    fun `should not take overload for override of method of relocated Java supertype of class which is not relocated next to the original one`() {
        // given
        val dependencies = arrayOf("RelocatedListener.class", "RelocatedEvent.class", "RelocatedOther.class")
        val shadedRoot = tempDir.resolve("relocatedDependenciesNextToOriginal")
        relocatedCopy(shadedRoot, *dependencies, dependents = listOf("RelocatedListenerImpl.class"))
        val root = isolatedTestFixturesCopy("originalDependencies")
        val introspector = KotlinIntrospector(
            URLClassLoader(arrayOf(shadedRoot.toUri().toURL(), root.toUri().toURL()), javaClass.classLoader)
        )

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.RelocatedListenerImpl")

        // then
        // the JVM method of the overload, taking the relocated type, is not taken for a shrunk one
        signature sameAsKotlin dependentListenerImplSignature
    }

    @Test
    fun `should tell override of member of relocated Kotlin supertype taking relocated Java type argument through relocated Java superclass`() {
        // given
        val classFiles = arrayOf(
            "RelocatedBase.class", "RelocatedJavaArgMid.class", "RelocatedJavaArgImpl.class",
            "RelocatedJavaArg.class", "RelocatedOther.class"
        )
        val root = isolatedTestFixturesCopy("relocatedJavaArgument", *classFiles)
        relocatedCopy(root, *classFiles)
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("shaded.com.xemantic.kotlin.introspection.isolated.RelocatedJavaArgImpl")

        // then
        // the Java type argument of RelocatedBase, named by its relocated JVM name in the Java superclass,
        // is the class named by its metadata name in the metadata of the overriding function
        signature sameAsKotlin """
            // shaded.com.xemantic.kotlin.introspection.isolated.RelocatedJavaArgImpl [page 1/1]
            class RelocatedJavaArgImpl : com.xemantic.kotlin.introspection.isolated.RelocatedJavaArgMid {
                override fun accept(value: com.xemantic.kotlin.introspection.isolated.RelocatedJavaArg)
                fun accept(value: com.xemantic.kotlin.introspection.isolated.RelocatedOther)
            }
        """.trimIndent()
    }

    @Test
    fun `should tell override of generic method of relocated Java supertype bounded by relocated type`() {
        // given
        val classFiles = arrayOf(
            "RelocatedBoundListener.class", "RelocatedBoundListenerImpl.class", "RelocatedEvent.class", "RelocatedOther.class"
        )
        val root = isolatedTestFixturesCopy("relocatedBoundJavaSupertype", *classFiles)
        relocatedCopy(root, *classFiles)
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("shaded.com.xemantic.kotlin.introspection.isolated.RelocatedBoundListenerImpl")

        // then
        // the type variable is erased to its relocated bound, the type of the metadata, which is not relocated
        signature sameAsKotlin """
            // shaded.com.xemantic.kotlin.introspection.isolated.RelocatedBoundListenerImpl [page 1/1]
            class RelocatedBoundListenerImpl : com.xemantic.kotlin.introspection.isolated.RelocatedBoundListener {
                override fun <E : com.xemantic.kotlin.introspection.isolated.RelocatedEvent> on(event: E)
                fun <E : com.xemantic.kotlin.introspection.isolated.RelocatedOther> on(event: E)
            }
        """.trimIndent()
    }

    @Test
    fun `should list member extensions of relocated object from its relocated name`() {
        // given
        val root = isolatedTestFixturesCopy("relocatedObject", "RelocatedExtensions.class")
        relocatedCopy(root, "RelocatedExtensions.class")
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.RelocatedExtensionTarget.relocatedExtension")

        // then
        // imported from the object by the name it can be resolved by
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.RelocatedExtensionTarget.relocatedExtension [page 1/1]
            // from shaded.com.xemantic.kotlin.introspection.isolated.RelocatedExtensions
            fun RelocatedExtensionTarget.relocatedExtension(): Int
        """.trimIndent()
    }

    @Test
    fun `should list nested classes and companion object of relocated class`() {
        // given
        val classFiles = arrayOf("RelocatedHolder.class", "RelocatedHolder\$Nested.class", "RelocatedHolder\$Companion.class")
        val root = isolatedTestFixturesCopy("relocatedHolder", *classFiles)
        relocatedCopy(root, *classFiles)
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("shaded.com.xemantic.kotlin.introspection.isolated.RelocatedHolder")
        val member = introspector.resolve("shaded.com.xemantic.kotlin.introspection.isolated.RelocatedHolder.create")

        // then
        // looked up by their relocated names, unlike the ones of the metadata,
        // which the types still refer to, qualified, as they are not in the package of the relocated class
        signature sameAsKotlin """
            // shaded.com.xemantic.kotlin.introspection.isolated.RelocatedHolder [page 1/1]
            class RelocatedHolder {
                class Nested
                companion object {
                    fun create(): com.xemantic.kotlin.introspection.isolated.RelocatedHolder
                }
            }
        """.trimIndent()
        member sameAsKotlin """
            // shaded.com.xemantic.kotlin.introspection.isolated.RelocatedHolder.create [page 1/1]
            companion object {
                fun create(): com.xemantic.kotlin.introspection.isolated.RelocatedHolder
            }
        """.trimIndent()
    }

    @Test
    fun `should list sealed subclasses of relocated sealed class, open through its relocated open subclass`() {
        // given
        val classFiles = arrayOf(
            "RelocatedSealed.class",
            "RelocatedSealed\$Leaf.class",
            "RelocatedSealed\$ProtectedNested.class",
            "RelocatedOpenSubclass.class"
        )
        val root = isolatedTestFixturesCopy("relocatedSealed", *classFiles)
        relocatedCopy(root, *classFiles)
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("shaded.com.xemantic.kotlin.introspection.isolated.RelocatedSealed")
        val searchResult = introspector.search("ProtectedNested")

        // then
        // the protected declarations can be used by a subclass of the open subclass,
        // whose supertype is still the one of the metadata
        signature sameAsKotlin """
            // shaded.com.xemantic.kotlin.introspection.isolated.RelocatedSealed [page 1/1]
            sealed class RelocatedSealed {
                class Leaf : com.xemantic.kotlin.introspection.isolated.RelocatedSealed
                open class RelocatedOpenSubclass : com.xemantic.kotlin.introspection.isolated.RelocatedSealed
                protected class ProtectedNested
                protected fun hook()
            }
        """.trimIndent()
        // also by the classpath scan
        assert(searchResult?.lines()?.contains("shaded.com.xemantic.kotlin.introspection.isolated.RelocatedSealed.ProtectedNested") == true)
    }

    /**
     * A copy of the isolated test fixture class files named [fileNames], in the [root] directory,
     * relocated to the package prepended with the [prefix], like by shading, which does not rewrite their Kotlin metadata,
     * while it rewrites the references to the relocated classes, see [withRelocatedNames],
     * the other classes referred to as they are.
     *
     * @param dependents the class files which are not relocated themselves, but depend on the relocated ones,
     *   like the classes of an app shading its dependencies, copied to the [root] directory with their references rewritten.
     */
    private fun relocatedCopy(
        root: Path,
        vararg fileNames: String,
        prefix: String = "shaded/",
        dependents: List<String> = emptyList()
    ) {
        val relocatedNames = fileNames.associate {
            val name = "com/xemantic/kotlin/introspection/isolated/${it.removeSuffix(".class")}"
            name to "$prefix$name"
        }
        dependents.forEach { fileName ->
            val original = isolatedTestFixtures
                .map { it.resolve("com/xemantic/kotlin/introspection/isolated/$fileName") }
                .single { it.exists() }
            val dependent = root.resolve("com/xemantic/kotlin/introspection/isolated/$fileName")
            dependent.parent.createDirectories()
            dependent.writeBytes(withRelocatedNames(original.readBytes(), relocatedNames::get))
        }
        fileNames.forEach { fileName ->
            val original = isolatedTestFixtures
                .map { it.resolve("com/xemantic/kotlin/introspection/isolated/$fileName") }
                .single { it.exists() }
            val relocatedName = "${prefix}com/xemantic/kotlin/introspection/isolated/${fileName.removeSuffix(".class")}"
            val relocated = root.resolve("$relocatedName.class")
            relocated.parent.createDirectories()
            relocated.writeBytes(withRelocatedNames(original.readBytes(), relocatedNames::get))
        }
    }

    @Test
    fun `should find both class relocated without its metadata and the original one`() {
        // given
        // the relocated copy is scanned first
        val shadedRoot = tempDir.resolve("shadedFirst")
        relocatedCopy(shadedRoot, "IsolatedTestClass.class")
        val root = isolatedTestFixturesCopy("original")
        val introspector = KotlinIntrospector(
            URLClassLoader(arrayOf(shadedRoot.toUri().toURL(), root.toUri().toURL()), javaClass.classLoader)
        )

        // when
        val searchResult = introspector.search("IsolatedTestClass")

        // then
        searchResult sameAs """
            // search results for "IsolatedTestClass" [page 1/1]
            com.xemantic.kotlin.introspection.isolated.IsolatedTestClass
            shaded.com.xemantic.kotlin.introspection.isolated.IsolatedTestClass
        """.trimIndent()
    }

    @Test
    fun `should neither resolve nor find class relocated without its metadata nested in internal class`() {
        // given
        val root = isolatedTestFixturesCopy(
            "relocatedNested",
            "RelocatedInternalOuter.class",
            "RelocatedInternalOuter\$PublicNested.class"
        )
        relocatedCopy(root, "RelocatedInternalOuter.class", "RelocatedInternalOuter\$PublicNested.class")
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("shaded.com.xemantic.kotlin.introspection.isolated.RelocatedInternalOuter.PublicNested")
        val searchResult = introspector.search("PublicNested")

        // then
        // the outer class is found by its relocated binary name, telling it is internal
        assert(signature == null)
        assert(searchResult?.contains("RelocatedInternalOuter") != true)
    }

    /**
     * The class bytes with the class renamed to the [internalName], like `com/example/Foo`,
     * referring to the other classes as they are.
     */
    private fun withClassName(bytes: ByteArray, internalName: String): ByteArray {
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
                super.visit(version, access, internalName, signature, superName, interfaces)
            }
        }, 0)
        return writer.toByteArray()
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
    fun `should list Java override of JVM-only member through its bridge once when another method refers to class missing from the classpath`() {
        // given
        assumeTrue(runCatching { List::class.java.getMethod("addFirst", Any::class.java) }.isSuccess, "JDK 21+")
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentFirstListSubclass.addFirst"
        )

        // then - the addFirst(Comparable), overriding the addFirst(Object) of java.util.List by its bridge, is not listed next to it,
        // as kotlin-reflect cannot describe the members of the Java class without Absent, so that the JVM-only member stands for it
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentFirstListSubclass.addFirst [page 1/1]
            // inherited from kotlin.collections.MutableList
            open fun addFirst(arg0: E)
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
        // the one without a class file cannot be listed, but is still told, as a when expression has to cover it
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentSealed [page 1/1]
            sealed interface AbsentSealed {
                class AbsentSubclass : Absent, AbsentSealed
                class Present : AbsentSealed
                // sealed subclass not found: com.xemantic.kotlin.introspection.isolated.AbsentSealed.Gone
                class NotASubclass
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve nested class of outer class which cannot be loaded, as read from its class file`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentOuter.Nested"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentOuter.Nested [page 1/1]
            class Nested {
                fun f()
            }
        """.trimIndent()
    }

    /**
     * An introspector of the isolated test fixtures without the class `Absent`,
     * and without the Kotlin metadata of the [strippedClassFiles].
     */
    private fun introspectorWithoutMetadata(vararg strippedClassFiles: String): KotlinIntrospector {
        val root = tempDir.resolve("withoutMetadata")
        isolatedTestFixtures.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.name != "Absent.class" }.forEach { file ->
                val target = root.resolve(file.relativeTo(dir).path).toFile()
                target.parentFile.mkdirs()
                target.writeBytes(
                    if (file.name in strippedClassFiles) withoutMetadata(file.readBytes()) else file.readBytes()
                )
            }
        }
        return KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))
    }

    @Test
    fun `should neither resolve nor find nested class of outer class without metadata`() {
        // given
        val introspector = introspectorWithoutMetadata("StrippedOuter.class")

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.StrippedOuter.Nested")
        val searchResult = introspector.search("Nested", pageSize = 1000)

        // then
        // the outer class might be private, as its class file is there, but cannot be read
        assert(signature == null)
        assert(searchResult?.contains("StrippedOuter") != true)
    }

    @Test
    fun `should neither resolve nor find nested class of outer class whose class file cannot be parsed`() {
        // given
        val root = isolatedTestFixturesCopy("corruptOuter") { fileName, bytes ->
            if (fileName == "MissingOuter.class") bytes.copyOf(16) else bytes
        }
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.MissingOuter.Nested")
        val searchResult = introspector.search("Nested", pageSize = 1000)

        // then
        // the outer class might be private, as its class file is there, but cannot be read, also by the classpath scan
        assert(signature == null)
        assert(searchResult?.contains("MissingOuter") != true)
    }

    @Test
    fun `should neither resolve nor find nested class of private outer class served outside the classpath roots`() {
        // given
        val root = isolatedTestFixturesCopy("withoutPrivateOuter", "StrippedOuter.class")
        val outside = isolatedTestFixturesCopy("privateOuterOnly", isAbsent = { it != "StrippedOuter.class" })
        val outsideLoader = URLClassLoader(arrayOf(outside.toUri().toURL()), null)
        // like a class loader serving some classes from elsewhere than its classpath roots, which the scan cannot list
        val classLoader = object : URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader) {
            override fun findResource(name: String): URL? = super.findResource(name) ?: outsideLoader.findResource(name)

            override fun findClass(name: String): Class<*> = try {
                super.findClass(name)
            } catch (e: ClassNotFoundException) {
                val bytes = outsideLoader.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() }
                    ?: throw e
                defineClass(name, bytes, 0, bytes.size)
            }
        }
        val introspector = KotlinIntrospector(classLoader)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.StrippedOuter.Nested")
        val searchResult = introspector.search("Nested", pageSize = 1000)

        // then
        // the outer class is private, as both the resolve and the classpath scan tell, by its class file
        assert(signature == null)
        assert(searchResult?.contains("StrippedOuter") != true)
    }

    @Test
    fun `should neither resolve nor find nested class of outer class whose class file is there, but cannot be opened`() {
        // given
        val root = isolatedTestFixturesCopy("withoutUnopenedOuter", "StrippedOuter.class")
        val outside = isolatedTestFixturesCopy("unopenedOuterOnly", isAbsent = { it != "StrippedOuter.class" })
        val outsideLoader = URLClassLoader(arrayOf(outside.toUri().toURL()), null)
        // like a URLClassLoader swallowing the IOException of opening a class file found outside the classpath roots,
        // which it cannot load then either
        val classLoader = object : URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader) {
            override fun findResource(name: String): URL? = super.findResource(name) ?: outsideLoader.findResource(name)

            override fun getResourceAsStream(name: String): InputStream? =
                if (name.endsWith("/StrippedOuter.class")) null else super.getResourceAsStream(name)
        }
        val introspector = KotlinIntrospector(classLoader)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.StrippedOuter.Nested")
        val searchResult = introspector.search("Nested", pageSize = 1000)

        // then
        // the outer class is not absent, so it might be private, like it is
        assert(signature == null)
        assert(searchResult?.contains("StrippedOuter") != true)
    }

    @Test
    fun `should find nested class of outer class served outside the classpath roots after a transient IOException`() {
        // given
        val root = isolatedTestFixturesCopy("withoutOuter", "MissingOuter.class")
        val outside = isolatedTestFixturesCopy("outerOnly", isAbsent = { it != "MissingOuter.class" })
        val outsideLoader = URLClassLoader(arrayOf(outside.toUri().toURL()), null)
        val outerClassFile = "com/xemantic/kotlin/introspection/isolated/MissingOuter.class"
        val outerReads = java.util.concurrent.atomic.AtomicInteger()
        // like a class loader serving some classes from elsewhere than its classpath roots, failing to read one once
        val classLoader = object : URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader) {
            override fun findResource(name: String): URL? = super.findResource(name) ?: outsideLoader.findResource(name)

            override fun getResourceAsStream(name: String): InputStream? =
                if (name == outerClassFile && outerReads.incrementAndGet() == 1) {
                    object : InputStream() {
                        override fun read(): Int = throw IOException("transient failure of $name")
                    }
                } else {
                    super.getResourceAsStream(name)
                }
        }

        // when
        val searchResult = KotlinIntrospector(classLoader).search("Nested", pageSize = 1000)

        // then
        // the class file of the outer class read again, telling it is public
        assert(searchResult?.lines()?.contains("com.xemantic.kotlin.introspection.isolated.MissingOuter.Nested") == true)
        assert(outerReads.get() == 2)
    }

    @Test
    fun `should tell sealed subclass without metadata as not read`() {
        // given
        val introspector = introspectorWithoutMetadata("StrippedSealed\$Stripped.class")

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.StrippedSealed")

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.StrippedSealed [page 1/1]
            sealed interface StrippedSealed {
                class Kept : StrippedSealed
                // sealed subclass could not be read: com.xemantic.kotlin.introspection.isolated.StrippedSealed.Stripped
            }
        """.trimIndent()
    }

    @Test
    fun `should escape sealed subclass named like hard keyword without class file`() {
        // given
        val introspector = introspectorWithoutAbsentClass("KeywordSubclassSealed\$in.class")

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.KeywordSubclassSealed")

        // then
        // escaped like the name of a when branch covering it
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.KeywordSubclassSealed [page 1/1]
            sealed interface KeywordSubclassSealed {
                class Kept : KeywordSubclassSealed
                // sealed subclass not found: com.xemantic.kotlin.introspection.isolated.KeywordSubclassSealed.`in`
            }
        """.trimIndent()
    }

    @Test
    fun `should escape sealed subclass named like hard keyword without metadata`() {
        // given
        val introspector = introspectorWithoutMetadata("KeywordSubclassSealed\$in.class")

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.KeywordSubclassSealed")

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.KeywordSubclassSealed [page 1/1]
            sealed interface KeywordSubclassSealed {
                class Kept : KeywordSubclassSealed
                // sealed subclass could not be read: com.xemantic.kotlin.introspection.isolated.KeywordSubclassSealed.`in`
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve nested class of outer class without class file`() {
        // given
        val introspector = introspectorWithoutAbsentClass("MissingOuter.class")

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.MissingOuter.Nested"
        )
        val member = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.MissingOuter.Nested.f"
        )

        // then
        // the outer class is assumed to be part of the API, as nothing tells otherwise
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.MissingOuter.Nested [page 1/1]
            class Nested {
                fun f()
            }
        """.trimIndent()
        member sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.MissingOuter.Nested.f [page 1/1]
            fun f()
        """.trimIndent()
    }

    @Test
    fun `should declare type parameters of inner class whose outer class has no class file`() {
        // given
        val introspector = introspectorWithoutAbsentClass("MissingGenericOuter.class")

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.MissingGenericOuter.Inner"
        )

        // then
        // the names of the type parameters of the outer class are not known, only the ones the inner class refers to
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.MissingGenericOuter.Inner [page 1/1]
            inner class MissingGenericOuter<T0>.Inner : Comparable<T0> {
                override operator fun compareTo(other: T0): Int
                fun <R> map(f: (T0) -> R): R
            }
        """.trimIndent()
    }

    @Test
    fun `should not render type parameter of outer class without class file as definitely not nullable`() {
        // given
        val introspector = introspectorWithoutAbsentClass("MissingNotNullOuter.class")

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.MissingNotNullOuter.Inner.orDefault"
        )

        // then
        // the bounds of the type parameters of the outer class are not known, like the T : Any, whose T & Any does not compile
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.MissingNotNullOuter.Inner.orDefault [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.isolated.DefinitelyNonNullHolder
            fun orDefault(value: T0, default: T0): T0
        """.trimIndent()
    }

    @Test
    fun `should not render type parameter of nullable bounds of outer class without class file as definitely not nullable`() {
        // given
        val introspector = introspectorWithoutAbsentClass("MissingNullableOuter.class")

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.MissingNullableOuter.Inner.orDefault"
        )

        // then
        // the T & Any of the T of nullable bounds would compile, but the bounds are not known, so that T0 is rendered,
        // deliberately, as it compiles whatever the bounds, unlike T0 & Any
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.MissingNullableOuter.Inner.orDefault [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.isolated.DefinitelyNonNullHolder
            fun orDefault(value: T0, default: T0): T0
        """.trimIndent()
    }

    @Test
    fun `should name type parameters of outer class without class file apart from the ones of its inner class`() {
        // given
        val introspector = introspectorWithoutAbsentClass("ClashingGenericOuter.class")

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.ClashingGenericOuter.Inner"
        )
        val deep = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.ClashingGenericOuter.Inner.Deep"
        )

        // then
        // T0, and T0_ of its member, are already declared by the inner class,
        // also for the inner class nested in it, which its outer classes read declare too
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.ClashingGenericOuter.Inner [page 1/1]
            inner class ClashingGenericOuter<T0__>.Inner<T0> : Comparable<T0__> {
                inner class Deep : Comparable<T0__>
                override operator fun compareTo(other: T0__): Int
                fun <T0_> pair(a: T0, b: T0_): Pair<T0__, T0_>
            }
        """.trimIndent()
        deep sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.ClashingGenericOuter.Inner.Deep [page 1/1]
            inner class ClashingGenericOuter<T0__>.Inner<T0>.Deep : Comparable<T0__> {
                override operator fun compareTo(other: T0__): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should declare type parameters of inner class whose outer class of its outer class has a class file`() {
        // given
        val introspector = introspectorWithoutAbsentClass("MissingMidHolder\$Mid.class")

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.MissingMidHolder.Mid.Leaf"
        )

        // then
        // the type parameter of the outermost class is known, and the remaining one is given to the missing class
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.MissingMidHolder.Mid.Leaf [page 1/1]
            inner class MissingMidHolder<A>.Mid<T1>.Leaf : Comparable<T1> {
                override operator fun compareTo(other: T1): Int
                fun pair(): Pair<A, T1>
            }
        """.trimIndent()
    }

    @Test
    fun `should not qualify inner class with outer class of its nested outer class without class file`() {
        // given
        val introspector = introspectorWithoutAbsentClass("NestedMidHolder\$Mid.class")

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.NestedMidHolder.Mid.Leaf"
        )

        // then
        // the type parameters of the outermost class are not referred to, as the missing class is not inner
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.NestedMidHolder.Mid.Leaf [page 1/1]
            inner class Mid<T1>.Leaf : Comparable<T1> {
                override operator fun compareTo(other: T1): Int
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
     * A class loader counting the classes requested by their names,
     * failing the first request of the classes [failingOnce], and every request of the classes [failing],
     * with the [failure], not serving the class files of the classes [unreadable],
     * and failing the first read of the class files of the classes [unreadableOnce] with an `IOException`.
     */
    private class CountingClassLoader(
        parent: ClassLoader,
        private val failingOnce: Set<String> = emptySet(),
        private val unreadable: Set<String> = emptySet(),
        private val unreadableOnce: Set<String> = emptySet(),
        // like a transient failure, unlike a class missing from the classpath
        private val failure: (String) -> Throwable = { LinkageError("transient failure of $it") }
    ) : ClassLoader(parent) {
        val loads = ConcurrentHashMap<String, Int>()
        // the resources requested, also to read them, by their names, and the class files read, by their class names
        val resources = ConcurrentHashMap<String, Int>()
        val reads = ConcurrentHashMap<String, Int>()
        val failing: MutableSet<String> = ConcurrentHashMap.newKeySet()
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            val count = loads.merge(name, 1, Int::plus)
            if ((name in failingOnce && count == 1) || name in failing) throw failure(name)
            return super.loadClass(name, resolve)
        }
        override fun getResource(name: String): java.net.URL? {
            resources.merge(name, 1, Int::plus)
            return super.getResource(name)
        }
        override fun getResourceAsStream(name: String): InputStream? {
            val className = name.removeSuffix(".class").replace('/', '.')
            val count = reads.merge(className, 1, Int::plus)
            // like a URLClassLoader whose jar cannot be read for now
            if (className in unreadableOnce && count == 1) return object : InputStream() {
                override fun read(): Int = throw IOException("transient failure of $className")
            }
            return if (className in unreadable) null else super.getResourceAsStream(name)
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
    fun `should load nested class again after ClassCircularityError`() {
        // given
        val nested = "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder\$Sorted"
        // which might be spurious, when the classes are loaded in parallel
        val classLoader = CountingClassLoader(
            javaClass.classLoader,
            failingOnce = setOf(nested),
            failure = { ClassCircularityError(it) }
        )
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signatures = List(2) {
            introspector.resolve("com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder")
        }

        // then
        assert(signatures.all { it != null && "class Sorted<T>" in it })
        assert(classLoader.loads[nested] == 2)
    }

    @Test
    fun `should look up nested class again after ClassNotFoundException when its class file is there`() {
        // given
        val nested = "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder\$Sorted"
        // like a URLClassLoader failing to read the class file with an IOException
        val classLoader = CountingClassLoader(
            javaClass.classLoader,
            failingOnce = setOf(nested),
            failure = { ClassNotFoundException(it) }
        )
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signatures = List(2) {
            introspector.resolve("com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder")
        }

        // then
        assert(signatures[0] != null && "class Sorted<T>" !in signatures[0]!!)
        assert(signatures[1] != null && "class Sorted<T>" in signatures[1]!!)
        assert(classLoader.loads[nested] == 2)
    }

    @Test
    fun `should count failing lookup by name after ClassNotFoundException caused by IOException`() {
        // given
        val holder = "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder"
        val nested = "$holder\$Sorted"
        // like a URLClassLoader failing to read the class file, telling a transient failure even when not probed
        val classLoader = CountingClassLoader(
            javaClass.classLoader,
            failure = { ClassNotFoundException(it, IOException("transient failure of $it")) }
        )
        classLoader.failing += nested
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        introspector.resolve("$holder.Sorted")
        val signatures = List(2) { introspector.resolve(holder) }

        // then
        assert(signatures.all { it != null && "class Sorted<T>" !in it })
        // retried by the resolve by name, and by the first resolve of the holder, deeming the failure final,
        // rather than the miss by name taken as final, telling nothing about the failure
        assert(classLoader.loads[nested] == 2)
    }

    @Test
    fun `should not cache missing class resolved by name when ClassNotFoundException is caused by IOException`() {
        // given
        val missing = "com.xemantic.kotlin.introspection.test.Missing"
        // like a class loader wrapping the NoSuchFileException of a class file which is not there
        val classLoader = CountingClassLoader(
            javaClass.classLoader,
            failure = { ClassNotFoundException(it, java.nio.file.NoSuchFileException(it)) }
        )
        classLoader.failing += missing
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signatures = List(3) { introspector.resolve(missing) }

        // then
        // a final miss, as its class file is not there, which is not cached, as the name comes from the user input,
        // rather than taken as a transient failure, deemed final, and cached
        assert(signatures.all { it == null })
        assert(classLoader.loads[missing] == 3)
    }

    @Test
    fun `should load nested class again after NoClassDefFoundError and failed read of its class file`() {
        // given
        val nested = "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder\$Sorted"
        val classLoader = CountingClassLoader(
            javaClass.classLoader,
            failingOnce = setOf(nested),
            unreadable = setOf(nested),
            failure = { NoClassDefFoundError(it) }
        )
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signatures = List(2) {
            introspector.resolve("com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder")
        }

        // then
        assert(signatures[0] != null && "class Sorted<T>" !in signatures[0]!!)
        assert(signatures[1] != null && "class Sorted<T>" in signatures[1]!!)
        assert(classLoader.loads[nested] == 2)
    }

    /**
     * A class loader of the class files of the [root], counting the classes requested by their names,
     * failing the first definition of the class [failingOnce], like a `URLClassLoader` failing to read its class file
     * with an `IOException`, which it throws wrapped in a `ClassNotFoundException`.
     */
    private class FailingOnceUrlClassLoader(
        root: Path,
        parent: ClassLoader,
        private val failingOnce: String
    ) : URLClassLoader(arrayOf(root.toUri().toURL()), parent) {
        val loads = ConcurrentHashMap<String, Int>()
        private val hasFailed = java.util.concurrent.atomic.AtomicBoolean()
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            loads.merge(name, 1, Int::plus)
            return super.loadClass(name, resolve)
        }
        override fun findClass(name: String): Class<*> {
            if (name == failingOnce && hasFailed.compareAndSet(false, true)) {
                throw ClassNotFoundException(name, IOException("transient failure of $name"))
            }
            return super.findClass(name)
        }
    }

    @Test
    fun `should load class again after NoClassDefFoundError caused by transient IOException of its supertype`() {
        // given
        val subclass = "com.xemantic.kotlin.introspection.isolated.AbsentNestedHolder\$AbsentSubclass"
        // the supertype, whose class file cannot be read for now, failing the definition of its subclass
        val classLoader = FailingOnceUrlClassLoader(
            isolatedTestFixturesCopy("failingOnce"),
            javaClass.classLoader,
            failingOnce = "com.xemantic.kotlin.introspection.isolated.Absent"
        )
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signatures = List(3) {
            introspector.resolve("com.xemantic.kotlin.introspection.isolated.AbsentNestedHolder.AbsentSubclass")
        }

        // then
        // read from the class file the first time, and loaded the second time, rather than never again
        assert(signatures.all { it != null && "class AbsentSubclass : Absent" in it })
        assert(classLoader.loads[subclass] == 2)
    }

    @Test
    fun `should read class file again after a transient IOException, to tell the scope and hide the members deprecated as HIDDEN`() {
        // given
        val root = isolatedTestFixturesCopy("absentParameterHidden", "Absent.class")
        val reads = java.util.concurrent.atomic.AtomicInteger()
        // like a URLClassLoader whose jar cannot be read for now, while the class is already loaded
        val classLoader = object : URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader) {
            override fun getResourceAsStream(name: String): InputStream? =
                if (name.endsWith("/AbsentParameterHidden.class") && reads.incrementAndGet() == 1) {
                    object : InputStream() {
                        override fun read(): Int = throw IOException("transient failure of $name")
                    }
                } else {
                    super.getResourceAsStream(name)
                }
        }
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signatures = List(2) { introspector.resolve("com.xemantic.kotlin.introspection.isolated.AbsentParameterHidden") }

        // then
        // reflected the first time, where none of the members can be, as a type of one of them cannot be loaded,
        // and read from the class file the second time, telling the hidden members,
        // like the constructor, so that the class cannot be subclassed
        assert(signatures[0] != null && "protected fun forSubclasses()" in signatures[0]!! && "fun hidden(absent: Absent)" in signatures[0]!!)
        signatures[1] sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentParameterHidden [page 1/1]
            open class AbsentParameterHidden private constructor() {
                fun visible()
            }
        """.trimIndent()
        assert(reads.get() == 2)
    }

    @Test
    fun `should reflect deprecations of interface properties kept in DefaultImpls, when the class file is not served`() {
        // given
        // the deprecations of the properties of an interface compiled without the JVM default methods
        // are kept on the synthetic methods for their annotations in its DefaultImpls class
        val target = "com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsHiddenProperties"
        val classLoader = CountingClassLoader(
            javaClass.classLoader,
            unreadable = setOf(target, "$target\$DefaultImpls")
        )
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signature = introspector.resolve(target)

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsHiddenProperties [page 1/1]
            interface DefaultImplsHiddenProperties {
                val visible: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not assume enclosing class absent when reading its class file fails transiently`() {
        // given
        val outer = "com.xemantic.kotlin.introspection.test.InternalNestedHolder"
        // the internal outer class cannot be loaded, and its class file cannot be read the first time
        val classLoader = CountingClassLoader(
            javaClass.classLoader,
            unreadableOnce = setOf(outer),
            failure = { ClassNotFoundException(it) }
        )
        classLoader.failing += outer
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signatures = List(2) { introspector.resolve("$outer.NestedInInternalClass") }

        // then
        // an absent enclosing class would be assumed public, while this one is there, only not read for now
        assert(signatures == listOf(null, null))
    }

    @Test
    fun `should mark overrides of Java superclass again after its transient linkage error`() {
        // given
        val javaSuperclass = "com.xemantic.kotlin.introspection.test.other.JavaProcessorBase"
        // the class file is not served, so that the first resolve knows nothing about the Java superclass
        val classLoader = CountingClassLoader(javaClass.classLoader, unreadable = setOf(javaSuperclass))
        classLoader.failing += javaSuperclass
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signatures = List(2) {
            introspector.resolve("com.xemantic.kotlin.introspection.test.JavaProcessor").also {
                classLoader.failing.clear()
            }
        }

        // then
        assert(signatures[0] != null && "override fun process(item: String)" !in signatures[0]!!)
        signatures[1] sameAsKotlin javaProcessorSignature
    }

    @Test
    fun `should not look up class files for the names tried only to resolve a member`() {
        // given
        val classLoader = CountingClassLoader(javaClass.classLoader)
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.test.TestClass.greet")

        // then
        assert(signature != null && "fun greet(): String" in signature)
        // the names tried as classes, like TestClass${'$'}greet, are not found, and not cached, so not worth probing
        assert(classLoader.resources.keys.none { "greet" in it })
    }

    private val javaProcessorSignature = /* language=kotlin */ """
        // com.xemantic.kotlin.introspection.test.JavaProcessor [page 1/1]
        open class JavaProcessor : com.xemantic.kotlin.introspection.test.other.JavaProcessorBase<String> {
            override fun process(item: String)
            fun process(item: Int)
            override fun handle(items: List<String>)
            protected override fun count(): Int
            fun finish()
            override fun <N : Number> measure(value: N)
            fun measure(value: String)
            override fun all(items: Array<String>)
            fun all(items: Array<Int>)
        }
    """.trimIndent()

    @Test
    fun `should look up Java superclass once when its loading fails permanently`() {
        // given
        val javaSuperclass = "com.xemantic.kotlin.introspection.test.other.JavaProcessorBase"
        val classLoader = CountingClassLoader(
            javaClass.classLoader,
            failure = { IncompatibleClassChangeError(it) }
        )
        classLoader.failing += javaSuperclass
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signatures = List(3) {
            introspector.resolve("com.xemantic.kotlin.introspection.test.JavaProcessor")
        }

        // then
        // the overrides are told from the class file, read once
        assert(signatures.all { it == javaProcessorSignature })
        assert(classLoader.loads[javaSuperclass] == 1)
        assert(classLoader.reads[javaSuperclass] == 1)
    }

    @Test
    fun `should retry Java superclass failing transiently once per resolve, until its failure is deemed final`() {
        // given
        val javaSuperclass = "com.xemantic.kotlin.introspection.test.other.JavaProcessorBase"
        // like a class loader serving the class file of a class it refuses to define
        val classLoader = CountingClassLoader(
            javaClass.classLoader,
            failure = { ClassNotFoundException(it) }
        )
        classLoader.failing += javaSuperclass
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signatures = List(3) {
            introspector.resolve("com.xemantic.kotlin.introspection.test.JavaProcessor")
        }

        // then
        assert(signatures.all { it != null })
        assert(classLoader.loads[javaSuperclass] == 2)
    }

    @Test
    fun `should deem failure of class lookup final also when the class is resolved by name meanwhile`() {
        // given
        val javaSuperclass = "com.xemantic.kotlin.introspection.test.other.JavaProcessorBase"
        // like a class loader serving the class file of a class it refuses to define
        val classLoader = CountingClassLoader(
            javaClass.classLoader,
            failure = { ClassNotFoundException(it) }
        )
        classLoader.failing += javaSuperclass
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signatures = List(3) {
            introspector.resolve("com.xemantic.kotlin.introspection.test.JavaProcessor").also {
                // a name given to resolve, whose miss is neither probed, nor cached, as it comes from the user input
                introspector.resolve(javaSuperclass)
            }
        }

        // then
        assert(signatures.all { it != null })
        // retried by the first two resolves, and by the first resolve by name, until deemed final, and cached,
        // also for the resolves by name, rather than retried for good, as each miss by name reset the failure
        assert(classLoader.loads[javaSuperclass] == 3)
    }

    @Test
    fun `should count failing lookups of class by name together with its other lookups`() {
        // given
        val javaSuperclass = "com.xemantic.kotlin.introspection.test.other.JavaProcessorBase"
        // a linkage error which might be transient, whichever the lookup
        val classLoader = CountingClassLoader(javaClass.classLoader)
        classLoader.failing += javaSuperclass
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signatures = List(3) {
            introspector.resolve("com.xemantic.kotlin.introspection.test.JavaProcessor").also {
                introspector.resolve(javaSuperclass)
            }
        }

        // then
        assert(signatures.all { it == javaProcessorSignature })
        // retried by the first resolve, and by the first resolve by name, deeming the failure final,
        // rather than counted apart, each lookup retrying until its own count deems it final
        assert(classLoader.loads[javaSuperclass] == 2)
    }

    /**
     * A class loader blocking the first request of the class [blocked] until [release]d,
     * and then failing it with a `ClassNotFoundException`, like a transient failure,
     * while its class file is still served, unless [isFirstRequestLoaded],
     * when it is loaded instead, while every other request fails meanwhile, without the class file served.
     */
    private class BlockingClassLoader(
        parent: ClassLoader,
        private val blocked: String,
        private val isFirstRequestLoaded: Boolean = false
    ) : ClassLoader(parent) {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // the requests of the class blocked
        val requests = java.util.concurrent.atomic.AtomicInteger()
        private val blockedResource = blocked.replace('.', '/') + ".class"
        private val isHidden get() = isFirstRequestLoaded && release.count > 0
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == blocked) {
                if (requests.incrementAndGet() == 1) {
                    entered.countDown()
                    awaitOrFail(release)
                    if (!isFirstRequestLoaded) throw ClassNotFoundException(name)
                } else if (isHidden) {
                    throw ClassNotFoundException(name)
                }
            }
            return super.loadClass(name, resolve)
        }
        override fun getResource(name: String): java.net.URL? =
            if (name == blockedResource && isHidden) null else super.getResource(name)
        override fun getResourceAsStream(name: String): InputStream? =
            if (name == blockedResource && isHidden) null else super.getResourceAsStream(name)
    }

    @Test
    fun `should cache class found by name over miss cached meanwhile by concurrent lookup of the same class`() {
        // given
        val outer = "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder"
        // the outer class found by name, while any other request of it fails meanwhile, without its class file served
        val classLoader = BlockingClassLoader(javaClass.classLoader, blocked = outer, isFirstRequestLoaded = true)
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)
        var byNameSignature: String? = null
        val byName = testThread { byNameSignature = introspector.resolve(outer) }
        awaitOrFail(classLoader.entered)
        // its lookup of the outer class, whose miss is cached, missing it meanwhile
        var nested: String? = null
        val lookup = testThread { nested = introspector.resolve("$outer.Sorted") }

        // when
        try {
            // until the lookup of the nested class is over, or waits for the lookup by name, if it does
            pollUntil { !lookup.isAlive || lookup.isWaitingForComputation() }
        } finally {
            classLoader.release.countDown()
        }
        joinOrFail(byName, lookup)
        val requests = classLoader.requests.get()
        val signature = introspector.resolve(outer)

        // then
        assert(byNameSignature != null && "class TypeParametersNestedHolder" in byNameSignature!!)
        assert(nested != null && "class Sorted<T>" in nested!!)
        // guarding the put over a miss: the class found by name is cached over the miss cached meanwhile,
        // as a class once found stays loadable,
        // rather than the miss kept, and the class assumed missing
        assert(signature != null && "class TypeParametersNestedHolder" in signature)
        assert(classLoader.requests.get() == requests)
    }

    @Test
    fun `should share class lookup of concurrent resolves by name`() {
        // given
        val outer = "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder"
        val classLoader = BlockingClassLoader(javaClass.classLoader, blocked = outer, isFirstRequestLoaded = true)
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)
        val signatures = arrayOfNulls<String>(2)
        val first = testThread { signatures[0] = introspector.resolve(outer) }
        awaitOrFail(classLoader.entered)
        val second = testThread { signatures[1] = introspector.resolve(outer) }

        // when
        try {
            pollUntil { !second.isAlive || second.isWaitingForComputation() }
        } finally {
            classLoader.release.countDown()
        }
        joinOrFail(first, second)

        // then
        // loaded once, rather than by each resolve, the second one failing meanwhile
        assert(classLoader.requests.get() == 1)
        assert(signatures.all { it != null && "class TypeParametersNestedHolder" in it })
    }

    @Test
    fun `should not share miss of class resolved by name with concurrent lookup of the same class`() {
        // given
        val outer = "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder"
        val classLoader = BlockingClassLoader(javaClass.classLoader, blocked = outer)
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)
        // the outer class resolved by name, whose miss is not probed, failing after the lookup of the nested class waits
        val byName = testThread { introspector.resolve(outer) }
        awaitOrFail(classLoader.entered)
        var nested: String? = null
        val lookup = testThread { nested = introspector.resolve("$outer.Sorted") }

        // when
        try {
            // until the lookup of the nested class waits for the lookup of its outer class, if it does,
            // bounded, as it might wait for something else instead
            pollUntil { !lookup.isAlive || lookup.isWaitingForComputation() }
        } finally {
            classLoader.release.countDown()
        }
        joinOrFail(byName, lookup)

        // then
        // the outer class is looked up on its own, and found, rather than assumed missing after a miss not probed
        assert(nested != null && "class Sorted<T>" in nested!!)
    }

    /**
     * A class loader hiding the class [hidden] from the threads started by [thread] as hidden,
     * failing their requests of it with a `ClassNotFoundException`, without its class file served,
     * like a class loader not serving it yet, or with the [failure], if any, while its class file is still served,
     * while blocking the first request of it by a thread started with a [Gate], until the gate is released.
     */
    private class ThreadHidingClassLoader(
        parent: ClassLoader,
        private val hidden: String,
        private val failure: ((String) -> Throwable)? = null
    ) : ClassLoader(parent) {
        class Gate {
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
        }
        // the requests of the class hidden, by any thread
        val requests = java.util.concurrent.atomic.AtomicInteger()
        private val hiddenFrom: MutableSet<Thread> = ConcurrentHashMap.newKeySet()
        private val gates = ConcurrentHashMap<Thread, Gate>()
        private val hiddenResource = hidden.replace('.', '/') + ".class"
        private val isHidden get() = Thread.currentThread() in hiddenFrom
        private val isResourceHidden get() = isHidden && failure == null
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == hidden) {
                requests.incrementAndGet()
                gates.remove(Thread.currentThread())?.let { gate ->
                    gate.entered.countDown()
                    awaitOrFail(gate.release)
                }
                if (isHidden) throw failure?.invoke(name) ?: ClassNotFoundException(name)
            }
            return super.loadClass(name, resolve)
        }
        override fun getResource(name: String): java.net.URL? =
            if (name == hiddenResource && isResourceHidden) null else super.getResource(name)
        override fun getResourceAsStream(name: String): InputStream? =
            if (name == hiddenResource && isResourceHidden) null else super.getResourceAsStream(name)
        /**
         * Starts a thread running the [block], from which the class is hidden if [isHidden],
         * its first request of the class blocked by the [gate], if any.
         */
        fun thread(isHidden: Boolean, gate: Gate? = null, block: () -> Unit): TestThread = TestThread(block).also {
            if (isHidden) hiddenFrom += it
            if (gate != null) gates[it] = gate
            it.start()
        }
    }

    @Test
    fun `should not replace class found by name with miss of concurrent lookup of the same class`() {
        // given
        val outer = "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder"
        val classLoader = ThreadHidingClassLoader(javaClass.classLoader, hidden = outer)
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)
        val gate = ThreadHidingClassLoader.Gate()
        // its lookup of the outer class, whose miss is cached, missing it after the lookup by name finds it
        val lookup = classLoader.thread(isHidden = true, gate) { introspector.resolve("$outer.Sorted") }
        awaitOrFail(gate.entered)
        var byNameSignature: String? = null
        val byName = classLoader.thread(isHidden = false) { byNameSignature = introspector.resolve(outer) }

        // when
        try {
            // until the lookup by name is over, or waits for the lookup of the nested class, if it does
            pollUntil { !byName.isAlive || byName.isWaitingForComputation() }
        } finally {
            gate.release.countDown()
        }
        joinOrFail(lookup, byName)
        val requests = classLoader.requests.get()
        val signature = introspector.resolve(outer)

        // then
        // found by name on its own, rather than receiving the miss of the concurrent lookup
        assert(byNameSignature != null && "class TypeParametersNestedHolder" in byNameSignature!!)
        // and kept, rather than replaced with the later miss, and the class assumed missing
        assert(signature != null && "class TypeParametersNestedHolder" in signature)
        assert(classLoader.requests.get() == requests)
    }

    @Test
    fun `should take class found meanwhile over probed miss of lookup by name`() {
        // given
        val outer = "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder"
        // like a URLClassLoader failing to read the class file, for the threads hidden, while it is still served
        val classLoader = ThreadHidingClassLoader(
            javaClass.classLoader,
            hidden = outer,
            failure = { ClassNotFoundException(it, IOException("transient failure of $it")) }
        )
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)
        // the first failing attempt, by name
        joinOrFail(classLoader.thread(isHidden = true) { introspector.resolve(outer) })
        val gate = ThreadHidingClassLoader.Gate()
        // its lookup of the outer class, whose miss is cached, finding it after the second lookup by name misses it
        val lookup = classLoader.thread(isHidden = false, gate) { introspector.resolve("$outer.Sorted") }
        awaitOrFail(gate.entered)
        var byNameSignature: String? = null
        // the second failing attempt, by name, deeming the failure final, so that its miss, probed, is to be cached
        val byName = classLoader.thread(isHidden = true) { byNameSignature = introspector.resolve(outer) }

        // when
        try {
            // until the lookup by name is over, or waits for the lookup of the outer class, if it does
            pollUntil { !byName.isAlive || byName.isWaitingForComputation() }
        } finally {
            gate.release.countDown()
        }
        joinOrFail(lookup, byName)
        val requests = classLoader.requests.get()
        val signature = introspector.resolve(outer)

        // then
        // the class found meanwhile is taken, and kept cached, rather than the final miss by name
        assert(byNameSignature != null && "class TypeParametersNestedHolder" in byNameSignature!!)
        assert(signature == byNameSignature)
        assert(classLoader.requests.get() == requests)
    }

    @Test
    fun `should mark overrides of Java superclass which cannot be loaded, as read from its class file`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentGenericJavaLeaf"
        )

        // then
        // the type argument of the generic Java superclass is substituted also in its own generic supertype
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentGenericJavaLeaf [page 1/1]
            abstract class AbsentGenericJavaLeaf : AbsentGenericJavaMid<String> {
                override fun process(item: String)
                fun process(item: Int)
                override fun run()
                fun finish(code: Int)
                override operator fun compareTo(other: String): Int
                fun compareTo(other: Int): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve member inherited through Java superclass which cannot be loaded, as read from its class file`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentKotlinInterfaceJavaLeaf.greet"
        )

        // then
        // the Kotlin interface is told by the class file of the Java superclass, whose own members are not
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentKotlinInterfaceJavaLeaf.greet [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.isolated.AbsentReachedGreeter
            fun greet(): String
        """.trimIndent()
    }

    @Test
    fun `should resolve member of package-private Java class inherited by class which cannot be loaded from its public Java subclass`() {
        // given
        val introspector = introspectorWithoutAbsentClass("AbsentGreetingMarker.class")

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.UnloadableGreeterLeaf.hello"
        )

        // then
        // the package-private class cannot be named, its public Java subclass is told by the superclass of the metadata
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.UnloadableGreeterLeaf.hello [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.isolated.PublicGreeterJavaMid
            fun hello(): String
        """.trimIndent()
    }

    @Test
    fun `should resolve member inherited through cyclic supertypes once`() {
        // given
        fun type(name: String) = KmType().apply { classifier = KmClassifier.Class("com/xemantic/kotlin/introspection/isolated/$name") }
        val root = isolatedTestFixturesCopy("cyclic") { fileName, bytes ->
            when (fileName) {
                "CyclicFirst.class" -> withSupertypesInMetadata(bytes, type("CyclicSecond"))
                "CyclicSecond.class" -> withSupertypesInMetadata(bytes, type("CyclicFirst"))
                else -> bytes
            }
        }
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val second = introspector.resolve("com.xemantic.kotlin.introspection.isolated.CyclicLeaf.second")
        val missing = introspector.resolve("com.xemantic.kotlin.introspection.isolated.CyclicLeaf.missing")

        // then
        second sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.CyclicLeaf.second [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.isolated.CyclicSecond
            fun second()
        """.trimIndent()
        assert(missing == null)
    }

    @Test
    fun `should mark overrides of raw Java superclass which cannot be loaded, as read from its class file without generic signature`() {
        // given
        val introspector = introspectorWithoutAbsentClass()

        // when
        val signature = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.AbsentRawGenericJavaLeaf"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentRawGenericJavaLeaf [page 1/1]
            abstract class AbsentRawGenericJavaLeaf : AbsentRawGenericJavaMid {
                override fun process(item: Any?)
                fun process(item: String)
                override operator fun compareTo(other: Any?): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of members of built-in type declared only in its class file when class loader serves no standard library resources`() {
        // given
        // like an in-memory class loader of a scripting host, defining the classes of the standard library
        // without serving their class files as resources
        val classLoader = object : URLClassLoader(
            isolatedTestFixtures.map { it.toURI().toURL() }.toTypedArray(),
            javaClass.classLoader
        ) {
            override fun getResource(name: String): java.net.URL? =
                if (name.startsWith("kotlin/")) null else super.getResource(name)
        }
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.NonSuspendCallable")

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.NonSuspendCallable [page 1/1]
            abstract class NonSuspendCallable : KCallable<String> {
                override val isSuspend: Boolean
            }
        """.trimIndent()
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
    fun `should neither list nor resolve synthetic companion object`() {
        // given
        val root = tempDir.resolve("syntheticCompanion")
        isolatedTestFixtures.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val target = root.resolve(file.relativeTo(dir).path).toFile()
                target.parentFile.mkdirs()
                target.writeBytes(
                    if (file.name == "SyntheticCompanionHolder\$Companion.class") {
                        synthetic(file.readBytes())
                    } else {
                        file.readBytes()
                    }
                )
            }
        }
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.SyntheticCompanionHolder")
        val companion = introspector.resolve("com.xemantic.kotlin.introspection.isolated.SyntheticCompanionHolder.Companion")
        val member = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.SyntheticCompanionHolder.Companion.create"
        )

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.SyntheticCompanionHolder [page 1/1]
            class SyntheticCompanionHolder {
                fun f()
            }
        """.trimIndent()
        assert(companion == null)
        assert(member == null)
    }

    @Test
    fun `should not load companion object of annotation class with parameters`() {
        // given
        val classLoader = CountingClassLoader(javaClass.classLoader)
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.test.CompanionAnnotation")

        // then
        // the parameters are the only members of an annotation class rendered
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionAnnotation [page 1/1]
            annotation class CompanionAnnotation(val value: Int)
        """.trimIndent()
        assert(classLoader.loads.keys.none { it.endsWith("CompanionAnnotation\$Companion") })
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
        // so its override is not told, rendered like any other member of the final class
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentJavaSubclass [page 1/1]
            class AbsentJavaSubclass : AbsentJavaBase<String> {
                fun process(item: String)
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
    fun `should tell overrides of internal members by modality in final classes which can be neither read nor reflected`() {
        // given
        val introspector = unreflectableIntrospector()

        // when
        val sameModule = introspector.resolve(
            "com.xemantic.kotlin.introspection.othermodule.UnreflectableSameModuleSubclass"
        )
        val friendModule = introspector.resolve(
            "com.xemantic.kotlin.introspection.friendmodule.UnreflectableFriendModuleSubclass"
        )

        // then
        // the JVM methods telling a mangled override cannot be read, so in a final class the modality is the evidence,
        // as only an override is open there
        sameModule sameAsKotlin """
            // com.xemantic.kotlin.introspection.othermodule.UnreflectableSameModuleSubclass [page 1/1]
            class UnreflectableSameModuleSubclass : InternalBase {
                override val state: Int
                fun take(marker: OtherModuleMarker)
            }
        """.trimIndent()
        // also of a friend module, otherwise told only by the bridge, which cannot be seen
        friendModule sameAsKotlin """
            // com.xemantic.kotlin.introspection.friendmodule.UnreflectableFriendModuleSubclass [page 1/1]
            class UnreflectableFriendModuleSubclass : com.xemantic.kotlin.introspection.othermodule.InternalBase {
                override val state: Int
                fun take(marker: com.xemantic.kotlin.introspection.othermodule.OtherModuleMarker)
            }
        """.trimIndent()
    }

    /**
     * An introspector of the fixtures of the other module and the friend module, without the marker class,
     * which the methods of the `Unreflectable` classes refer to, so that they cannot be reflected,
     * while their class files cannot be read either, being of an unsupported class file version.
     */
    private fun unreflectableIntrospector(): KotlinIntrospector {
        val root = tempDir.resolve("unreflectable")
        listOf(InternalBase::class.java, FriendModuleSubclass::class.java).forEach { fixture ->
            val dir = File(fixture.protectionDomain.codeSource.location.toURI())
            dir.walkTopDown()
                .filter { it.isFile && it.name != "OtherModuleMarker.class" }
                .forEach { it.copyTo(root.resolve(it.relativeTo(dir).path).toFile(), overwrite = true) }
        }
        val unreadableResources = setOf(
            "com/xemantic/kotlin/introspection/othermodule/UnreflectableSameModuleSubclass.class",
            "com/xemantic/kotlin/introspection/othermodule/UnreflectableOpenSameModuleSubclass.class",
            "com/xemantic/kotlin/introspection/friendmodule/UnreflectableFriendModuleSubclass.class",
            "com/xemantic/kotlin/introspection/friendmodule/UnreflectableOpenFriendModuleSubclass.class"
        )
        // a new Unreflectable fixture must be listed above, or it would stay readable
        val unreflectableFixtures = root.toFile().walkTopDown()
            .filter { it.isFile && it.name.startsWith("Unreflectable") }
            .map { it.relativeTo(root.toFile()).invariantSeparatorsPath }
            .toSet()
        assert(unreflectableFixtures == unreadableResources)
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
        return KotlinIntrospector(classLoader, scanEntireClasspath = false)
    }

    @Test
    fun `should tell overrides of internal members by module name in open classes which can be neither read nor reflected`() {
        // given
        val introspector = unreflectableIntrospector()

        // when
        val sameModule = introspector.resolve(
            "com.xemantic.kotlin.introspection.othermodule.UnreflectableOpenSameModuleSubclass"
        )
        val friendModule = introspector.resolve(
            "com.xemantic.kotlin.introspection.friendmodule.UnreflectableOpenFriendModuleSubclass"
        )

        // then
        // the JVM methods telling a mangled override cannot be read, and the modality tells nothing in an open class,
        // so a module of the same name is assumed to be the same module
        sameModule sameAsKotlin """
            // com.xemantic.kotlin.introspection.othermodule.UnreflectableOpenSameModuleSubclass [page 1/1]
            open class UnreflectableOpenSameModuleSubclass : InternalBase {
                override val state: Int
                fun take(marker: OtherModuleMarker)
            }
        """.trimIndent()
        // while an override from a friend module, of another name, is told only by the bridge, which cannot be seen
        friendModule sameAsKotlin """
            // com.xemantic.kotlin.introspection.friendmodule.UnreflectableOpenFriendModuleSubclass [page 1/1]
            open class UnreflectableOpenFriendModuleSubclass : com.xemantic.kotlin.introspection.othermodule.InternalBase {
                open val state: Int
                fun take(marker: com.xemantic.kotlin.introspection.othermodule.OtherModuleMarker)
            }
        """.trimIndent()
    }

    @Test
    fun `should keep the colliding module files of both modules named by default without top-level declarations`() {
        // given
        // both default-module fixtures write META-INF/main.kotlin_module onto the test classpath,
        // and only one of them would be seen by its resource name, depending on the classpath order,
        // so that a top-level declaration of either, mapped only there, might not be found
        // a dependency might ship a module file of the same name, which is not checked
        val fixtureRoots = listOf(
            "com.xemantic.kotlin.introspection.defaultmodule.DefaultModuleBase",
            "com.xemantic.kotlin.introspection.otherdefaultmodule.OtherDefaultModuleSubclass"
        ).map { Class.forName(it).protectionDomain.codeSource.location.toString() }
        val moduleFiles = javaClass.classLoader.getResources("META-INF/main.kotlin_module").toList().filter { url ->
            fixtureRoots.any { url.toString().startsWith(it) }
        }

        // when
        val packageParts = moduleFiles.map { url ->
            KotlinModuleMetadata.read(url.openStream().use { it.readBytes() }).kmModule.packageParts
        }

        // then
        // a top-level declaration belongs in a fixture of a module of its own name instead
        assert(moduleFiles.size == 2 && packageParts.all { it.isEmpty() })
    }

    @Test
    fun `should mark shrunk overrides of internal members of superclass of the same module named by default`() {
        // given
        val className = "com.xemantic.kotlin.introspection.defaultmodule.DefaultModuleSubclass"
        // like R8 removing both the override of clear and its bridge clear$main, keeping the metadata
        val introspector = shrunkIntrospector(className, "clear")

        // when
        val signature = introspector.resolve(className)

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultmodule.DefaultModuleSubclass [page 1/1]
            class DefaultModuleSubclass : DefaultModuleBase {
                override val state: Int
                override fun clear()
            }
        """.trimIndent()
    }

    /**
     * An introspector of the class named [className], whose JVM methods named like the [shrunkMembers]
     * are removed, together with their bridges, the names mangled with a module name, like R8 might,
     * while keeping the metadata.
     */
    private fun shrunkIntrospector(className: String, vararg shrunkMembers: String): KotlinIntrospector {
        val shrunkResource = className.replace('.', '/') + ".class"
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun getResourceAsStream(name: String): InputStream? {
                val stream = super.getResourceAsStream(name) ?: return null
                if (name != shrunkResource) return stream
                return withoutMethods(stream.use { it.readBytes() }) { methodName, _ ->
                    methodName.substringBefore('$') in shrunkMembers
                }.inputStream()
            }
        }
        return KotlinIntrospector(classLoader, scanEntireClasspath = false)
    }

    @Test
    fun `should not mark shrunk members named like internal members of superclass of another module as override`() {
        // given
        val className = "com.xemantic.kotlin.introspection.test.OtherModuleSubclass"
        val introspector = shrunkIntrospector(className, "reset", "getState")

        // when
        val signature = introspector.resolve(className)

        // then
        // the internal members of another module are not visible, so the shrunk members are not overrides
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OtherModuleSubclass [page 1/1]
            class OtherModuleSubclass : com.xemantic.kotlin.introspection.othermodule.InternalBase {
                val state: Int
                fun reset()
                override fun keep()
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark shrunk members named like PublishedApi internal members of superclass of another module as override`() {
        // given
        val className = "com.xemantic.kotlin.introspection.test.PublishedApiSubclass"
        val introspector = shrunkIntrospector(className, "publish")

        // when
        val signature = introspector.resolve(className)

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PublishedApiSubclass [page 1/1]
            class PublishedApiSubclass : com.xemantic.kotlin.introspection.othermodule.PublishedApiBase {
                fun publish()
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark shrunk overloads of public members as override when internal member of another module is matched`() {
        // given
        val className = "com.xemantic.kotlin.introspection.test.OverloadedInternalSubclass"
        val introspector = shrunkIntrospector(className, "reset")

        // when
        val signature = introspector.resolve(className)

        // then
        // the shrunk member matches the signature of the internal member, not of the public overload
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OverloadedInternalSubclass [page 1/1]
            class OverloadedInternalSubclass : com.xemantic.kotlin.introspection.othermodule.OverloadedInternalBase {
                fun reset()
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark shrunk overloads of public members as override when no member is matched`() {
        // given
        val className = "com.xemantic.kotlin.introspection.test.OverloadingInternalSubclass"
        val introspector = shrunkIntrospector(className, "reset")

        // when
        val signature = introspector.resolve(className)

        // then
        // the members of a Kotlin supertype are known by the metadata, also when their JVM methods are shrunk
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OverloadingInternalSubclass [page 1/1]
            class OverloadingInternalSubclass : com.xemantic.kotlin.introspection.othermodule.OverloadedInternalBase {
                fun reset(name: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark shrunk properties named like Java methods taking parameters as override`() {
        // given
        val className = "com.xemantic.kotlin.introspection.test.JavaLocalizedNameSubclass"
        val introspector = shrunkIntrospector(className, "getName")

        // when
        val signature = introspector.resolve(className)

        // then
        // a getter takes no parameters, unless the property has a receiver
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaLocalizedNameSubclass [page 1/1]
            class JavaLocalizedNameSubclass : com.xemantic.kotlin.introspection.test.other.JavaLocalizedNameBase {
                val name: String
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark shrunk overloads of members of classes of JDK modules defined by application class loader as override`() {
        // given
        val className = "com.xemantic.kotlin.introspection.test.DepthScanner"
        val introspector = shrunkIntrospector(className, "scan")

        // when
        val signature = introspector.resolve(className)

        // then
        // like jdk.compiler, which is a part of the JDK, though not defined by the platform class loader
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DepthScanner [page 1/1]
            abstract class DepthScanner : com.sun.source.util.TreeScanner<Unit, Unit> {
                fun scan(depth: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark shrunk properties named like getters of JDK classes as override`() {
        // given
        val className = "com.xemantic.kotlin.introspection.test.IndexedProperties"
        val introspector = shrunkIntrospector(className, "getProperty")

        // when
        val signature = introspector.resolve(className)

        // then
        // a shrinker never removes Properties.getProperty(String), so it is not taken for the shrunk overridden getter
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.IndexedProperties [page 1/1]
            class IndexedProperties : java.util.Properties {
                val Int.property: String
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark shrunk overloads of members of JDK classes as override`() {
        // given
        val exceptionName = "com.xemantic.kotlin.introspection.test.IndentedException"
        val listName = "com.xemantic.kotlin.introspection.test.OrderedList"
        val mutableListName = "com.xemantic.kotlin.introspection.test.OrderedMutableList"

        // when
        val exception = shrunkIntrospector(exceptionName, "toString").resolve(exceptionName)
        val list = shrunkIntrospector(listName, "sort").resolve(listName)
        val mutableList = shrunkIntrospector(mutableListName, "sort").resolve(mutableListName)

        // then
        // a shrinker never removes a JDK method, so its overload is not taken for its shrunk override
        list sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OrderedList [page 1/1]
            abstract class OrderedList : java.util.AbstractList<String> {
                fun sort(descending: Boolean)
            }
        """.trimIndent()
        mutableList sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OrderedMutableList [page 1/1]
            abstract class OrderedMutableList : MutableList<String> {
                fun sort(descending: Boolean)
            }
        """.trimIndent()
        exception sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.IndentedException [page 1/1]
            class IndentedException : java.lang.Exception {
                fun toString(indent: Int): String
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark shrunk overloads of members of JDK classes overridden by application Java classes as override`() {
        // given
        val exceptionName = "com.xemantic.kotlin.introspection.test.IndentedJavaException"
        val describedName = "com.xemantic.kotlin.introspection.test.IndentedJavaDescribed"
        val listName = "com.xemantic.kotlin.introspection.test.SizedJavaProbeList"
        val propertiesName = "com.xemantic.kotlin.introspection.test.IndexedJavaProperties"

        // when
        val exception = shrunkIntrospector(exceptionName, "toString").resolve(exceptionName)
        val described = shrunkIntrospector(describedName, "toString").resolve(describedName)
        val list = shrunkIntrospector(listName, "toArray").resolve(listName)
        val properties = shrunkIntrospector(propertiesName, "getProperty").resolve(propertiesName)

        // then
        // an override of a JDK method, like toString(), is kept as long as the JDK method is,
        // so it proves no shrunk override of its overload either
        exception sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.IndentedJavaException [page 1/1]
            class IndentedJavaException : com.xemantic.kotlin.introspection.test.other.JavaDescribedException {
                fun toString(indent: Int): String
            }
        """.trimIndent()
        // also of a method of Object, known as a member of Any
        described sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.IndentedJavaDescribed [page 1/1]
            class IndentedJavaDescribed : com.xemantic.kotlin.introspection.test.other.JavaDescribed {
                fun toString(indent: Int): String
            }
        """.trimIndent()
        // and of a JDK collection method hidden from the members of the Kotlin collection types
        list sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SizedJavaProbeList [page 1/1]
            abstract class SizedJavaProbeList : com.xemantic.kotlin.introspection.test.other.JavaProbeList {
                fun toArray(size: Int): Array<Any?>
            }
        """.trimIndent()
        properties sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.IndexedJavaProperties [page 1/1]
            class IndexedJavaProperties : com.xemantic.kotlin.introspection.test.other.JavaDefaultedProperties {
                val Int.property: String
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark shrunk overloads of raw and Object methods overridden by application Java classes as override`() {
        // given
        val executorName = "com.xemantic.kotlin.introspection.test.TaggedJavaProbeExecutor"
        val copyableName = "com.xemantic.kotlin.introspection.test.DeepJavaCopyable"

        // when
        val executor = shrunkIntrospector(executorName, "submit").resolve(executorName)
        val copyable = shrunkIntrospector(copyableName, "clone", "finalize").resolve(copyableName)

        // then
        // a raw override of the generic <T> submit(Runnable, T) of a JDK class overrides it all the same
        executor sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TaggedJavaProbeExecutor [page 1/1]
            abstract class TaggedJavaProbeExecutor : com.xemantic.kotlin.introspection.test.other.JavaProbeExecutor {
                fun submit(tag: String)
            }
        """.trimIndent()
        // the overrides of the methods of Object which are no members of Any, like clone() and finalize(), are no evidence either
        copyable sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DeepJavaCopyable [page 1/1]
            class DeepJavaCopyable : com.xemantic.kotlin.introspection.test.other.JavaCopyable {
                fun clone(deep: Boolean): Any
                fun finalize(timeout: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark shrunk overloads of application Java methods named like Kotlin members of built-in types as override`() {
        // given
        val className = "com.xemantic.kotlin.introspection.test.RadixCountingNumber"
        val introspector = shrunkIntrospector(className, "toInt")

        // when
        val signature = introspector.resolve(className)

        // then
        // Number.toInt() is intValue() on the JVM, so the Java toInt() overrides no JDK method, and stays the evidence,
        // as it might be shrunk together with its override
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.RadixCountingNumber [page 1/1]
            abstract class RadixCountingNumber : com.xemantic.kotlin.introspection.test.other.JavaCountingNumber {
                final override fun toInt(radix: Int): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should mark shrunk overrides of internal members of superclass of a friend module in final class`() {
        // given
        val className = "com.xemantic.kotlin.introspection.friendmodule.FriendModuleSubclass"
        // the bridge telling the friend module is removed as well
        val introspector = shrunkIntrospector(className, "getState")

        // when
        val signature = introspector.resolve(className)

        // then
        // only an override is open in a final class
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.friendmodule.FriendModuleSubclass [page 1/1]
            class FriendModuleSubclass : com.xemantic.kotlin.introspection.othermodule.InternalBase {
                override val state: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should keep inherited default values of shrunk overrides of internal members of superclass of a friend module`() {
        // given
        val className = "com.xemantic.kotlin.introspection.friendmodule.FriendModuleDefaultsSubclass"
        val introspector = shrunkIntrospector(className, "find")

        // when
        val signature = introspector.resolve(className)

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.friendmodule.FriendModuleDefaultsSubclass [page 1/1]
            class FriendModuleDefaultsSubclass : com.xemantic.kotlin.introspection.othermodule.InternalDefaultsBase {
                override fun find(limit: Int = …)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark shrunk overrides of PublishedApi internal members of superclass of a friend module in final class`() {
        // given
        val className = "com.xemantic.kotlin.introspection.friendmodule.FriendModulePublishedApiSubclass"
        val introspector = shrunkIntrospector(className, "publish")

        // when
        val signature = introspector.resolve(className)

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.friendmodule.FriendModulePublishedApiSubclass [page 1/1]
            class FriendModulePublishedApiSubclass : com.xemantic.kotlin.introspection.othermodule.PublishedApiBase {
                override fun publish()
            }
        """.trimIndent()
    }

    // the accepted trade-off of an unshrunk member applies, see the KotlinIntrospectorTest
    @Test
    fun `should not mark shrunk overrides of PublishedApi internal members of superclass of a friend module in open class`() {
        // given
        val className = "com.xemantic.kotlin.introspection.friendmodule.FriendModuleOpenPublishedApiSubclass"
        val introspector = shrunkIntrospector(className, "publish")

        // when
        val signature = introspector.resolve(className)

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.friendmodule.FriendModuleOpenPublishedApiSubclass [page 1/1]
            open class FriendModuleOpenPublishedApiSubclass : com.xemantic.kotlin.introspection.othermodule.PublishedApiBase {
                open fun publish()
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark shrunk final members named like internal members of superclass of another module of the same name as override`() {
        // given
        val className = "com.xemantic.kotlin.introspection.otherdefaultmodule.OtherDefaultModuleSubclass"
        val introspector = shrunkIntrospector(className, "reset", "getState")

        // when
        val signature = introspector.resolve(className)

        // then
        // a final member of a final class is not an override, though the module of the same name cannot be told
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.otherdefaultmodule.OtherDefaultModuleSubclass [page 1/1]
            class OtherDefaultModuleSubclass : com.xemantic.kotlin.introspection.defaultmodule.DefaultModuleBase {
                val state: Int
                fun reset()
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
        // and are rendered like any other member of the final class
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentJavaSubclass [page 1/1]
            class AbsentJavaSubclass : AbsentJavaBase<String> {
                fun process(item: String)
                fun process(item: Int)
                fun run()
                fun run(times: Int)
                fun prim(x: Int?)
                fun <S : String> bounded(x: S)
                fun <S : Number> bounded(x: S)
                fun take(nested: DollarHolder.`Dollar${'$'}Nested`)
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
        // the JVM methods of an interface owning an overload of a Java member are read,
        // also the ones relocated to DefaultImpls, which most interfaces do not have, compiled with the default -jvm-default
        val runner = introspector.resolve("com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsRunner")
        val runnable = introspector.resolve("com.xemantic.kotlin.introspection.test.OverloadingRunnableInterface")

        // then
        runner sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsRunner [page 1/1]
            interface DefaultImplsRunner : java.lang.Runnable {
                open fun run(times: Int)
            }
        """.trimIndent()
        runnable sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OverloadingRunnableInterface [page 1/1]
            interface OverloadingRunnableInterface : java.lang.Runnable {
                fun run(times: Int)
            }
        """.trimIndent()
        assert(requestedClasses.none { it.endsWith("\$DefaultImpls") })
    }

    @Test
    fun `should not load DefaultImpls class of interface whose class file is served without the one of DefaultImpls`() {
        // given
        val defaultImplsName = "com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsRunner\$DefaultImpls"
        val requestedClasses = Collections.synchronizedList(mutableListOf<String>())
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                requestedClasses += name
                return super.loadClass(name, resolve)
            }

            override fun getResource(name: String): java.net.URL? =
                if (name == defaultImplsName.replace('.', '/') + ".class") null else super.getResource(name)

            override fun getResourceAsStream(name: String): InputStream? =
                if (name == defaultImplsName.replace('.', '/') + ".class") null else super.getResourceAsStream(name)
        }
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        // the JVM methods of the interface are read to tell whether its overload of a Java member is shrunk
        val runner = introspector.resolve("com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsRunner")

        // then
        runner sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsRunner [page 1/1]
            interface DefaultImplsRunner : java.lang.Runnable {
                open fun run(times: Int)
            }
        """.trimIndent()
        // the class file of the interface is served, so the absence of the one of DefaultImpls tells there is none
        assert(defaultImplsName !in requestedClasses)
    }

    @Test
    fun `should not probe DefaultImpls classes of interfaces without member bodies when no class file is served`() {
        // given
        val failedLoads = Collections.synchronizedList(mutableListOf<String>())
        // like an in-memory class loader, defining the classes without serving their class files as resources
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = try {
                super.loadClass(name, resolve)
            } catch (e: ClassNotFoundException) {
                failedLoads += name
                throw e
            }

            override fun getResource(name: String): java.net.URL? =
                if (name.startsWith("com/xemantic/")) null else super.getResource(name)

            override fun getResourceAsStream(name: String): InputStream? =
                if (name.startsWith("com/xemantic/")) null else super.getResourceAsStream(name)
        }
        val introspector = KotlinIntrospector(classLoader, scanEntireClasspath = false)

        // when
        // the JVM methods of an interface owning an overload of a Java member are reflected,
        // also the ones relocated to DefaultImpls, which only an interface with member bodies might have,
        // as its metadata tells
        val runner = introspector.resolve("com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsRunner")
        val runnable = introspector.resolve("com.xemantic.kotlin.introspection.test.OverloadingRunnableInterface")

        // then
        runner sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsRunner [page 1/1]
            interface DefaultImplsRunner : java.lang.Runnable {
                open fun run(times: Int)
            }
        """.trimIndent()
        runnable sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OverloadingRunnableInterface [page 1/1]
            interface OverloadingRunnableInterface : java.lang.Runnable {
                fun run(times: Int)
            }
        """.trimIndent()
        assert(failedLoads.none { it.endsWith("\$DefaultImpls") })
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
        val baseName = "com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsBase"
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                when (name) {
                    defaultImplsName -> throw ClassNotFoundException(name)
                    // like when a supertype of the class is missing, so that it is read from its class file
                    baseName -> throw NoClassDefFoundError(name)
                    else -> super.loadClass(name, resolve)
                }

            override fun getResourceAsStream(name: String): InputStream? {
                val stream = super.getResourceAsStream(name) ?: return null
                val bytes = stream.use { it.readBytes() }
                return when (name) {
                    // the supertype is known only by its JVM methods, like a Java one,
                    // so that the overload cannot be told from an override of a JVM method removed as well
                    baseName.replace('.', '/') + ".class" -> withoutMetadata(bytes)
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
        // and the method of the malformed descriptor is unknown, so their overrides are not told,
        // rendered like any other member of the final class
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.AbsentJavaSubclass [page 1/1]
            class AbsentJavaSubclass : AbsentJavaBase<String> {
                fun process(item: String)
                fun process(item: Int)
                fun run()
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
    fun `should neither resolve hidden nested class nor hidden companion object whose class files are not served as resources`() {
        // given
        val introspector = KotlinIntrospector(unservedDeprecatedNestedClassLoader())

        // when
        val companion = introspector.resolve("com.xemantic.kotlin.introspection.isolated.DeprecatedNestedHolder.Companion")
        val companionMember = introspector.resolve(
            "com.xemantic.kotlin.introspection.isolated.DeprecatedNestedHolder.Companion.create"
        )
        val deprecated = introspector.resolve("com.xemantic.kotlin.introspection.isolated.DeprecatedNestedHolder.Old")

        // then
        // their deprecations are reflected, telling which ones are part of the API
        assert(companion == null)
        assert(companionMember == null)
        deprecated sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.DeprecatedNestedHolder.Old [page 1/1]
            @Deprecated("Old") class Old
        """.trimIndent()
    }

    @Test
    fun `should tell hidden declarations of class whose class file is not served as a resource`() {
        // given
        val root = isolatedTestFixturesCopy("unservedHiddenConstructor")
        val classLoader = object : URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader) {
            override fun getResourceAsStream(name: String): InputStream? =
                if (name.endsWith("/HiddenConstructorBase.class")) null else super.getResourceAsStream(name)
        }
        val introspector = KotlinIntrospector(classLoader)

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.HiddenConstructorBase")

        // then
        // their deprecations are reflected, telling that the class cannot be subclassed, and so has no protected members
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.HiddenConstructorBase [page 1/1]
            open class HiddenConstructorBase private constructor() {
                fun visible()
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve member of synthetic companion object through outer class`() {
        // given
        val root = tempDir.resolve("syntheticCompanionMember")
        isolatedTestFixtures.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val target = root.resolve(file.relativeTo(dir).path).toFile()
                target.parentFile.mkdirs()
                target.writeBytes(
                    if (file.name == "SyntheticCompanionHolder\$Companion.class") synthetic(file.readBytes()) else file.readBytes()
                )
            }
        }
        val introspector = KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))

        // when
        val member = introspector.resolve("com.xemantic.kotlin.introspection.isolated.SyntheticCompanionHolder.create")

        // then
        assert(member == null)
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

    @Test
    fun `should list extensions of standard library with built-in type without looking up their class files`() {
        // given
        // which library an extension comes from is told when indexing the classpath, see isBuiltinExtensionSource,
        // not by looking up its class file on every listing, where a failure, maybe transient, would hide it
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun getResource(name: String): java.net.URL? =
                if (name == "kotlin/text/StringsKt__IndentKt.class") throw IllegalStateException("closed: $name")
                else super.getResource(name)
        }
        val introspector = KotlinIntrospector(classLoader)

        // when
        val signature = introspector.resolve("kotlin.String.trimIndent")

        // then
        signature sameAs """
            // kotlin.String.trimIndent [page 1/1]
            // from kotlin.text
            fun String.trimIndent(): String
        """.trimIndent()
    }

    @Test
    fun `should not list extensions of other library with built-in type when merged with standard library in one jar`() {
        // given
        // like a shadow jar, merging kotlin-metadata-jvm, which declares fun ClassName.isLocalClassName() on a String,
        // with the standard library, told apart by the Kotlin modules their classes belong to
        val shadowJar = tempDir.resolve("shadow.jar")
        val libraries = listOf(Unit::class.java, Class.forName("kotlin.metadata.jvm.JvmExtensionsKt")).map {
            File(it.protectionDomain.codeSource.location.toURI())
        }
        JarOutputStream(shadowJar.outputStream()).use { out ->
            val written = HashSet<String>()
            libraries.forEach { library ->
                java.util.jar.JarFile(library).use { jar ->
                    jar.entries().asSequence()
                        .filter { !it.isDirectory && !it.name.startsWith("META-INF/versions/") && written.add(it.name) }
                        .forEach { entry ->
                            out.putNextEntry(JarEntry(entry.name))
                            jar.getInputStream(entry).use { it.copyTo(out) }
                            out.closeEntry()
                        }
                }
            }
        }
        // when
        // closed, not to keep the jar open, which could not be deleted on Windows
        val signatures = URLClassLoader(arrayOf(shadowJar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use {
            val introspector = KotlinIntrospector(it)
            listOf("kotlin.String.isLocalClassName", "kotlin.String.trimIndent").map { name -> introspector.resolve(name) }
        }

        // then
        assert(signatures[0] == null)
        assert(signatures[1]?.contains("fun String.trimIndent(): String") == true)
    }

    @Test
    fun `should render legacy context receivers of class`() {
        // given
        val introspector = introspectorWithLegacyContextReceivers()

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.LegacyContextReceiverClass")

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.LegacyContextReceiverClass [page 1/1]
            context(LegacyLogger, Comparator<T>) /* legacy context receivers, not compiled by Kotlin 2.2+ */ class LegacyContextReceiverClass<T> {
                fun log()
            }
        """.trimIndent()
    }

    @Test
    fun `should render legacy context receivers of class after its annotations`() {
        // given
        val introspector = introspectorWithLegacyContextReceivers()

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.LegacyContextReceiverValue")

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.LegacyContextReceiverValue [page 1/1]
            @Deprecated("Use LegacyContextReceiverClass") @JvmInline context(LegacyLogger) /* legacy context receivers, not compiled by Kotlin 2.2+ */ value class LegacyContextReceiverValue(val value: Int) {
                override operator fun equals(other: Any?): Boolean
                override fun hashCode(): Int
                override fun toString(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should render legacy context receivers of inner class with the type parameters of its outer class`() {
        // given
        val introspector = introspectorWithLegacyContextReceivers()

        // when
        val signature = introspector.resolve("com.xemantic.kotlin.introspection.isolated.LegacyContextReceiverOuter")

        // then
        signature sameAsKotlin """
            // com.xemantic.kotlin.introspection.isolated.LegacyContextReceiverOuter [page 1/1]
            class LegacyContextReceiverOuter<T> {
                context(Comparator<T>) /* legacy context receivers, not compiled by Kotlin 2.2+ */ inner class Inner
            }
        """.trimIndent()
    }

    @Test
    fun `should list extension of standard library with built-in type by Kotlin package of its file facade`() {
        // given - like the file facades of kotlin-stdlib relocated to another JVM package by @JvmPackageName
        val introspector = introspectorWithStdlibFacade("jvmPackageName", "kotlin/text/jdk8/IsolatedTestClassKt")

        // when
        val signatures = introspector.resolve("kotlin.String.isolatedExtension")

        // then
        signatures sameAsKotlin """
            // kotlin.String.isolatedExtension [page 1/1]
            // from kotlin.text
            fun String.isolatedExtension(): Int
        """.trimIndent()
    }

    @Test
    fun `should not list extension of standard library relocated by shading with built-in type`() {
        // given - like relocated by shading, keeping the Kotlin package in the metadata
        val introspector = introspectorWithStdlibFacade("shadedStdlib", "shaded/kotlin/text/IsolatedTestClassKt")

        // when
        val signatures = introspector.resolve("kotlin.String.isolatedExtension")

        // then
        assert(signatures == null)
    }

    /**
     * An introspector of the isolated test fixtures, with the file facade of `IsolatedTestClass.kt` moved to the JVM class
     * of the [binaryName], declaring `fun String.isolatedExtension()` in the `kotlin.text` package of `kotlin-stdlib`,
     * copied to the [name] directory.
     */
    private fun introspectorWithStdlibFacade(name: String, binaryName: String): KotlinIntrospector {
        val root = isolatedTestFixturesCopy(name, "IsolatedTestClassKt.class")
        val original = isolatedTestFixtures
            .map { it.resolve("com/xemantic/kotlin/introspection/isolated/IsolatedTestClassKt.class") }
            .single { it.exists() }
        val facade = withKotlinPackageInMetadata(original.readBytes(), "kotlin.text") {
            it.moduleName = "kotlin-stdlib"
            it.functions.single { function -> function.name == "isolatedExtension" }.receiverParameterType =
                KmType().apply { classifier = KmClassifier.Class("kotlin/String") }
        }
        val relocated = root.resolve("$binaryName.class")
        relocated.parent.createDirectories()
        relocated.writeBytes(withClassName(facade, binaryName))
        return KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))
    }

    /**
     * An introspector of the isolated test fixtures, with the legacy context receivers added to the metadata
     * of the classes of `LegacyContextReceivers.kt`, like compiled with `-Xcontext-receivers`, see [withLegacyContextReceivers].
     */
    private fun introspectorWithLegacyContextReceivers(): KotlinIntrospector {
        val root = isolatedTestFixturesCopy("legacyContextReceivers", transform = ::withLegacyContextReceivers)
        return KotlinIntrospector(URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader))
    }

}
