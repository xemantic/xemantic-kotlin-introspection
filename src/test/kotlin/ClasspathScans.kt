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

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path

/*
 * Scanning the classpath of the test fixtures, see DefaultKotlinIntrospector.scanClasspath.
 */

/**
 * A class loader of only the [roots], directories or jars, not of the test classpath,
 * to be closed, so that the roots can be deleted, like on Windows, where an open jar cannot be.
 */
internal fun rootsClassLoader(vararg roots: Path): URLClassLoader = URLClassLoader(
    roots.map { it.toUri().toURL() }.toTypedArray(),
    ClassLoader.getPlatformClassLoader()
)

/**
 * Scans the classpath of only the [roots], see [rootsClassLoader], closing the class loader afterwards.
 */
internal fun scan(
    vararg roots: Path,
    skipNestedClassFiles: Boolean = true
): DefaultKotlinIntrospector.ClasspathScan = rootsClassLoader(*roots).use { scan(it, skipNestedClassFiles) }

internal fun scan(
    classLoader: ClassLoader,
    skipNestedClassFiles: Boolean = true
): DefaultKotlinIntrospector.ClasspathScan = DefaultKotlinIntrospector(classLoader, scanEntireClasspath = false)
    .scanClasspath(skipNestedClassFiles, collectSkippedClassFiles = true)

/**
 * The extensions and classes indexed by the classpath scan, one per line, in the scan order,
 * and, [withClasses], all the Kotlin classes found, with their flags, which decide what is indexed.
 */
internal fun DefaultKotlinIntrospector.ClasspathScan.indexDump(withClasses: Boolean = true): String = buildList {
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

internal fun testFixtures(property: String): List<File> = System.getProperty(property)
    .split(File.pathSeparator)
    .map { File(it) }
    .filter { it.exists() }

/**
 * Copies the files of the [fixtures] directories to the [root],
 * without the ones [isAbsent] tells by their file names, transforming the bytes by their file names,
 * like the class files of the isolated test fixtures, see [testFixtures].
 */
internal fun testFixturesCopy(
    fixtures: List<File>,
    root: Path,
    isAbsent: (fileName: String) -> Boolean = { false },
    transform: (fileName: String, bytes: ByteArray) -> ByteArray = { _, bytes -> bytes }
): Path {
    fixtures.forEach { dir ->
        dir.walkTopDown().filter { it.isFile && !isAbsent(it.name) }.forEach { file ->
            val target = root.resolve(file.relativeTo(dir).path).toFile()
            target.parentFile.mkdirs()
            target.writeBytes(transform(file.name, file.readBytes()))
        }
    }
    return root
}
