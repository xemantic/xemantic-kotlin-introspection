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

// The class file resources of a class loader, looked up without loading the classes.
// A class file resource is never encapsulated, also when it is in a named module.
// A class loader might throw when looking one up, even a runtime exception, like a closed or a restricted one,
// which is then treated as if there were no such resource, so that it never fails the resolve.

/**
 * Reads the class file of a class by its JVM [binaryName], like `com.example.Outer${'$'}Nested`,
 * or `null` if it is not served, or cannot be read, telling the failure to [onFailure].
 */
internal fun ClassLoader.readClassBytes(binaryName: String, onFailure: (Exception) -> Unit = {}): ByteArray? = try {
    getResourceAsStream(classFileResource(binaryName))?.use { it.readBytes() }
} catch (e: Exception) {
    onFailure(e)
    null
}

/**
 * Whether there is a class file for the class of this JVM [binaryName].
 */
internal fun ClassLoader.hasClassFile(binaryName: String): Boolean = try {
    getResource(classFileResource(binaryName)) != null
} catch (_: Exception) {
    false
}

private fun classFileResource(binaryName: String): String = binaryName.replace('.', '/') + ".class"

/**
 * The JVM binary name, like `com.example.Outer${'$'}Nested`, of a metadata name, like `com/example/Outer.Nested`,
 * the Kotlin class name of the metadata, where the nested classes are separated with `.`.
 */
internal fun binaryNameOf(metadataName: String): String {
    val packageName = metadataName.substringBeforeLast('/', "")
    val simpleName = metadataName.substringAfterLast('/').replace('.', '$')
    return if (packageName.isEmpty()) simpleName else "${packageName.replace('/', '.')}.$simpleName"
}
