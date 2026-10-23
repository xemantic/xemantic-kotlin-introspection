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

private const val METADATA_DESCRIPTOR = "Lkotlin/Metadata;"
