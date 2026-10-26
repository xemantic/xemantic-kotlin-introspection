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

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmVariance
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata

/*
 * Rewriting the class files of the test fixtures, like a compiler plugin, R8 or another bytecode tool might.
 */

/**
 * Replaces the `@Metadata` annotation of the class bytes by the one [transformed][transform].
 */
internal fun withMetadata(bytes: ByteArray, transform: (Metadata) -> Metadata): ByteArray {
    val writer = ClassWriter(0)
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
            if (descriptor != METADATA_DESCRIPTOR) {
                super.visitAnnotation(descriptor, visible)
            } else {
                MetadataRewriter(visible, transform)
            }

        inner class MetadataRewriter(
            private val visible: Boolean,
            private val transform: (Metadata) -> Metadata
        ) : AnnotationVisitor(Opcodes.ASM9) {

            private val values = mutableMapOf<String, Any>()

            override fun visit(name: String, value: Any) {
                values[name] = value
            }

            override fun visitArray(name: String): AnnotationVisitor = object : AnnotationVisitor(Opcodes.ASM9) {
                private val elements = mutableListOf<String>()

                override fun visit(name: String?, value: Any) {
                    elements += value as String
                }

                override fun visitEnd() {
                    values[name] = elements.toTypedArray()
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun visitEnd() {
                val metadata = transform(
                    Metadata(
                        kind = values["k"] as Int?,
                        metadataVersion = values["mv"] as IntArray?,
                        data1 = values["d1"] as Array<String>?,
                        data2 = values["d2"] as Array<String>?,
                        extraString = values["xs"] as String?,
                        packageName = values["pn"] as String?,
                        extraInt = values["xi"] as Int?
                    )
                )
                writer.visitAnnotation(METADATA_DESCRIPTOR, visible).apply {
                    visit("k", metadata.kind)
                    visit("mv", metadata.metadataVersion)
                    visitArray("d1").apply {
                        metadata.data1.forEach { visit(null, it) }
                        visitEnd()
                    }
                    visitArray("d2").apply {
                        metadata.data2.forEach { visit(null, it) }
                        visitEnd()
                    }
                    if (metadata.extraString.isNotEmpty()) visit("xs", metadata.extraString)
                    if (metadata.packageName.isNotEmpty()) visit("pn", metadata.packageName)
                    visit("xi", metadata.extraInt)
                    visitEnd()
                }
            }

        }
    }, 0)
    return writer.toByteArray()
}

/**
 * Removes the [nestedClass] from the nested classes declared in the class metadata.
 */
internal fun withoutNestedClassInMetadata(bytes: ByteArray, nestedClass: String): ByteArray = withMetadata(bytes) {
    val metadata = KotlinClassMetadata.readStrict(it) as KotlinClassMetadata.Class
    check(metadata.kmClass.nestedClasses.remove(nestedClass))
    metadata.write()
}

/**
 * Adds the legacy context receivers of the [types] to the class metadata,
 * like of `context(Logger) class Foo` compiled with `-Xcontext-receivers`, which the current compiler no longer compiles.
 */
internal fun withContextReceiversInMetadata(bytes: ByteArray, vararg types: KmType): ByteArray = withMetadata(bytes) {
    val metadata = KotlinClassMetadata.readStrict(it) as KotlinClassMetadata.Class
    metadata.kmClass.legacyContextReceiverTypes += types
    metadata.write()
}

/**
 * Adds the legacy context receivers to the metadata of the classes of `LegacyContextReceivers.kt` of the isolated test fixtures,
 * see [withContextReceiversInMetadata], given the [fileName] and the [bytes] of a class file, keeping the other ones,
 * shared with the parse test of the rendered declarations, so that it parses the very listings rendered of them.
 */
internal fun withLegacyContextReceivers(fileName: String, bytes: ByteArray): ByteArray = when (fileName) {
    "LegacyContextReceiverClass.class" -> withContextReceiversInMetadata(bytes, legacyLogger, legacyComparator)
    "LegacyContextReceiverValue.class" -> withContextReceiversInMetadata(bytes, legacyLogger)
    "LegacyContextReceiverOuter\$Inner.class" -> withContextReceiversInMetadata(bytes, legacyComparator)
    else -> bytes
}

private val legacyLogger = KmType().apply { classifier = KmClassifier.Class("com/xemantic/kotlin/introspection/isolated/LegacyLogger") }

/**
 * The `Comparator<T>` of the type parameter `T` of the class, or of the outer class.
 */
private val legacyComparator = KmType().apply {
    classifier = KmClassifier.Class("kotlin/Comparator")
    arguments += KmTypeProjection(KmVariance.INVARIANT, KmType().apply { classifier = KmClassifier.TypeParameter(0) })
}

/**
 * Adds the supertypes of the [types] to the class metadata, like a cyclic one, which the compiler would reject.
 */
internal fun withSupertypesInMetadata(bytes: ByteArray, vararg types: KmType): ByteArray = withMetadata(bytes) {
    val metadata = KotlinClassMetadata.readStrict(it) as KotlinClassMetadata.Class
    metadata.kmClass.supertypes += types
    metadata.write()
}

internal fun withMetadataKind(bytes: ByteArray, kind: Int): ByteArray = withMetadata(bytes) {
    Metadata(kind, it.metadataVersion, it.data1, it.data2, it.extraString, it.packageName, it.extraInt)
}

/**
 * Removes the `@Metadata` annotation from the class bytes, like R8 or shading might.
 */
internal fun withoutMetadata(bytes: ByteArray): ByteArray {
    val writer = ClassWriter(0)
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
            if (descriptor == METADATA_DESCRIPTOR) null else super.visitAnnotation(descriptor, visible)
    }, 0)
    return writer.toByteArray()
}

/**
 * Removes the `InnerClasses` attribute from the class bytes, like R8 does unless told to keep it,
 * so that reflection no longer tells the nested classes.
 */
internal fun withoutInnerClasses(bytes: ByteArray): ByteArray {
    val writer = ClassWriter(0)
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {}
    }, 0)
    return writer.toByteArray()
}

private const val METADATA_DESCRIPTOR = "Lkotlin/Metadata;"
