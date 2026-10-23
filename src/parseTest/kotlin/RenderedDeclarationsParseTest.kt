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
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.jar.JarFile
import kotlin.test.AfterTest
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
@OptIn(CoreEnvironmentDeprecation::class)
class RenderedDeclarationsParseTest {

    private val disposable = Disposer.newDisposable()

    private val psiFactory = KtPsiFactory(
        KotlinCoreEnvironment.createForProduction(
            disposable,
            CompilerConfiguration.create(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        ).project,
        markGenerated = false
    )

    @AfterTest
    fun disposeEnvironment() {
        Disposer.dispose(disposable)
    }

    @Test
    fun `should render every declaration of the classpath as Kotlin`() {
        // given
        val introspector = KotlinIntrospector(introspectedClassLoader)

        // when
        val listings = renderedListings(introspector)
        val errors = listings.flatMap { (name, listing) ->
            parseErrors(toParsableKotlin(listing)).map { "$name: $it" }
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
    fun `should report unescaped segment of qualified name of listed class header`() {
        // when
        val errors = parseErrors("class Listing {\n${toParsableKotlin("    class in.Nested")}\n}")

        // then
        assert(errors.isNotEmpty())
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

/**
 * The classes of the classpath of the tests, see the `parseTest` suite in the build script,
 * where the `kotlin.*` classes are the ones of this test, sharing the `kotlin.Metadata` annotation with kotlin-reflect,
 * and nothing else is visible, also not the resources, telling the classpath of the parent class loader.
 */
private val introspectedClassLoader: URLClassLoader by lazy {
    val kotlinClasses = object : ClassLoader(getPlatformClassLoader()) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            if (name.startsWith("kotlin.")) RenderedDeclarationsParseTest::class.java.classLoader.loadClass(name)
            else super.loadClass(name, resolve)
    }
    URLClassLoader(introspectedClasspath.map { it.toURI().toURL() }.toTypedArray(), kotlinClasses)
}

private val introspectedClasspath: List<File> =
    System.getProperty("introspectedClasspath").split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File)

/**
 * The listing of every class found by the search, of every built-in type, which has no class file to find,
 * and of every top-level declaration, see [topLevelDeclarationNames], rendered on a single page, by the name it is resolved by.
 */
private fun renderedListings(introspector: KotlinIntrospector): Map<String, String> {
    val classNames = introspector.search("", pageSize = Int.MAX_VALUE)!!.lines().drop(1)
    val builtinTypeNames = DefaultKotlinIntrospector.builtinTypes.keys
    return (classNames + builtinTypeNames + topLevelDeclarationNames()).associateWith { name ->
        introspector.resolve(name, pageSize = Int.MAX_VALUE)
    }.filterValues { it != null }.mapValues { (_, listing) -> listing!! }
}

/**
 * The names of the top-level declarations, which are not listed in any class, unlike the extensions,
 * like `kotlin.collections.CollectionsKt.listOf`, guessed from the public static methods of the file facades,
 * a property from the name of its getter, like `foo` of `getFoo`; the names which are not declarations resolve to nothing.
 */
private fun topLevelDeclarationNames(): List<String> = introspectedClasspath
    .flatMap(::classNames)
    .filter { '$' !in it }
    .mapNotNull { className ->
        try {
            Class.forName(className, false, introspectedClassLoader).takeIf { isFileFacade(it) }
        } catch (_: LinkageError) {
            null
        } catch (_: ClassNotFoundException) {
            null
        }
    }
    .flatMap { facade ->
        // the methods of a multi-file facade of the standard library are inherited from its parts
        facade.methods
            .filter { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filter { '$' !in it && '-' !in it }
            .flatMap { name -> listOfNotNull(name, propertyName(name)) }
            .distinct()
            .map { "${facade.name}.$it" }
    }
    // the methods are in no particular order
    .sorted()

private fun propertyName(accessorName: String): String? = listOf("get", "set").firstNotNullOfOrNull { prefix ->
    accessorName.removePrefix(prefix).takeIf { it != accessorName && it.firstOrNull()?.isUpperCase() == true }
        ?.replaceFirstChar { it.lowercase() }
}

/**
 * Whether the [clazz] is the file facade of a single file, or of several files, by the kind of its `kotlin.Metadata`.
 */
private fun isFileFacade(clazz: Class<*>): Boolean {
    val metadata = clazz.getAnnotation(Metadata::class.java) ?: return false
    return metadata.kind == 2 || metadata.kind == 4
}

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
 * The rendered [listing] made parsable, see [RenderedDeclarationsParseTest].
 */
private fun toParsableKotlin(listing: String): String = listing.lines().joinToString("\n") { line ->
    if (line.startsWith(FROM_HEADER)) return@joinToString "val from = ${line.removePrefix(FROM_HEADER)}"
    if (line.trimStart().startsWith("//")) return@joinToString line
    val withDefaultValues = line.replace("= …", "= TODO()")
    listedClassHeader.matchEntire(withDefaultValues)?.let { match ->
        val (prefix, qualifiedName, suffix) = match.destructured
        val simpleName = Regex("$IDENTIFIER$").find(qualifiedName)!!.value
        "$prefix$simpleName$suffix; val reference: $qualifiedName"
    } ?: withDefaultValues
}
