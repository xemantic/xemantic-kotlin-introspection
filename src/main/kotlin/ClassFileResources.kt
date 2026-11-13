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

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes

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

/**
 * An ASM reader of the class file [bytes], also of one too new for ASM, like of a class compiled for a newer JVM,
 * whose major version is then lowered in a copy, as the constant pool, the fields, the methods and the attributes
 * keep their format across the class file versions, so that its declarations, like its Kotlin metadata, are read all the same.
 *
 * @throws IllegalArgumentException if the class file cannot be parsed otherwise,
 *   like when the constant pool has an entry of an unknown kind.
 */
internal fun classReader(bytes: ByteArray): ClassReader = try {
    ClassReader(bytes)
} catch (e: IllegalArgumentException) {
    // the major version, following the magic number and the minor version
    val majorVersion = if (bytes.size < 8) 0 else ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
    if (majorVersion <= Opcodes.V1_8) throw e
    ClassReader(
        bytes.copyOf().apply {
            this[6] = 0
            this[7] = Opcodes.V1_8.toByte()
        }
    )
}

/**
 * Whether the class file of this reader names each of its direct supertypes by a `Class` entry of its constant pool,
 * unlike a malformed one, whose superclass or interface index ASM reads all the same:
 * the entry 0 as no name, and an entry of another kind, like a `Utf8` one, as an unrelated name.
 * The superclass of the entry 0 is none, like of `java/lang/Object`.
 */
internal fun ClassReader.namesSupertypesByClassEntries(): Boolean {
    fun isClassEntry(index: Int): Boolean =
        // the offset of an entry follows its tag, while the second entry taken by a Long or a Double has none
        index in 1 until itemCount && getItem(index).let { it > 0 && readByte(it - 1) == CONSTANT_CLASS_TAG }
    // the access flags are followed by this class, the superclass, the interface count, and the interfaces
    val superclass = readUnsignedShort(header + 4)
    return (superclass == 0 || isClassEntry(superclass)) &&
        (0 until readUnsignedShort(header + 6)).all { isClassEntry(readUnsignedShort(header + 8 + 2 * it)) }
}

private const val CONSTANT_CLASS_TAG = 7

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

/**
 * The metadata name, like `com/example/Outer.Nested`, of a class found by its JVM [binaryName],
 * in the package of the binary name, which is the [metadataName] the Kotlin metadata tells,
 * unless the class is relocated, like by shading, rewriting the binary names, but not the Kotlin metadata,
 * like `shaded/com/example/Outer.Nested` of `shaded.com.example.Outer${'$'}Nested`,
 * so that the classes enclosing it are found by their relocated binary names too, see [binaryNameOf].
 * The leading `.` of the metadata name of a local class is kept, telling it apart, see [ApiClasses].
 */
internal fun locatedNameOf(binaryName: String, metadataName: String): String {
    val packageName = binaryName.substringBeforeLast('.', "")
    val simpleName = metadataName.substringAfterLast('/').removePrefix(".")
    val localPrefix = if (metadataName.startsWith('.')) "." else ""
    return localPrefix + if (packageName.isEmpty()) simpleName else "${packageName.replace('.', '/')}/$simpleName"
}
