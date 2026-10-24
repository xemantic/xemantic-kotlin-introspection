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
import java.io.File
import java.io.IOException
import java.net.JarURLConnection
import java.net.URISyntaxException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
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
 * read only when the resources cannot be listed, or the listed ones lack the anchor resource.
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
 * by their Kotlin class names, like `kotlin/collections/List`,
 * each read from the root containing the [anchor] resource, where they are listed, see [kotlinBuiltinsResources],
 * and not from the first root of the class path having it, which might be of another version of the standard library,
 * like an older or a shaded one, mixing the declarations of two versions.
 *
 * @param anchor the URL of the `kotlin/kotlin.kotlin_builtins` resource, found relative to a class of the standard library,
 *   through its class loader when it is an unnamed module; when it is a named module, the resources are encapsulated,
 *   as its packages are open only to kotlin.reflect, so that they cannot be found from here.
 */
internal fun readKotlinBuiltinClasses(
    anchor: URL? = KotlinVersion::class.java.getResource("/$ANCHOR_BUILTINS_RESOURCE")
): Map<String, KmClass> = kotlinBuiltinsResources(anchor).flatMap { resource ->
    val stream = try {
        if (anchor != null) {
            // all the resources are in the kotlin package, or its subpackages, like the anchor itself
            @Suppress("DEPRECATION") // the URL handler of the anchor, like of a nested jar, is kept only this way
            URL(anchor, resource.removePrefix("kotlin/")).openStream()
        } else {
            KotlinVersion::class.java.getResourceAsStream("/$resource")
        }
    } catch (_: IOException) {
        null
    }
    if (stream == null) {
        // the known resources, read when they cannot be listed, might be missing from an older standard library
        if (resource == ANCHOR_BUILTINS_RESOURCE) {
            logger.warn { "Cannot read the Kotlin built-in declarations of $resource" }
        } else {
            logger.debug { "Cannot read the Kotlin built-in declarations of $resource" }
        }
    }
    stream?.use { KotlinCommonMetadata.read(it) }?.kmModuleFragment?.classes.orEmpty()
}.associateBy { it.name }

/**
 * Lists the `.kotlin_builtins` resources of the standard library, as paths relative to its root, like
 * `kotlin/collections/collections.kotlin_builtins`, in the root containing the [anchor] resource:
 * a jar, also a nested one of a fat jar if its URL handler supports it, a directory, or a module of the runtime image.
 *
 * As all the built-in types are declared in the `kotlin` package, or its subpackages, only these are listed.
 * If the resources cannot be listed, or the listed ones lack the [anchor] itself,
 * the known ones are returned, and a warning is logged.
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
    var failure: Exception? = null
    val resources = try {
        val resources = if (anchor.protocol == "jar") {
            jarBuiltinsResources(anchor)
        } else {
            pathBuiltinsResources(Path.of(anchor.toURI()))
        }
        resources.filter { it.startsWith("kotlin/") && it.endsWith(BUILTINS_EXTENSION) }.sorted()
    } catch (e: Exception) {
        failure = e
        emptyList()
    }
    if (ANCHOR_BUILTINS_RESOURCE in resources) return resources
    logger.warn(failure) {
        if (failure != null) {
            "Cannot list the Kotlin built-in declarations next to $anchor, reading the known ones"
        } else {
            "The Kotlin built-in declarations listed next to $anchor lack $ANCHOR_BUILTINS_RESOURCE, reading the known ones"
        }
    }
    return knownBuiltinsResources
}

/**
 * Lists the entries of the jar containing the [anchor], relative to the path prefix of the anchor entry,
 * like `WEB-INF/classes/` of a war, skipping the entries outside of it.
 */
private fun jarBuiltinsResources(anchor: URL): List<String> {
    val connection = anchor.openConnection() as JarURLConnection
    val prefix = connection.entryName.orEmpty().removeSuffix(ANCHOR_BUILTINS_RESOURCE)
    fun JarFile.resources() = entries().asSequence()
        .map { it.name }
        .filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix) }
        .toList()
    val file = connection.jarFileURL.toFile()
    if (file != null) return JarFile(file).use { it.resources() }
    // a jar file of a custom URL handler, like a nested jar of a fat jar, or of a file: URL which is not a valid URI,
    // is shared with the class loader when cached, so it is closed only when not cached
    val jarFile = connection.jarFile
    return if (connection.useCaches) jarFile.resources() else jarFile.use { it.resources() }
}

/**
 * The file of a `file:` [URL], or `null` if it is not one, or it is not a valid file URI,
 * like one with an unencoded space of the deprecated `File.toURL()`, or a Windows UNC path, like `file://server/share`.
 */
internal fun URL.toFile(): File? {
    if (protocol != "file") return null
    return try {
        File(toURI())
    } catch (_: URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Lists the resources of a directory, or of a module of the runtime image (the `jrt` file system),
 * containing the [anchor], relative to its root, see [kotlinBuiltinsResources].
 */
internal fun pathBuiltinsResources(anchor: Path): List<String> {
    val kotlinPackage = anchor.parent
    val root = kotlinPackage.parent
    return Files.walk(kotlinPackage).use { paths ->
        paths.asSequence()
            .filter { it.isRegularFile() }
            .map { root.relativize(it).joinToString("/") }
            .toList()
    }
}
