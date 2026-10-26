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
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmPackage
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

/**
 * Declares the file facade of the class bytes in the Kotlin [packageName], other than its JVM package,
 * like `@JvmPackageName` of the standard library does, with its declarations [transformed][transform].
 */
internal fun withKotlinPackageInMetadata(
    bytes: ByteArray,
    packageName: String,
    transform: (KmPackage) -> Unit
): ByteArray = withMetadata(bytes) {
    val metadata = KotlinClassMetadata.readStrict(it) as KotlinClassMetadata.FileFacade
    transform(metadata.kmPackage)
    val written = metadata.write()
    Metadata(written.kind, written.metadataVersion, written.data1, written.data2, written.extraString, packageName, written.extraInt)
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

/**
 * Relocates the class bytes like shading does: the internal names, like `com/example/Foo`, which [relocate] maps,
 * like to `shaded/com/example/Foo`, are rewritten wherever the class file refers to them:
 * in the name of the class, its supertypes, the descriptors and the generic signatures of its fields and methods,
 * the `InnerClasses`, `EnclosingMethod` and nest attributes, and the method bodies,
 * but not in the Kotlin metadata, which a shading tool does not rewrite.
 * The `ClassRemapper` of `asm-commons`, not on the test classpath, is not used.
 *
 * @param relocate the relocated internal name of a class, or `null` if it is not relocated.
 */
internal fun withRelocatedNames(bytes: ByteArray, relocate: (String) -> String?): ByteArray {
    fun name(name: String): String = relocate(name) ?: name
    // the class types of a descriptor, or of a generic signature, like `Lcom/example/Outer<TK;>.Inner;`
    fun descriptor(descriptor: String): String = CLASS_TYPE.replace(descriptor) { "L" + name(it.groupValues[1]) }
    // the internal name of a class, or the descriptor of an array type
    fun type(type: String): String = if (type.startsWith('[')) descriptor(type) else name(type)
    fun constant(value: Any?): Any? = when {
        value is Type && value.sort == Type.METHOD -> Type.getMethodType(descriptor(value.descriptor))
        value is Type -> Type.getType(descriptor(value.descriptor))
        else -> value
    }
    fun frame(entries: Array<out Any?>?): Array<Any?>? = entries?.map { if (it is String) type(it) else it }?.toTypedArray()
    val writer = ClassWriter(0)
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
        ) = super.visit(
            version, access, name(name), signature?.let(::descriptor), superName?.let(::name),
            interfaces?.map(::name)?.toTypedArray()
        )

        override fun visitOuterClass(owner: String, name: String?, descriptor: String?) =
            super.visitOuterClass(name(owner), name, descriptor?.let(::descriptor))

        override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) =
            super.visitInnerClass(name(name), outerName?.let(::name), innerName, access)

        override fun visitNestHost(nestHost: String) = super.visitNestHost(name(nestHost))

        override fun visitNestMember(nestMember: String) = super.visitNestMember(name(nestMember))

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? =
            super.visitField(access, name, descriptor(descriptor), signature?.let(::descriptor), value)

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor = object : MethodVisitor(
            Opcodes.ASM9,
            super.visitMethod(
                access, name, descriptor(descriptor), signature?.let(::descriptor), exceptions?.map(::name)?.toTypedArray()
            )
        ) {
            override fun visitTypeInsn(opcode: Int, type: String) = super.visitTypeInsn(opcode, type(type))

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) =
                super.visitFieldInsn(opcode, type(owner), name, descriptor(descriptor))

            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) =
                super.visitMethodInsn(opcode, type(owner), name, descriptor(descriptor), isInterface)

            override fun visitLdcInsn(value: Any?) = super.visitLdcInsn(constant(value))

            override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) =
                super.visitMultiANewArrayInsn(descriptor(descriptor), numDimensions)

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) =
                super.visitTryCatchBlock(start, end, handler, type?.let(::name))

            override fun visitLocalVariable(
                name: String,
                descriptor: String,
                signature: String?,
                start: Label,
                end: Label,
                index: Int
            ) = super.visitLocalVariable(name, descriptor(descriptor), signature?.let(::descriptor), start, end, index)

            override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any?>?, numStack: Int, stack: Array<out Any?>?) =
                super.visitFrame(type, numLocal, frame(local), numStack, frame(stack))
        }
    }, 0)
    return writer.toByteArray()
}

// a class type in a descriptor, or in a generic signature, up to its end, its type arguments, or its inner class
private val CLASS_TYPE = Regex("L([^;<.:]+)")

private const val METADATA_DESCRIPTOR = "Lkotlin/Metadata;"
