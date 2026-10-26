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
import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.metadata.KmPackage
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.test.Test

/**
 * Parses, with the parser of the Kotlin compiler, every declaration rendered for the classes of the classpath of the tests,
 * the standard library, the other libraries, and the test fixtures, like the ones named like hard keywords,
 * so that a rendering path missing the escaping of an identifier, or rendering something else which is not Kotlin, is caught.
 *
 * The rendered listings are not entirely Kotlin, and the parse tolerates, see [toParsableKotlin]:
 * - the comments, like the `// in protected …` one, which the parser skips anyway,
 *   except the `// from …` header, telling the package, or the object, to import the extensions from,
 *   parsed as a qualified expression, like `val from = com.example.`in``, as it has to be escaped like in an import,
 * - the `…` marker of a default value, like `limit: Int = …`, replaced with `TODO()`,
 * - the header of a class listed in another one, like a sealed subclass, named relative to it, or to its package,
 *   like `class Holder.Nested`, or `inner class Outer<T>.Sub`, which the README documents as a reference,
 *   whose qualified name is parsed as a type instead, like `Outer<T>.Sub`, still checking that each segment is escaped.
 *
 * The declarations without bodies, like `fun foo(): Int`, or `val x: Int` without an initializer, parse as they are,
 * as these are semantic, not syntactic, errors.
 *
 * The classpath of the tests is introspected through its own class loader,
 * not to introspect the compiler itself, on the classpath of this test.
 */
class RenderedDeclarationsParseTest {

    @Test
    fun `should render every declaration of the classpath as Kotlin`() {
        // given
        val (listings, errors) = introspectedClassLoader().use { classLoader ->
            val introspector = KotlinIntrospector(classLoader)

            // when
            val listings = renderedListings(introspector, classLoader)
            listings to listings.flatMap { (name, listing) ->
                parseErrors(toParsableKotlin(listing)).map { "$name: $it" }
            }
        }

        // then
        // the classes of the standard library alone are a few thousand
        assert(listings.size > 1000)
        // a built-in type, and a top-level function of a multi-file facade
        assert("kotlin.String" in listings)
        assert("kotlin.collections.CollectionsKt.listOf" in listings)
        // the fixtures named like hard keywords, both a class, and a top-level function
        assert("com.xemantic.kotlin.introspection.test.KeywordMembersSample" in listings)
        assert("com.xemantic.kotlin.introspection.test.in.TestKeywordPackageKt.keywordPackageFunction" in listings)
        // the top-level properties without getters, a const val, and a @JvmField, and one whose getter is getURL
        assert("com.xemantic.kotlin.introspection.test.in.TestKeywordPackageKt.KEYWORD_PACKAGE_CONSTANT" in listings)
        assert("com.xemantic.kotlin.introspection.test.in.TestKeywordPackageKt.keywordPackageField" in listings)
        assert("com.xemantic.kotlin.introspection.test.in.TestKeywordPackageKt.URL" in listings)
        assert(errors.isEmpty())
    }

    @Test
    fun `should report declaration which does not parse as Kotlin`() {
        // when
        val errors = parseErrors("fun in(): Int")

        // then
        assert(errors.isNotEmpty())
    }

    @Test
    fun `should parse qualified name of listed class header as type`() {
        // when
        val parsable = toParsableKotlin("    inner class Outer<T>.`in` : Sealed<T>")

        // then
        assert(parsable == "    inner class `in` : Sealed<T>; val reference: Outer<T>.`in`")
        assert(parseErrors("class Listing {\n$parsable\n}").isEmpty())
    }

    @Test
    fun `should parse from header as qualified expression`() {
        // when
        val parsable = toParsableKotlin("// from com.example.`in`\nfun Foo.bar()")

        // then
        assert(parsable == "val from = com.example.`in`\nfun Foo.bar()")
        assert(parseErrors(parsable).isEmpty())
        assert(parseErrors(toParsableKotlin("// from com.example.in")).isNotEmpty())
    }

    @Test
    fun `should parse legacy context receivers of class`(@TempDir tempDir: Path) {
        // given - the classes compiled with -Xcontext-receivers, not on the classpath introspected by the other test,
        // as the current compiler no longer compiles them, see withLegacyContextReceivers
        val listings = legacyContextReceiversClassLoader(tempDir).use { classLoader ->
            val introspector = KotlinIntrospector(classLoader)

            // when
            listOf("LegacyContextReceiverClass", "LegacyContextReceiverValue", "LegacyContextReceiverOuter").map { name ->
                introspector.resolve("com.xemantic.kotlin.introspection.isolated.$name")!!
            }
        }
        val errors = listings.flatMap { parseErrors(toParsableKotlin(it)) }

        // then
        assert(listings.all { "context(" in it })
        assert(errors.isEmpty())
    }

    @Test
    fun `should report unescaped segment of qualified name of listed class header`() {
        // when
        val errors = parseErrors("class Listing {\n${toParsableKotlin("    class in.Nested")}\n}")

        // then
        assert(errors.isNotEmpty())
    }

    @OptIn(CoreEnvironmentDeprecation::class)
    companion object {

        /**
         * The environment of the parser, shared by the tests, as setting it up takes a while.
         */
        private val disposable = Disposer.newDisposable()

        private val psiFactory = KtPsiFactory(
            KotlinCoreEnvironment.createForProduction(
                disposable,
                CompilerConfiguration.create(),
                EnvironmentConfigFiles.JVM_CONFIG_FILES
            ).project,
            markGenerated = false
        )

        @AfterAll
        @JvmStatic
        fun disposeEnvironment() {
            Disposer.dispose(disposable)
        }

        /**
         * The errors of parsing the [kotlin] source, each with the line it is found on.
         */
        private fun parseErrors(kotlin: String): List<String> {
            val file = psiFactory.createFile("Listing.kt", kotlin)
            return PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).map { error ->
                val offset = error.textRange.startOffset
                val line = kotlin.substring(kotlin.lastIndexOf('\n', offset - 1) + 1).substringBefore('\n')
                "${error.errorDescription} in: $line"
            }
        }

    }

}

/**
 * The classes of the classpath of the tests, see the `parseTest` suite in the build script,
 * where the `kotlin.*` classes are the ones of this test, sharing the `kotlin.Metadata` annotation with kotlin-reflect,
 * and nothing else is visible, also not the resources, telling the classpath of the parent class loader.
 */
private fun introspectedClassLoader(): URLClassLoader =
    URLClassLoader(introspectedClasspath.map { it.toURI().toURL() }.toTypedArray(), kotlinClassLoader)

/**
 * The parent of a class loader of the introspected classes, see [introspectedClassLoader].
 */
private val kotlinClassLoader = object : ClassLoader(getPlatformClassLoader()) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        if (name.startsWith("kotlin.")) RenderedDeclarationsParseTest::class.java.classLoader.loadClass(name)
        else super.loadClass(name, resolve)
}

/**
 * The isolated test fixtures, see `ClassLoaderIntrospectionTest`, copied to the [root],
 * with the legacy context receivers added to the metadata of their classes, see [withLegacyContextReceivers],
 * seen through their own class loader, like the [introspectedClassLoader].
 */
private fun legacyContextReceiversClassLoader(root: Path): URLClassLoader {
    requireNotNull(System.getProperty("isolatedTestFixtures")) { "run via ./gradlew parseTest" }
        .split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File).filter { it.exists() }
        .forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val target = root.resolve(file.relativeTo(dir).path).toFile()
                target.parentFile.mkdirs()
                target.writeBytes(withLegacyContextReceivers(file.name, file.readBytes()))
            }
        }
    return URLClassLoader(arrayOf(root.toUri().toURL()), kotlinClassLoader)
}

private val introspectedClasspath: List<File> by lazy {
    requireNotNull(System.getProperty("introspectedClasspath")) { "run via ./gradlew parseTest" }
        .split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File)
}

/**
 * The listing of every class found by the search, of every built-in type, which has no class file to find,
 * and of every top-level declaration, see [topLevelDeclarationNames], rendered on a single page, by the name it is resolved by.
 */
private fun renderedListings(introspector: KotlinIntrospector, classLoader: ClassLoader): Map<String, String> {
    val classNames = introspector.search("", pageSize = Int.MAX_VALUE)!!.lines().drop(1)
    val builtinTypeNames = DefaultKotlinIntrospector.builtinTypes.keys
    return (classNames + builtinTypeNames + topLevelDeclarationNames(classLoader)).associateWith { name ->
        introspector.resolve(name, pageSize = Int.MAX_VALUE)
    }.filterValues { it != null }.mapValues { (_, listing) -> listing!! }
}

/**
 * The names of the top-level functions and properties, which are not listed in any class, unlike the extensions,
 * qualified with their file facade, like `kotlin.collections.CollectionsKt.listOf`, as read from its Kotlin metadata,
 * or from the one of its parts, for the facade of several files.
 * The ones which are not part of the API, like the internal ones, resolve to nothing.
 * A facade which cannot be loaded, or read, is skipped.
 */
private fun topLevelDeclarationNames(classLoader: ClassLoader): List<String> = introspectedClasspath
    .flatMap(::classNames)
    .filter { '$' !in it }
    .flatMap { className ->
        try {
            val kmPackages = when (val metadata = kotlinMetadata(className, classLoader)) {
                is KotlinClassMetadata.FileFacade -> listOf(metadata.kmPackage)
                is KotlinClassMetadata.MultiFileClassFacade -> metadata.partClassNames.mapNotNull { partName ->
                    (kotlinMetadata(partName.replace('/', '.'), classLoader) as? KotlinClassMetadata.MultiFileClassPart)?.kmPackage
                }
                else -> emptyList()
            }
            kmPackages.flatMap(KmPackage::declarationNames).distinct().map { "$className.$it" }
        } catch (_: LinkageError) {
            emptyList()
        } catch (_: ClassNotFoundException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            // the metadata which cannot be read
            emptyList()
        }
    }
    .sorted()

private fun kotlinMetadata(className: String, classLoader: ClassLoader): KotlinClassMetadata? =
    Class.forName(className, false, classLoader).getAnnotation(Metadata::class.java)?.let(KotlinClassMetadata::readLenient)

private val KmPackage.declarationNames: List<String>
    get() = functions.map { it.name } + properties.map { it.name }

/**
 * The binary names of the classes of the classpath [root], a jar, or a directory.
 */
private fun classNames(root: File): List<String> = when {
    root.isDirectory -> root.walk()
        .filter { it.isFile && it.name.endsWith(".class") }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .toList()
    root.isFile && root.name.endsWith(".jar") -> JarFile(root).use { jar ->
        jar.entries().asSequence().map { it.name }.filter { it.endsWith(".class") && !it.startsWith("META-INF/") }.toList()
    }
    else -> emptyList()
}.filter { it != "module-info.class" }.map { it.removeSuffix(".class").replace('/', '.') }

/**
 * The header of a class listed in another one, named relative to it, or to its package, see [toParsableKotlin].
 */
private val listedClassHeader = Regex(
    """^(.*\b(?:class|interface|object) )((?:$IDENTIFIER(?:<[^<>]*>)?\.)+$IDENTIFIER)(.*)$"""
)

private const val FROM_HEADER = "// from "

private const val IDENTIFIER = """(?:`[^`]+`|[\p{L}_][\p{L}\p{N}_]*)"""

/**
 * The last segment of a qualified name, see [listedClassHeader].
 */
private val lastIdentifier = Regex("$IDENTIFIER$")

/**
 * The rendered [listing] made parsable, see [RenderedDeclarationsParseTest].
 */
private fun toParsableKotlin(listing: String): String = listing.lines().joinToString("\n") { line ->
    if (line.startsWith(FROM_HEADER)) return@joinToString "val from = ${line.removePrefix(FROM_HEADER)}"
    if (line.trimStart().startsWith("//")) return@joinToString line
    val withDefaultValues = line.replace("= …", "= TODO()")
    listedClassHeader.matchEntire(withDefaultValues)?.let { match ->
        val (prefix, qualifiedName, suffix) = match.destructured
        val simpleName = lastIdentifier.find(qualifiedName)!!.value
        "$prefix$simpleName$suffix; val reference: $qualifiedName"
    } ?: withDefaultValues
}
