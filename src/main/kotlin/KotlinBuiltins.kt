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

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.metadata.KmClass
import kotlin.metadata.internal.common.KotlinCommonMetadata
import kotlin.streams.asSequence

private val logger = KotlinLogging.logger {}

private const val BUILTINS_EXTENSION = ".kotlin_builtins"

/**
 * The `.kotlin_builtins` resource present in every standard library, telling where the others are.
 */
private const val ANCHOR_BUILTINS_RESOURCE = "kotlin/kotlin$BUILTINS_EXTENSION"

/**
 * The `.kotlin_builtins` resources of the standard library known at the time of writing,
 * read only when the resources cannot be listed.
 */
private val knownBuiltinsResources = listOf(
    ANCHOR_BUILTINS_RESOURCE,
    "kotlin/annotation/annotation$BUILTINS_EXTENSION",
    "kotlin/collections/collections$BUILTINS_EXTENSION",
    "kotlin/concurrent/atomics/atomics$BUILTINS_EXTENSION",
    "kotlin/coroutines/coroutines$BUILTINS_EXTENSION",
    "kotlin/internal/internal$BUILTINS_EXTENSION",
    "kotlin/ranges/ranges$BUILTINS_EXTENSION",
    "kotlin/reflect/reflect$BUILTINS_EXTENSION"
)

/**
 * Reads the declarations of the built-in types from the `.kotlin_builtins` resources of the standard library,
 * by their Kotlin class names, like `kotlin/collections/List`.
 */
internal fun readKotlinBuiltinClasses(): Map<String, KmClass> = kotlinBuiltinsResources().flatMap { resource ->
    // read through a class of the standard library, as its resources are encapsulated when it is a named module
    val stream = KotlinVersion::class.java.getResourceAsStream("/$resource")
    if (stream == null) {
        logger.warn { "Cannot read the Kotlin built-in declarations of $resource" }
    }
    stream?.use { KotlinCommonMetadata.read(it) }?.kmModuleFragment?.classes.orEmpty()
}.associateBy { it.name }

/**
 * Lists the `.kotlin_builtins` resources of the standard library, as paths relative to its root, like
 * `kotlin/collections/collections.kotlin_builtins`, in the root containing the [anchor] resource:
 * a jar, also a nested one of a fat jar if its URL handler supports it, a directory, or a module of the runtime image.
 *
 * As all the built-in types are declared in the `kotlin` package, or its subpackages, only these are listed.
 * If the resources cannot be listed, the known ones are returned, and a warning is logged.
 *
 * @param anchor the URL of the `kotlin/kotlin.kotlin_builtins` resource.
 */
internal fun kotlinBuiltinsResources(
    anchor: URL? = KotlinVersion::class.java.getResource("/$ANCHOR_BUILTINS_RESOURCE")
): List<String> {
    if (anchor == null) {
        logger.warn { "Cannot find $ANCHOR_BUILTINS_RESOURCE of the Kotlin standard library" }
        return knownBuiltinsResources
    }
    return try {
        val resources = if (anchor.protocol == "jar") jarBuiltinsResources(anchor) else pathBuiltinsResources(anchor)
        resources.filter { it.startsWith("kotlin/") && it.endsWith(BUILTINS_EXTENSION) }.sorted()
    } catch (e: Exception) {
        logger.warn(e) { "Cannot list the Kotlin built-in declarations next to $anchor, reading the known ones" }
        knownBuiltinsResources
    }
}

private fun jarBuiltinsResources(anchor: URL): List<String> {
    val connection = anchor.openConnection() as JarURLConnection
    // a cached jar file is shared with the class loader, and must not be closed
    connection.useCaches = false
    return connection.jarFile.use { jar ->
        jar.entries().asSequence().map { it.name }.toList()
    }
}

/**
 * Lists the resources of a directory, or of a module of the runtime image (the `jrt` file system).
 */
private fun pathBuiltinsResources(anchor: URL): List<String> {
    val path = Path.of(anchor.toURI())
    val kotlinPackage = path.parent
    val root = kotlinPackage.parent
    return Files.walk(kotlinPackage).use { paths ->
        paths.asSequence()
            .filter { it.isRegularFile() }
            .map { root.relativize(it).joinToString("/") }
            .toList()
    }
}
