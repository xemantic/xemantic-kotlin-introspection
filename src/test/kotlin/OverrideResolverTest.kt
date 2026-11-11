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

import com.xemantic.kotlin.introspection.test.other.PackagePrivateBase
import com.xemantic.kotlin.test.assert
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmValueParameter
import kotlin.metadata.KmVariance
import kotlin.metadata.Modality
import kotlin.metadata.Visibility
import kotlin.metadata.isInner
import kotlin.metadata.isNullable
import kotlin.metadata.modality
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility
import kotlin.test.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

class OverrideResolverTest {

    private fun type(name: String) = KmType().apply { classifier = KmClassifier.Class(name) }

    private fun function(name: String) = KmFunction(name).apply {
        visibility = Visibility.PUBLIC
        modality = Modality.OPEN
        returnType = type("kotlin/Unit")
    }

    private fun kmClass(name: String, supertype: String? = null, vararg functions: String) = KmClass().apply {
        this.name = name
        visibility = Visibility.PUBLIC
        modality = Modality.OPEN
        supertype?.let { supertypes += type(it) }
        functions.forEach { this.functions += function(it) }
    }

    /**
     * @param classFiles the class files served by their Kotlin class names, like `test/Owner`.
     * @param unloadableClassFiles the class files of the classes which cannot be loaded, by their Kotlin class names.
     * @param readClassFile reads the class file of a class by its Kotlin class name, like from the [classFiles].
     */
    private fun overrideResolver(
        builtinClasses: Map<String, KmClass> = emptyMap(),
        classes: Map<String, KmClass> = emptyMap(),
        classFiles: Map<String, ByteArray> = emptyMap(),
        unloadableClassFiles: Map<String, ByteArray> = emptyMap(),
        transientFailures: TransientFailures = TransientFailures(),
        classLoader: ClassLoader = javaClass.classLoader,
        loadClass: (String) -> Class<*>? = { null },
        readClassFile: (String) -> ByteArray? = { classFiles[it] },
        loadKmClass: (String) -> KmClass? = { classes[it] }
    ) = OverrideResolver(
        classLoader = classLoader,
        loadClassByMetadataName = loadClass,
        loadKmClassByMetadataName = loadKmClass,
        readClassBytesByMetadataName = readClassFile,
        unloadableClassBytesByMetadataName = { unloadableClassFiles[it] },
        transientFailures = transientFailures,
        builtinClasses = { builtinClasses },
        builtinTypes = emptyMap()
    )

    @Test
    fun `should tell overrides of built-in declaration and class file declaration of the same name apart`() {
        // given
        // like the incomplete .kotlin_builtins declaration of a built-in type having its own class file
        val builtinOwner = kmClass("test/Owner", supertype = "test/BuiltinBase")
        val classFileOwner = kmClass("test/Owner", supertype = "test/ClassFileBase")
        val resolver = overrideResolver(
            builtinClasses = mapOf("test/Owner" to builtinOwner),
            classes = mapOf(
                "test/BuiltinBase" to kmClass("test/BuiltinBase", null, "fromBuiltin"),
                "test/ClassFileBase" to kmClass("test/ClassFileBase", null, "fromClassFile")
            )
        )

        // when
        val builtinOverrides = listOf(
            resolver.isOverride(OverrideResolver.Owner(builtinOwner), function("fromBuiltin")),
            resolver.isOverride(OverrideResolver.Owner(builtinOwner), function("fromClassFile"))
        )
        val classFileOverrides = listOf(
            resolver.isOverride(OverrideResolver.Owner(classFileOwner), function("fromBuiltin")),
            resolver.isOverride(OverrideResolver.Owner(classFileOwner), function("fromClassFile"))
        )

        // then
        assert(builtinOverrides == listOf(true, false))
        assert(classFileOverrides == listOf(false, true))
    }

    /**
     * Like `put(key: Int)`, of the [descriptor], or of the nullable `Int?`, when [isNullable].
     */
    private fun intFunction(descriptor: String?, isNullable: Boolean = false) = function("put").apply {
        valueParameters += KmValueParameter("key").apply { type = type("kotlin/Int").apply { this.isNullable = isNullable } }
        signature = descriptor?.let { JvmMethodSignature("put", it) }
    }

    @Test
    fun `should tell overrides of Java method by its primitive types regardless of nullability`() {
        // given
        // like a Java class implementing `interface PrimitivePut { fun put(key: Int) }` of put(int), and declaring put(Integer)
        val owner = kmClass("test/JavaPut", supertype = "test/PrimitivePut")
        val resolver = overrideResolver(
            classes = mapOf("test/PrimitivePut" to kmClass("test/PrimitivePut").apply { functions += intFunction("(I)V") })
        )

        // when
        val primitive = resolver.overriddenMembers(OverrideResolver.Owner(owner), intFunction(null), jvmDescriptor = "(I)V")
        // the Int? of the platform type Int!, as kotlin-reflect describes it
        val boxed = resolver.overriddenMembers(
            OverrideResolver.Owner(owner), intFunction(null, isNullable = true), jvmDescriptor = "(Ljava/lang/Integer;)V"
        )
        val boxedByKotlinTypes = resolver.overriddenMembers(OverrideResolver.Owner(owner), intFunction(null))

        // then
        assert(primitive == setOf("test/PrimitivePut#context() fun put(kotlin/Int)"))
        assert(boxed.isEmpty())
        assert(boxedByKotlinTypes == primitive)
    }

    /**
     * A function taking a value of the type parameter of the [id], like `accept(value: T)`.
     */
    private fun typeParameterFunction(name: String, id: Int) = function(name).apply {
        valueParameters += KmValueParameter("value").apply {
            type = KmType().apply { classifier = KmClassifier.TypeParameter(id) }
        }
    }

    /**
     * Like `abstract class Base<T> { abstract fun accept(value: T) }`.
     */
    private fun genericBase(name: String) = kmClass(name).apply {
        typeParameters += KmTypeParameter("T", 0, KmVariance.INVARIANT)
        functions += typeParameterFunction("accept", 0)
    }

    @Test
    fun `should find supertypes of relocated owner in its package by their located names`() {
        // given
        // like by shading, relocating the binary names of the classes, but not their names in the Kotlin metadata
        val owner = kmClass("test/Owner", supertype = "test/Base")
        val resolver = overrideResolver(
            classes = mapOf(
                "test/Base" to kmClass("test/Base", null, "original"),
                "shaded/test/Base" to kmClass("test/Base", null, "relocated")
            )
        )
        val original = OverrideResolver.Owner(owner, "test/Owner")
        val relocated = OverrideResolver.Owner(owner, "shaded/test/Owner")

        // when
        val originalOverrides = listOf(
            resolver.isOverride(original, function("original")),
            resolver.isOverride(original, function("relocated"))
        )
        val relocatedOverrides = listOf(
            resolver.isOverride(relocated, function("original")),
            resolver.isOverride(relocated, function("relocated"))
        )

        // then
        assert(originalOverrides == listOf(true, false))
        assert(relocatedOverrides == listOf(false, true))
    }

    @Test
    fun `should find supertypes of other packages relocated with the owner by the prefix of its relocation`() {
        // given
        // like relocated by shading, prepending shaded/ to all the packages
        val owner = kmClass("test/Owner", supertype = "other/Base")
        val resolver = overrideResolver(
            classes = mapOf(
                "other/Base" to kmClass("other/Base", null, "original"),
                "shaded/other/Base" to kmClass("other/Base", null, "relocated")
            )
        )
        val relocated = OverrideResolver.Owner(owner, "shaded/test/Owner")

        // when
        val overrides = listOf(
            resolver.isOverride(relocated, function("original")),
            resolver.isOverride(relocated, function("relocated"))
        )

        // then
        assert(overrides == listOf(false, true))
    }

    @Test
    fun `should find supertypes of relocated owner which are not relocated by their metadata names`() {
        // given
        // like a class excluded from the relocation, of another package, or of the same one
        val owner = kmClass("test/Owner", supertype = "other/Base").apply { supertypes += type("test/SamePackageBase") }
        val resolver = overrideResolver(
            classes = mapOf(
                "other/Base" to kmClass("other/Base", null, "fromOtherPackage"),
                "test/SamePackageBase" to kmClass("test/SamePackageBase", null, "fromSamePackage")
            )
        )
        val relocated = OverrideResolver.Owner(owner, "shaded/test/Owner")

        // when
        val overrides = listOf(
            resolver.isOverride(relocated, function("fromOtherPackage")),
            resolver.isOverride(relocated, function("fromSamePackage"))
        )

        // then
        assert(overrides == listOf(true, true))
    }

    @Test
    fun `should find supertypes of subpackage of renamed package of relocated owner by their located names`() {
        // given
        // like relocated by shading from test/ to renamed/, with the subpackages
        val owner = kmClass("test/Owner", supertype = "test/sub/Base")
        val resolver = overrideResolver(
            classes = mapOf(
                "test/sub/Base" to kmClass("test/sub/Base", null, "original"),
                "renamed/sub/Base" to kmClass("test/sub/Base", null, "relocated")
            )
        )
        val relocated = OverrideResolver.Owner(owner, "renamed/Owner")

        // when
        val overrides = listOf(
            resolver.isOverride(relocated, function("original")),
            resolver.isOverride(relocated, function("relocated"))
        )

        // then
        assert(overrides == listOf(false, true))
    }

    @Test
    fun `should find supertypes of owner relocated to the root package by their located names`() {
        // given
        // like relocated by shading from test/ to the root package
        val owner = kmClass("test/Owner", supertype = "test/Base")
        val resolver = overrideResolver(
            classes = mapOf(
                "test/Base" to kmClass("test/Base", null, "original"),
                "Base" to kmClass("test/Base", null, "relocated")
            )
        )
        val relocated = OverrideResolver.Owner(owner, "Owner")

        // when
        val overrides = listOf(
            resolver.isOverride(relocated, function("original")),
            resolver.isOverride(relocated, function("relocated"))
        )

        // then
        assert(overrides == listOf(false, true))
    }

    @Test
    fun `should not take overload for override of Java supertype of subpackage of owner relocated to the root package`() {
        // given
        // like relocated by shading from test/ to the root package, and test/sub/ to sub/
        val owner = kmClass("test/Owner", supertype = "test/sub/Listener").apply {
            functions += functionTaking("on", "test/sub/Event")
            functions += functionTaking("on", "test/sub/Other")
        }
        val listener = ClassWriter(0).apply {
            visit(
                Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
                "sub/Listener", null, "java/lang/Object", null
            )
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "on", "(Lsub/Event;)V", null, null)
            visitEnd()
        }.toByteArray()
        val ownerClassFile = ClassWriter(0).apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Owner", null, "java/lang/Object", arrayOf("sub/Listener"))
            visitMethod(Opcodes.ACC_PUBLIC, "on", "(Lsub/Event;)V", null, null)
            visitMethod(Opcodes.ACC_PUBLIC, "on", "(Lsub/Other;)V", null, null)
            visitEnd()
        }.toByteArray()
        val resolver = overrideResolver(
            classFiles = mapOf("Owner" to ownerClassFile),
            unloadableClassFiles = mapOf("sub/Listener" to listener)
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner, "Owner"), it) }

        // then
        // the class file tells test/sub/ relocated to sub/, translated back, so that the JVM method of the overload,
        // taking sub/Other, is not taken for a shrunk one, as when the root package relocated its subpackages
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should translate names of classes relocated to the root package both ways`() {
        // given
        val relocation = checkNotNull(Relocation.of("test/Owner", "Owner"))

        // when
        val located = listOf(relocation.located("test/Base"), relocation.located("test/sub/Base"), relocation.located("other/Base"))
        val unlocated = listOf(relocation.unlocated("Base"), relocation.unlocated("sub/Base"), relocation.unlocated("other/Base"))
        val descriptor = relocation.unlocatedDescriptor("on(LEvent;Lother/Event;)V")

        // then
        // only the classes of the package itself are relocated, to and from the root package,
        // as the root package, containing every other one, tells no subpackage apart
        assert(located == listOf("Base", null, null))
        assert(unlocated == listOf("test/Base", "sub/Base", "other/Base"))
        assert(descriptor == "on(Ltest/Event;Lother/Event;)V")
    }

    /**
     * The class file of a class of the [internalName], like `test/Owner`, implementing the [interfaces],
     * telling the JVM names of its supertypes, which shading rewrites, unlike the Kotlin metadata.
     */
    private fun classFile(internalName: String, vararg interfaces: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, internalName, null, "java/lang/Object", arrayOf(*interfaces)
        )
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `should find relocated supertypes of owner which is not relocated by the names its class file refers to`() {
        // given
        // like an app shading its dependencies, rewriting its own class files, but not their Kotlin metadata
        val owner = kmClass("app/Owner", supertype = "lib/Base")
        val resolver = overrideResolver(
            classes = mapOf(
                "lib/Base" to kmClass("lib/Base", null, "original"),
                "app/shaded/lib/Base" to kmClass("lib/Base", null, "relocated")
            ),
            classFiles = mapOf("app/Owner" to classFile("app/Owner", "app/shaded/lib/Base"))
        )

        // when
        val overrides = listOf(
            resolver.isOverride(OverrideResolver.Owner(owner), function("original")),
            resolver.isOverride(OverrideResolver.Owner(owner), function("relocated"))
        )

        // then
        assert(overrides == listOf(false, true))
    }

    @Test
    fun `should find relocated supertypes of owner by the names its class file too new to be parsed refers to`() {
        // given
        // like above, the class file of the owner of a version ASM cannot parse
        val owner = kmClass("app/Owner", supertype = "lib/Base")
        val resolver = overrideResolver(
            classes = mapOf(
                "lib/Base" to kmClass("lib/Base", null, "original"),
                "app/shaded/lib/Base" to kmClass("lib/Base", null, "relocated")
            ),
            classFiles = mapOf("app/Owner" to tooNew(classFile("app/Owner", "app/shaded/lib/Base")))
        )

        // when
        val overrides = listOf(
            resolver.isOverride(OverrideResolver.Owner(owner), function("original")),
            resolver.isOverride(OverrideResolver.Owner(owner), function("relocated"))
        )

        // then
        assert(overrides == listOf(false, true))
    }

    /**
     * A function taking a value of the class of the [typeName], like `on(event: lib/Event)`,
     * with its JVM signature, which shading does not rewrite in the metadata.
     */
    private fun functionTaking(name: String, vararg typeNames: String) = function(name).apply {
        typeNames.forEachIndexed { index, typeName ->
            valueParameters += KmValueParameter("value$index").apply { type = type(typeName) }
        }
        signature = JvmMethodSignature(name, typeNames.joinToString("", "(", ")V") { "L$it;" })
    }

    @Test
    fun `should match parameter types of other relocated package of owner which is not relocated by its JVM methods`() {
        // given
        // like an app shading its dependencies, the Java interface lib/Listener taking lib2/Event, and lib2 itself
        val owner = kmClass("app/Owner", supertype = "lib/Listener").apply {
            functions += functionTaking("on", "lib2/Event")
            functions += functionTaking("on", "lib2/Other")
        }
        val listener = ClassWriter(0).apply {
            visit(
                Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
                "app/shaded/lib/Listener", null, "java/lang/Object", null
            )
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "on", "(Lapp/shaded/lib2/Event;)V", null, null)
            visitEnd()
        }.toByteArray()
        val ownerClassFile = ClassWriter(0).apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "app/Owner", null, "java/lang/Object", arrayOf("app/shaded/lib/Listener"))
            visitMethod(Opcodes.ACC_PUBLIC, "on", "(Lapp/shaded/lib2/Event;)V", null, null)
            visitMethod(Opcodes.ACC_PUBLIC, "on", "(Lapp/shaded/lib2/Other;)V", null, null)
            visitEnd()
        }.toByteArray()
        val resolver = overrideResolver(
            classFiles = mapOf("app/Owner" to ownerClassFile),
            unloadableClassFiles = mapOf("app/shaded/lib/Listener" to listener)
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // the JVM method of the overload, taking the relocated type, is not taken for a shrunk one
        assert(overrides == listOf(true, false))
    }

    /**
     * The class file of a class of the [internalName], like `app/Owner`, extending the [superName], implementing the [interfaces],
     * and declaring the [methods], names followed by descriptors, like `on(Lapp/shaded/lib/Event;)V`,
     * or of a Java interface, if it [isInterface], telling the JVM names, which shading rewrites, unlike the Kotlin metadata.
     *
     * @param isAnnotated whether the class is annotated with `lib/Marker`, of the single element `count`,
     *   the `RuntimeVisibleAnnotations` attribute being the only one of the class then, ending the class file,
     *   see [withAnnotationsAttribute].
     */
    private fun classFile(
        internalName: String,
        methods: List<String>,
        superName: String = "java/lang/Object",
        interfaces: List<String> = emptyList(),
        isInterface: Boolean = false,
        isAnnotated: Boolean = false
    ): ByteArray = ClassWriter(0).apply {
        val access = if (isInterface) Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE else 0
        visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or access, internalName, null, superName, interfaces.toTypedArray())
        if (isAnnotated) {
            visitAnnotation("Llib/Marker;", true).apply {
                visit("count", 1)
                visitEnd()
            }
        }
        methods.forEach { method ->
            val descriptor = method.indexOf('(')
            visitMethod(
                Opcodes.ACC_PUBLIC or (if (isInterface) Opcodes.ACC_ABSTRACT else 0),
                method.substring(0, descriptor), method.substring(descriptor), null, null
            )
        }
        visitEnd()
    }.toByteArray()

    @Test
    fun `should not take overload for override of relocated Java supertype of indirect supertype next to the original one`() {
        // given
        // like an app shading its dependencies, its class app/Mid implementing the relocated Java interface lib/Listener,
        // while the original lib jar is on the classpath as well
        val owner = kmClass("app/Owner", supertype = "app/Mid").apply {
            functions += functionTaking("on", "lib/Event")
            functions += functionTaking("on", "lib/Other")
        }
        val resolver = overrideResolver(
            classes = mapOf("app/Mid" to kmClass("app/Mid", supertype = "lib/Listener")),
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner", listOf("on(Lapp/shaded/lib/Event;)V", "on(Lapp/shaded/lib/Other;)V"), superName = "app/Mid"
                )
            ),
            unloadableClassFiles = mapOf(
                "lib/Listener" to classFile("lib/Listener", listOf("on(Llib/Event;)V"), isInterface = true),
                "app/shaded/lib/Listener" to classFile(
                    "app/shaded/lib/Listener", listOf("on(Lapp/shaded/lib/Event;)V"), isInterface = true
                )
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // the JVM methods of the owner tell lib/ relocated to app/shaded/lib/, which its supertypes do not,
        // so that the JVM method of the overload, taking the relocated type, is not taken for a shrunk one
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should find relocated Java supertype of indirect supertype by relocated name told by JVM methods of owner`() {
        // given
        // like above, with only the shaded jar on the classpath
        val owner = kmClass("app/Owner", supertype = "app/Mid").apply {
            functions += functionTaking("on", "lib/Event")
            functions += functionTaking("on", "lib/Other")
        }
        val resolver = overrideResolver(
            classes = mapOf("app/Mid" to kmClass("app/Mid", supertype = "lib/Listener")),
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner", listOf("on(Lapp/shaded/lib/Event;)V", "on(Lapp/shaded/lib/Other;)V"), superName = "app/Mid"
                )
            ),
            unloadableClassFiles = mapOf(
                "app/shaded/lib/Listener" to classFile(
                    "app/shaded/lib/Listener", listOf("on(Lapp/shaded/lib/Event;)V"), isInterface = true
                )
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should tell relocated dependencies by JVM methods whose names contain L like a class type`() {
        // given
        // like above, a JVM method name like onLoad, whose L must not be taken for the start of a class type
        val owner = kmClass("app/Owner", supertype = "app/Mid").apply {
            functions += functionTaking("onLoad", "lib/Event")
            functions += functionTaking("onLoad", "lib/Other")
        }
        val resolver = overrideResolver(
            classes = mapOf("app/Mid" to kmClass("app/Mid", supertype = "lib/Listener")),
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner", listOf("onLoad(Lapp/shaded/lib/Event;)V", "onLoad(Lapp/shaded/lib/Other;)V"), superName = "app/Mid"
                )
            ),
            unloadableClassFiles = mapOf(
                "app/shaded/lib/Listener" to classFile(
                    "app/shaded/lib/Listener", listOf("onLoad(Lapp/shaded/lib/Event;)V"), isInterface = true
                )
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should match relocated parameter types of Java interface of the app by JVM methods whose names contain L`() {
        // given
        val owner = kmClass("app/Owner", supertype = "app/JavaListener").apply {
            functions += functionTaking("onLoad", "lib/Event")
            functions += functionTaking("onLoad", "lib/Other")
        }
        val resolver = overrideResolver(
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner", listOf("onLoad(Lapp/shaded/lib/Event;)V", "onLoad(Lapp/shaded/lib/Other;)V"),
                    interfaces = listOf("app/JavaListener")
                )
            ),
            unloadableClassFiles = mapOf(
                "app/JavaListener" to classFile("app/JavaListener", listOf("onLoad(Lapp/shaded/lib/Event;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should find Java supertype of indirect supertype relocated to the root package with the classes of its package`() {
        // given
        // like an app shading its dependencies, relocating lib/ to the root package, with only the shaded jar on the classpath
        val owner = kmClass("app/Owner", supertype = "app/Mid").apply {
            functions += functionTaking("on", "lib/Event")
            functions += functionTaking("on", "lib/Other")
        }
        val resolver = overrideResolver(
            classes = mapOf("app/Mid" to kmClass("app/Mid", supertype = "lib/Listener")),
            classFiles = mapOf("app/Owner" to classFile("app/Owner", listOf("on(LEvent;)V", "on(LOther;)V"), superName = "app/Mid")),
            unloadableClassFiles = mapOf("Listener" to classFile("Listener", listOf("on(LEvent;)V"), isInterface = true))
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // the JVM methods of the owner tell the classes of lib/ relocated to the root package, so lib/Listener is looked up there
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should tell shrunk override of relocated owner next to overload taking class of the same simple name not relocated`() {
        // given
        // like relocated by shading from test/ to shaded/test/, and obfuscated, test/Event to a/b,
        // while the overload takes other/Event, which the relocation does not change
        val owner = kmClass("test/Owner", supertype = "test/Listener").apply {
            functions += functionTaking("sh", "test/Event")
            functions += functionTaking("sh", "other/Event")
        }
        val resolver = overrideResolver(
            classFiles = mapOf(
                "shaded/test/Owner" to classFile(
                    "shaded/test/Owner", listOf("sh(La/b;)V", "sh(Lother/Event;)V"), interfaces = listOf("shaded/test/Listener")
                )
            ),
            unloadableClassFiles = mapOf(
                "shaded/test/Listener" to classFile("shaded/test/Listener", listOf("sh(La/b;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner, "shaded/test/Owner"), it) }

        // then
        // the JVM method of the overload, taking other/Event, cannot be the relocated sh(test/Event)
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should tell shrunk override of owner relocated to the root package next to overload taking class relocated with it`() {
        // given
        // like relocated by shading from app/ to the root package, and shrunk, removing the JVM method of sh(lib/Event)
        val owner = kmClass("app/Owner", supertype = "lib/Listener").apply {
            functions += functionTaking("sh", "lib/Event")
            functions += functionTaking("sh", "app/Event")
        }
        val resolver = overrideResolver(
            classFiles = mapOf("Owner" to classFile("Owner", listOf("sh(LEvent;)V"), interfaces = listOf("lib/Listener"))),
            unloadableClassFiles = mapOf("lib/Listener" to classFile("lib/Listener", listOf("sh(Llib/Event;)V"), isInterface = true))
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner, "Owner"), it) }

        // then
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should find Java supertype by its metadata name in package relocated to the root package only in part`() {
        // given
        // like an app shading its dependencies, relocating lib/Event to the root package, but not lib/Listener,
        // while another jar has a class of the root package of the same simple name
        val owner = kmClass("app/Owner", supertype = "app/Mid").apply {
            functions += functionTaking("on", "lib/Event")
        }
        val resolver = overrideResolver(
            classes = mapOf("app/Mid" to kmClass("app/Mid", supertype = "lib/Listener")),
            classFiles = mapOf("app/Owner" to classFile("app/Owner", listOf("on(LEvent;)V"), superName = "app/Mid")),
            unloadableClassFiles = mapOf(
                "lib/Listener" to classFile("lib/Listener", listOf("on(LEvent;)V"), isInterface = true),
                "Listener" to classFile("Listener", listOf("other()V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        assert(overrides == listOf(true))
    }

    @Test
    fun `should not take overload for shrunk override when its JVM method differs from another overload only by the packages`() {
        // given
        // like an app shading its dependencies, relocating lib/ to the root package, and lib/sub/ to sub/
        val owner = kmClass("app/Owner", supertype = "lib/Listener").apply {
            functions += functionTaking("on", "lib/Event")
            functions += functionTaking("onPkg", "lib/Event")
            functions += functionTaking("onPkg", "lib/sub/Event")
        }
        val resolver = overrideResolver(
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner", listOf("on(LEvent;)V", "onPkg(LEvent;)V", "onPkg(Lsub/Event;)V"), interfaces = listOf("Listener")
                )
            ),
            unloadableClassFiles = mapOf(
                "Listener" to classFile("Listener", listOf("on(LEvent;)V", "onPkg(LEvent;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // the JVM methods of the onPkg overloads cannot be told apart, so neither is taken for a shrunk one
        assert(overrides == listOf(true, true, false))
    }

    @Test
    fun `should tell override of Java supertype relocated to the root package next to overloads of the same shape`() {
        // given
        // like an app shading its dependencies, relocating lib/ to the root package, and lib/sub/ to sub/,
        // the overloads all of the same shape, so that their JVM methods cannot tell lib/Event relocated to Event
        val owner = kmClass("app/Owner", supertype = "lib/Listener").apply {
            functions += functionTaking("on", "lib/Event", "ext/Event")
            functions += functionTaking("on", "ext/Event", "lib/Event")
            functions += functionTaking("on", "lib/sub/Event", "ext/Event")
        }
        val resolver = overrideResolver(
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner", listOf("on(LEvent;Lext/Event;)V", "on(Lext/Event;LEvent;)V", "on(Lsub/Event;Lext/Event;)V"),
                    interfaces = listOf("Listener")
                )
            ),
            unloadableClassFiles = mapOf(
                "lib/Listener" to classFile("lib/Listener", listOf("on(Llib/Event;Lext/Event;)V"), isInterface = true),
                "Listener" to classFile("Listener", listOf("on(LEvent;Lext/Event;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // the supertype tells the classes of lib/ relocated to the root package, so Event is matched as lib/Event
        assert(overrides == listOf(true, false, false))
    }

    @Test
    fun `should tell override of Java supertype of owner relocated from enclosing package to the root package next to overloads of the same shape`() {
        // given
        // like relocated by shading from p/ to the root package, p/app/ to app/, p/lib/ to lib/, and p/ext/ to ext/,
        // the overloads all of the same shape, so that their JVM methods cannot tell p/ext/Event relocated to ext/Event
        val owner = kmClass("p/app/Owner", supertype = "p/lib/Listener").apply {
            functions += functionTaking("on", "p/lib/Event", "p/ext/Event")
            functions += functionTaking("on", "p/ext/Event", "p/lib/Event")
            functions += functionTaking("on", "p/lib/sub/Event", "p/ext/Event")
        }
        val resolver = overrideResolver(
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner",
                    listOf("on(Llib/Event;Lext/Event;)V", "on(Lext/Event;Llib/Event;)V", "on(Llib/sub/Event;Lext/Event;)V"),
                    interfaces = listOf("lib/Listener")
                )
            ),
            unloadableClassFiles = mapOf(
                "p/lib/Listener" to classFile("p/lib/Listener", listOf("on(Lp/lib/Event;Lp/ext/Event;)V"), isInterface = true),
                "lib/Listener" to classFile("lib/Listener", listOf("on(Llib/Event;Lext/Event;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner, "app/Owner"), it) }

        // then
        // the owner relocated from p/app/ to app/ tells the prefix p/ removed from the other packages too
        assert(overrides == listOf(true, false, false))
    }

    @Test
    fun `should tell relocated Java supertype of indirect supertype by its class file next to overloads of the same shape`() {
        // given
        // like an app shading its dependencies, renaming lib/ to app/libs/ and other/ to app/others/,
        // its class app/Mid implementing the relocated Java interface lib/Listener,
        // the overloads of the owner of the same shape, so that their JVM methods cannot tell the relocation,
        // and neither can the package prefix, as each one is renamed differently,
        // with only the shaded jar on the classpath
        val owner = kmClass("app/Owner", supertype = "app/Mid").apply {
            functions += functionTaking("on", "lib/Event")
            functions += functionTaking("on", "other/Event")
        }
        val resolver = overrideResolver(
            classes = mapOf("app/Mid" to kmClass("app/Mid", supertype = "lib/Listener")),
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner", listOf("on(Lapp/libs/Event;)V", "on(Lapp/others/Event;)V"), superName = "app/Mid"
                ),
                "app/Mid" to classFile("app/Mid", listOf(), interfaces = listOf("app/libs/Listener"))
            ),
            unloadableClassFiles = mapOf(
                "app/libs/Listener" to classFile("app/libs/Listener", listOf("on(Lapp/libs/Event;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // only the class file of app/Mid tells lib/ relocated to app/libs/
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should tell relocated Java supertype of indirect supertype by its class file too new to be parsed next to overloads of the same shape`() {
        // given
        // like above, the class file of app/Mid of a version ASM cannot parse
        val owner = kmClass("app/Owner", supertype = "app/Mid").apply {
            functions += functionTaking("on", "lib/Event")
            functions += functionTaking("on", "other/Event")
        }
        val resolver = overrideResolver(
            classes = mapOf("app/Mid" to kmClass("app/Mid", supertype = "lib/Listener")),
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner", listOf("on(Lapp/libs/Event;)V", "on(Lapp/others/Event;)V"), superName = "app/Mid"
                ),
                "app/Mid" to tooNew(classFile("app/Mid", listOf(), interfaces = listOf("app/libs/Listener")))
            ),
            unloadableClassFiles = mapOf(
                "app/libs/Listener" to classFile("app/libs/Listener", listOf("on(Lapp/libs/Event;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // the supertypes of app/Mid are read from its class file all the same, telling lib/ relocated to app/libs/
        assert(overrides == listOf(true, false))
    }

    private fun ByteArray.writeShort(offset: Int, value: Int) {
        this[offset] = (value shr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    /**
     * The [classFile] with the constant pool index of the class itself, of its superclass,
     * and of its interfaces, by their positions, replaced, like a malformed one,
     * the index `0xFFFF` out of the constant pool, and the one of [utf8IndexOfName] not of a `Class` entry.
     */
    private fun withSupertypeIndexes(
        classFile: ByteArray,
        superclass: Int? = null,
        interfaces: Map<Int, Int> = emptyMap(),
        thisClass: Int? = null
    ): ByteArray {
        // the offset of the access flags, followed by this class, the superclass, the interface count, and the interfaces
        val header = ClassReader(classFile).header
        return classFile.copyOf().apply {
            thisClass?.let { writeShort(header + 2, it) }
            superclass?.let { writeShort(header + 4, it) }
            interfaces.forEach { (position, index) -> writeShort(header + 8 + 2 * position, index) }
        }
    }

    /**
     * The constant pool index of the `Utf8` entry naming the class of the [classFile], which is not a `Class` entry.
     */
    private fun utf8IndexOfName(classFile: ByteArray): Int {
        val reader = ClassReader(classFile)
        return reader.readUnsignedShort(reader.getItem(reader.readUnsignedShort(reader.header + 2)))
    }

    @Test
    fun `should keep methods of owner whose class file too new to be parsed has malformed supertype indexes`() {
        // given
        // like relocated by shading from test/ to shaded/test/, and obfuscated, test/Event to a/b,
        // while the overload takes other/Event, which the relocation does not change,
        // the class file of the owner of a version ASM cannot parse, its superclass index out of the constant pool,
        // and the index of its second interface not of a Class entry
        val owner = kmClass("test/Owner", supertype = "test/Listener").apply {
            functions += functionTaking("sh", "test/Event")
            functions += functionTaking("sh", "other/Event")
        }
        val ownerClassFile = classFile(
            "shaded/test/Owner",
            listOf("sh(La/b;)V", "sh(Lother/Event;)V"),
            interfaces = listOf("shaded/test/Listener", "shaded/test/Malformed")
        )
        val resolver = overrideResolver(
            classFiles = mapOf(
                "shaded/test/Owner" to tooNew(
                    withSupertypeIndexes(ownerClassFile, superclass = 0xFFFF, interfaces = mapOf(1 to utf8IndexOfName(ownerClassFile)))
                )
            ),
            unloadableClassFiles = mapOf(
                "shaded/test/Listener" to classFile("shaded/test/Listener", listOf("sh(La/b;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner, "shaded/test/Owner"), it) }

        // then
        // only the malformed supertypes are left out, the methods are read all the same:
        // the JVM method of the overload, taking other/Event, cannot be the relocated sh(test/Event)
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should keep methods and supertypes of owner whose class file too new to be parsed has malformed class index`() {
        // given
        // like an app shading its dependencies, the Java interface lib/Listener taking lib2/Event, and lib2 itself,
        // the class file of the owner of a version ASM cannot parse, the index of the class itself out of the constant pool
        val owner = kmClass("app/Owner", supertype = "lib/Listener").apply {
            functions += functionTaking("on", "lib2/Event")
            functions += functionTaking("on", "lib2/Other")
        }
        val ownerClassFile = classFile(
            "app/Owner",
            listOf("on(Lapp/shaded/lib2/Event;)V", "on(Lapp/shaded/lib2/Other;)V"),
            interfaces = listOf("app/shaded/lib/Listener")
        )
        val resolver = overrideResolver(
            classFiles = mapOf("app/Owner" to tooNew(withSupertypeIndexes(ownerClassFile, thisClass = 0xFFFF))),
            unloadableClassFiles = mapOf(
                "app/shaded/lib/Listener" to classFile(
                    "app/shaded/lib/Listener", listOf("on(Lapp/shaded/lib2/Event;)V"), isInterface = true
                )
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // only the name of the class is unknown, which the class file is not looked up by
        assert(overrides == listOf(true, false))
    }

    /**
     * The [classFile] ending with its only class attribute, the `RuntimeVisibleAnnotations` one,
     * of a single annotation of a single `Int` element, see [classFile], changed in place, like a malformed one,
     * by its offsets from the end of the class file: the attribute count at -19, the index of the attribute name at -17,
     * the attribute length at -15, followed by the annotation count, the index of the annotation type,
     * the element count at -7, the index of the element name, the tag of the element at -3, and the index of its value.
     */
    private fun withAnnotationsAttribute(classFile: ByteArray, change: ByteArray.(end: Int) -> Unit): ByteArray =
        classFile.copyOf().apply { change(size) }

    @Test
    fun `should keep methods and supertypes of owner whose class file too new to be parsed has malformed class attributes`() {
        // given
        // like above, the supertype indexes intact, the class file of the owner of a version ASM cannot parse
        val owner = kmClass("app/Owner", supertype = "lib/Listener").apply {
            functions += functionTaking("on", "lib2/Event")
            functions += functionTaking("on", "lib2/Other")
        }
        val methods = listOf("on(Lapp/shaded/lib2/Event;)V", "on(Lapp/shaded/lib2/Other;)V")
        val interfaces = listOf("app/shaded/lib/Listener")
        val annotated = classFile("app/Owner", methods, interfaces = interfaces, isAnnotated = true)
        val ownerClassFiles = listOf(
            // cut before the count of the class attributes, following the method table
            classFile("app/Owner", methods, interfaces = interfaces).let { it.copyOf(it.size - 2) },
            // the index of the attribute name out of the constant pool
            withAnnotationsAttribute(annotated) { end -> writeShort(end - 17, 0xFFFF) },
            // the attribute longer than the class file, its length of 4 GB
            withAnnotationsAttribute(annotated) { end -> (end - 15 until end - 11).forEach { this[it] = 0xFF.toByte() } },
            // the attribute cut
            annotated.copyOf(annotated.size - 1)
        )

        // when
        val overrides = ownerClassFiles.map { ownerClassFile ->
            val resolver = overrideResolver(
                classFiles = mapOf("app/Owner" to tooNew(ownerClassFile)),
                unloadableClassFiles = mapOf(
                    "app/shaded/lib/Listener" to classFile(
                        "app/shaded/lib/Listener", listOf("on(Lapp/shaded/lib2/Event;)V"), isInterface = true
                    )
                )
            )
            owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }
        }

        // then
        // the supertypes and the method table, preceding the class attributes, are read all the same
        assert(overrides == List(4) { listOf(true, false) })
    }

    @Test
    fun `should not pair supertypes of owner by simple names when its class file too new to be parsed has malformed supertype index`() {
        // given
        // like an app shading its dependencies, a/Base and b/Base, of the same simple name,
        // the class file of the owner of a version ASM cannot parse, the index of its first interface out of the constant pool
        val owner = kmClass("app/Owner", supertype = "a/Base").apply { supertypes += type("b/Base") }
        val resolver = overrideResolver(
            classes = mapOf(
                "app/shaded/a/Base" to kmClass("a/Base", null, "fromA"),
                "app/shaded/b/Base" to kmClass("b/Base", null, "fromB")
            ),
            classFiles = mapOf(
                "app/Owner" to tooNew(
                    withSupertypeIndexes(
                        classFile("app/Owner", "app/shaded/a/Base", "app/shaded/b/Base"), interfaces = mapOf(0 to 0xFFFF)
                    )
                )
            )
        )

        // when
        val overrides = listOf(
            resolver.isOverride(OverrideResolver.Owner(owner), function("fromA")),
            resolver.isOverride(OverrideResolver.Owner(owner), function("fromB"))
        )

        // then
        // the only supertype left, app/shaded/b/Base, is not taken for the relocated a/Base, nor for any other one,
        // as the supertype left out might be of the same simple name
        assert(overrides == listOf(false, false))
    }

    @Test
    fun `should tell overrides of generic Java supertype which cannot be loaded by its class file too new to be parsed`() {
        // given
        // like a Java interface whose own supertype is missing from the classpath, compiled for a JVM newer than ASM knows,
        // its type variable told only by the generic signature of the class
        val owner = kmClass("app/Owner").apply {
            supertypes += type("lib/Listener").apply { arguments += KmTypeProjection(KmVariance.INVARIANT, type("lib/Event")) }
            functions += functionTaking("on", "lib/Event")
            functions += functionTaking("on", "lib/Other")
        }
        val listener = ClassWriter(0).apply {
            visit(
                Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
                "lib/Listener", "<T:Ljava/lang/Object;>Ljava/lang/Object;", "java/lang/Object", null
            )
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "on", "(Ljava/lang/Object;)V", "(TT;)V", null)
            visitEnd()
        }.toByteArray()
        val resolver = overrideResolver(unloadableClassFiles = mapOf("lib/Listener" to tooNew(listener)))

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        assert(overrides == listOf(true, false))
    }

    /**
     * The class file of a class of the [internalName], extending `lib/Base`, and implementing `lib/Listener`,
     * annotated with `lib/Marker`, of an element of each kind, and, if it [isKotlin], with a Kotlin `Metadata`,
     * which cannot be read, [isVisible] at runtime, like the compiler writes it, or not.
     */
    private fun annotatedClassFile(internalName: String, isKotlin: Boolean = false, isVisible: Boolean = true): ByteArray =
        ClassWriter(0).apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "lib/Base", arrayOf("lib/Listener"))
            visitAnnotation("Llib/Marker;", true).apply {
                visit("count", 1)
                visit("name", "marker")
                visit("type", Type.getObjectType("lib/Event"))
                visit("ints", intArrayOf(1, 2))
                visitEnum("kind", "Llib/Kind;", "FIRST")
                visitAnnotation("nested", "Llib/Nested;").apply {
                    visit("flag", true)
                    visitEnd()
                }
                visitArray("values").apply {
                    visit(null, 1L)
                    visit(null, 2.0)
                    visitEnd()
                }
                visitEnd()
            }
            if (isKotlin) {
                visitAnnotation("Lkotlin/Metadata;", isVisible).apply {
                    visit("k", 1)
                    visitEnd()
                }
            }
            visitEnd()
        }.toByteArray()

    @Test
    fun `should tell supertypes of Java class which cannot be loaded by its class file too new to be parsed`() {
        // given
        val resolver = overrideResolver(unloadableClassFiles = mapOf("lib/Mid" to tooNew(annotatedClassFile("lib/Mid"))))

        // when
        val supertypes = resolver.unloadableJavaSupertypeNames("lib/Mid")

        // then
        // the annotation of the class is not the Kotlin metadata
        assert(supertypes == listOf("lib/Base", "lib/Listener"))
    }

    @Test
    fun `should not take class file too new to be parsed of Kotlin class which cannot be loaded for Java class`() {
        // given
        // the metadata following another annotation, whose elements are skipped
        val resolver = overrideResolver(
            unloadableClassFiles = mapOf(
                "lib/Mid" to tooNew(annotatedClassFile("lib/Mid", isKotlin = true)),
                "lib/Invisible" to tooNew(annotatedClassFile("lib/Invisible", isKotlin = true, isVisible = false))
            )
        )

        // when
        val supertypes = listOf("lib/Mid", "lib/Invisible").map { resolver.unloadableJavaSupertypeNames(it) }

        // then
        // the JVM methods of a Kotlin class are not its Kotlin members
        assert(supertypes == List(2) { emptyList<String>() })
    }

    @Test
    fun `should not take class file too new to be parsed of another class for Java class which cannot be loaded`() {
        // given
        // like the class file found for a name differing only in case
        val resolver = overrideResolver(unloadableClassFiles = mapOf("lib/mid" to tooNew(annotatedClassFile("lib/Mid"))))

        // when
        val supertypes = resolver.unloadableJavaSupertypeNames("lib/mid")

        // then
        assert(supertypes == emptyList<String>())
    }

    @Test
    fun `should not take class file of malformed supertype index for Java class which cannot be loaded`() {
        // given
        // the index of the interface out of the constant pool, in a class file ASM can parse otherwise, and in one it cannot
        val resolver = overrideResolver(
            unloadableClassFiles = mapOf(
                "lib/Mid" to withSupertypeIndexes(annotatedClassFile("lib/Mid"), interfaces = mapOf(0 to 0xFFFF)),
                "lib/New" to tooNew(withSupertypeIndexes(annotatedClassFile("lib/New"), interfaces = mapOf(0 to 0xFFFF)))
            )
        )

        // when
        val supertypes = listOf("lib/Mid", "lib/New").map { resolver.unloadableJavaSupertypeNames(it) }

        // then
        // a Java class of a supertype left out would miss the members it inherits, so it stays unknown
        assert(supertypes == List(2) { emptyList<String>() })
    }

    @Test
    fun `should not take class file too new to be parsed of malformed class index for Java class which cannot be loaded`() {
        // given
        // the index of the class itself out of the constant pool, so that the class file does not tell whose it is
        val resolver = overrideResolver(
            unloadableClassFiles = mapOf("lib/Mid" to tooNew(withSupertypeIndexes(annotatedClassFile("lib/Mid"), thisClass = 0xFFFF)))
        )

        // when
        val supertypes = resolver.unloadableJavaSupertypeNames("lib/Mid")

        // then
        assert(supertypes == emptyList<String>())
    }

    @Test
    fun `should not take class file too new to be parsed whose annotations cannot be read for Java class which cannot be loaded`() {
        // given
        // the annotations of a class which might be the Kotlin metadata, as they cannot be told
        val annotated = classFile("lib/Mid", listOf(), superName = "lib/Base", interfaces = listOf("lib/Listener"), isAnnotated = true)
        // an annotation nested deeper than any annotation is, like in a malformed attribute nesting without an end
        val nested = ClassWriter(0).apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "lib/Mid", null, "lib/Base", arrayOf("lib/Listener"))
            generateSequence(visitAnnotation("Llib/Marker;", true)) { it.visitAnnotation("nested", "Llib/Marker;") }
                .take(20)
                .toList()
                .asReversed()
                .forEach { it.visitEnd() }
            visitEnd()
        }.toByteArray()
        val classFiles = listOf(
            // an element of an unknown kind, whose size is unknown
            withAnnotationsAttribute(annotated) { end -> this[end - 3] = '?'.code.toByte() },
            // more elements than the attribute holds
            withAnnotationsAttribute(annotated) { end -> writeShort(end - 7, 2) },
            nested,
            // the attribute cut
            annotated.copyOf(annotated.size - 1)
        )

        // when
        val supertypes = (listOf(annotated) + classFiles).map { classFile ->
            overrideResolver(unloadableClassFiles = mapOf("lib/Mid" to tooNew(classFile))).unloadableJavaSupertypeNames("lib/Mid")
        }

        // then
        // the class file as it is tells a Java class, the others might be of a Kotlin class, whose JVM methods are not its members
        assert(supertypes == listOf(listOf("lib/Base", "lib/Listener")) + List(4) { emptyList<String>() })
    }

    @Test
    fun `should tell relocated Java supertype of indirect supertype next to overloads of the same shape relocated by the same package prefix`() {
        // given
        // like an app shading its dependencies, its class app/Mid implementing the relocated Java interface lib/Listener,
        // the overloads of the owner all of the same shape, so that their JVM methods cannot tell the relocation,
        // with only the shaded jar on the classpath
        val owner = kmClass("app/Owner", supertype = "app/Mid").apply {
            functions += functionTaking("on", "lib/Event", "ext/Event")
            functions += functionTaking("on", "ext/Event", "lib/Event")
            functions += functionTaking("on", "lib/sub/Event", "ext/Event")
        }
        val resolver = overrideResolver(
            classes = mapOf("app/Mid" to kmClass("app/Mid", supertype = "lib/Listener")),
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner",
                    listOf(
                        "on(Lapp/shaded/lib/Event;Lext/Event;)V",
                        "on(Lext/Event;Lapp/shaded/lib/Event;)V",
                        "on(Lapp/shaded/lib/sub/Event;Lext/Event;)V"
                    ),
                    superName = "app/Mid"
                ),
                "app/Mid" to classFile("app/Mid", listOf(), interfaces = listOf("app/shaded/lib/Listener"))
            ),
            unloadableClassFiles = mapOf(
                "app/shaded/lib/Listener" to classFile(
                    "app/shaded/lib/Listener", listOf("on(Lapp/shaded/lib/Event;Lext/Event;)V"), isInterface = true
                )
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // lib/ is told relocated to app/shaded/lib/, by the package prefix pairing the overloads, or by the class file of app/Mid
        assert(overrides == listOf(true, false, false))
    }

    @Test
    fun `should not take overloads of the same shape for overrides of relocated Java supertype of indirect supertype next to the original one`() {
        // given
        // like above, with the original lib jar on the classpath as well
        val owner = kmClass("app/Owner", supertype = "app/Mid").apply {
            functions += functionTaking("on", "lib/Event", "ext/Event")
            functions += functionTaking("on", "ext/Event", "lib/Event")
            functions += functionTaking("on", "lib/sub/Event", "ext/Event")
        }
        val resolver = overrideResolver(
            classes = mapOf("app/Mid" to kmClass("app/Mid", supertype = "lib/Listener")),
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner",
                    listOf(
                        "on(Lapp/shaded/lib/Event;Lext/Event;)V",
                        "on(Lext/Event;Lapp/shaded/lib/Event;)V",
                        "on(Lapp/shaded/lib/sub/Event;Lext/Event;)V"
                    ),
                    superName = "app/Mid"
                ),
                "app/Mid" to classFile("app/Mid", listOf(), interfaces = listOf("app/shaded/lib/Listener"))
            ),
            unloadableClassFiles = mapOf(
                "lib/Listener" to classFile("lib/Listener", listOf("on(Llib/Event;Lext/Event;)V"), isInterface = true),
                "app/shaded/lib/Listener" to classFile(
                    "app/shaded/lib/Listener", listOf("on(Lapp/shaded/lib/Event;Lext/Event;)V"), isInterface = true
                )
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // the JVM methods of the overloads, taking the relocated types, are not taken for shrunk overrides
        assert(overrides == listOf(true, false, false))
    }

    @Test
    fun `should tell override of relocated Java supertype of Java class of the app by the overload left of the same shape`() {
        // given
        // like an app shading its dependencies, relocating p/lib/Event to the root package, the rest of p/lib/ to p/app/shaded/p/lib/,
        // its Java class p/app/Mid, without metadata, implementing the relocated Java interface p/lib/Listener,
        // the overloads of the owner all of the same shape, the package prefix telling only the ones taking p/lib/Event
        val owner = kmClass("p/app/Owner", supertype = "p/app/Mid").apply {
            functions += functionTaking("on", "p/lib/Event", "p/ext/Event")
            functions += functionTaking("on", "p/ext/Event", "p/lib/Event")
            functions += functionTaking("on", "p/lib/sub/Event", "p/ext/Event")
        }
        val mid = classFile("p/app/Mid", listOf(), interfaces = listOf("p/app/shaded/p/lib/Listener"))
        val resolver = overrideResolver(
            classFiles = mapOf(
                "p/app/Owner" to classFile(
                    "p/app/Owner",
                    listOf(
                        "on(LEvent;Lp/ext/Event;)V",
                        "on(Lp/ext/Event;LEvent;)V",
                        "on(Lp/app/shaded/p/lib/sub/Event;Lp/ext/Event;)V"
                    ),
                    superName = "p/app/Mid"
                ),
                "p/app/Mid" to mid
            ),
            unloadableClassFiles = mapOf(
                "p/app/Mid" to mid,
                "p/app/shaded/p/lib/Listener" to classFile(
                    "p/app/shaded/p/lib/Listener", listOf("on(Lp/app/shaded/p/lib/sub/Event;Lp/ext/Event;)V"), isInterface = true
                )
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // the only signature, and the only JVM method, of their shape left unpaired by the package prefix are paired,
        // telling p/lib/sub/ relocated to p/app/shaded/p/lib/sub/, which no supertype of metadata could tell
        assert(overrides == listOf(false, false, true))
    }

    @Test
    fun `should tell relocated Java supertype of indirect supertype by its class file when overloads of the same shape do not tell it`() {
        // given
        // like above, through the Kotlin class p/app/Mid, the package prefix pairing every overload,
        // but telling only p/lib/Event relocated to the root package, not where p/lib/Listener is, with only the shaded jar
        val owner = kmClass("p/app/Owner", supertype = "p/app/Mid").apply {
            functions += functionTaking("on", "p/lib/Event", "p/ext/Event")
            functions += functionTaking("on", "p/ext/Event", "p/lib/Event")
        }
        val resolver = overrideResolver(
            classes = mapOf("p/app/Mid" to kmClass("p/app/Mid", supertype = "p/lib/Listener")),
            classFiles = mapOf(
                "p/app/Owner" to classFile(
                    "p/app/Owner", listOf("on(LEvent;Lp/ext/Event;)V", "on(Lp/ext/Event;LEvent;)V"), superName = "p/app/Mid"
                ),
                "p/app/Mid" to classFile("p/app/Mid", listOf(), interfaces = listOf("p/app/shaded/p/lib/Listener"))
            ),
            unloadableClassFiles = mapOf(
                "p/app/shaded/p/lib/Listener" to classFile(
                    "p/app/shaded/p/lib/Listener", listOf("on(LEvent;Lp/ext/Event;)V"), isInterface = true
                )
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // the supertype missing still, the class file of p/app/Mid tells p/lib/ relocated to p/app/shaded/p/lib/
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should tell relocation of overloads of the same shape taking also classes relocated with the owner by the package prefix`() {
        // given
        // like relocated by shading from app/ to shaded/app/, and lib/ to the root package,
        // the owner implementing the Java interface app/Listener of the app,
        // the overloads of the same shape taking both a class of the app, and one of the library
        val owner = kmClass("app/Owner", supertype = "app/Listener").apply {
            functions += functionTaking("on", "app/Event", "lib/Event")
            functions += functionTaking("on", "lib/Event", "app/Event")
        }
        val resolver = overrideResolver(
            classFiles = mapOf(
                "shaded/app/Owner" to classFile(
                    "shaded/app/Owner",
                    listOf("on(Lshaded/app/Event;LEvent;)V", "on(LEvent;Lshaded/app/Event;)V"),
                    interfaces = listOf("shaded/app/Listener")
                )
            ),
            unloadableClassFiles = mapOf(
                "shaded/app/Listener" to classFile("shaded/app/Listener", listOf("on(Lshaded/app/Event;LEvent;)V"), isInterface = true),
                "app/Listener" to classFile("app/Listener", listOf("on(Lapp/Event;Llib/Event;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner, "shaded/app/Owner"), it) }

        // then
        // the relocation of the owner explains shaded/app/Event, leaving lib/ relocated to the root package to tell
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should not take overload taking class of subpackage relocated to the root package for override of relocated Java supertype`() {
        // given
        // like an app shading its dependencies, relocating p/lib/sub/ to the root package, the rest of p/lib/ to p/app/shaded/p/lib/,
        // the owner implementing the relocated Java interface p/lib/JListener
        val owner = kmClass("p/app/Owner", supertype = "p/lib/JListener").apply {
            functions += functionTaking("on", "p/ext/Event", "p/lib/Event")
            functions += functionTaking("on", "p/ext/Event", "p/lib/sub/Event")
        }
        val resolver = overrideResolver(
            classFiles = mapOf(
                "p/app/Owner" to classFile(
                    "p/app/Owner",
                    listOf("on(Lp/ext/Event;Lp/app/shaded/p/lib/Event;)V", "on(Lp/ext/Event;LEvent;)V"),
                    interfaces = listOf("p/app/shaded/p/lib/JListener")
                )
            ),
            unloadableClassFiles = mapOf(
                "p/app/shaded/p/lib/JListener" to classFile(
                    "p/app/shaded/p/lib/JListener", listOf("on(Lp/ext/Event;Lp/app/shaded/p/lib/Event;)V"), isInterface = true
                )
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should not take overload taking class of subpackage relocated to the root package for override of relocated Java supertype of indirect supertype`() {
        // given
        // like above, through the class p/app/Mid of the app, whose class file tells p/lib/ relocated to p/app/shaded/p/lib/
        val owner = kmClass("p/app/Owner", supertype = "p/app/Mid").apply {
            functions += functionTaking("on", "p/ext/Event", "p/lib/Event")
            functions += functionTaking("on", "p/ext/Event", "p/lib/sub/Event")
        }
        val resolver = overrideResolver(
            classes = mapOf("p/app/Mid" to kmClass("p/app/Mid", supertype = "p/lib/JListener")),
            classFiles = mapOf(
                "p/app/Owner" to classFile(
                    "p/app/Owner",
                    listOf("on(Lp/ext/Event;Lp/app/shaded/p/lib/Event;)V", "on(Lp/ext/Event;LEvent;)V"),
                    superName = "p/app/Mid"
                ),
                "p/app/Mid" to classFile("p/app/Mid", listOf(), interfaces = listOf("p/app/shaded/p/lib/JListener"))
            ),
            unloadableClassFiles = mapOf(
                "p/app/shaded/p/lib/JListener" to classFile(
                    "p/app/shaded/p/lib/JListener", listOf("on(Lp/ext/Event;Lp/app/shaded/p/lib/Event;)V"), isInterface = true
                )
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // the JVM method the class file of p/app/Mid explains is paired first, leaving the other one paired with p/lib/sub/Event
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should not read class files of supertypes for JVM method of no member paired with shrunk one of its shape`() {
        // given
        // a class which is not relocated, its supertype p/lib/Listener missing from the classpath,
        // its overload on(p/lib/Event, p/ext/Event) shrunk away, next to the JVM method of a @JvmOverloads one of the same shape
        val owner = kmClass("p/app/Owner", supertype = "p/app/Mid").apply {
            functions += functionTaking("on", "p/ext/Event", "p/lib/Event")
            functions += functionTaking("on", "p/lib/Event", "p/ext/Event")
            functions += functionTaking("on", "p/ext/Event", "p/lib/sub/Event", "kotlin/Int")
        }
        val classFiles = mapOf(
            "p/app/Owner" to classFile(
                "p/app/Owner",
                listOf(
                    "on(Lp/ext/Event;Lp/lib/Event;)V",
                    "on(Lp/ext/Event;Lp/lib/sub/Event;Lkotlin/Int;)V",
                    "on(Lp/ext/Event;Lp/lib/sub/Event;)V"
                ),
                superName = "p/app/Mid"
            ),
            "p/app/Mid" to classFile("p/app/Mid", listOf(), interfaces = listOf("p/lib/Listener"))
        )
        val reads = mutableListOf<String>()
        val resolver = overrideResolver(
            classes = mapOf("p/app/Mid" to kmClass("p/app/Mid", supertype = "p/lib/Listener")),
            readClassFile = { name -> reads += name; classFiles[name] }
        )

        // when
        owner.functions.forEach { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // the classes paired, p/ext/Event and p/lib/sub/Event, are the ones the metadata refers to by these very names
        assert("p/app/Mid" !in reads)
    }

    @Test
    fun `should find relocated Java supertype of package whose other classes are relocated to the root package`() {
        // given
        // like an app shading its dependencies, relocating lib/ to app/shaded/lib/, except for lib/Event relocated to the root package
        val owner = kmClass("app/Owner", supertype = "lib/Listener").apply {
            functions += functionTaking("emit", "lib/Event")
            functions += functionTaking("on", "lib/Other")
            functions += functionTaking("on", "lib/Third")
        }
        val resolver = overrideResolver(
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner", listOf("emit(LEvent;)V", "on(Lapp/shaded/lib/Other;)V", "on(Lapp/shaded/lib/Third;)V"),
                    interfaces = listOf("app/shaded/lib/Listener")
                )
            ),
            unloadableClassFiles = mapOf(
                "app/shaded/lib/Listener" to classFile("app/shaded/lib/Listener", listOf("on(Lapp/shaded/lib/Other;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // lib/ is relocated to app/shaded/lib/, its class lib/Event to the root package, one by one
        assert(overrides == listOf(false, true, false))
    }

    @Test
    fun `should find Java supertype of indirect supertype relocated to the root package next to owner relocated by prefix`() {
        // given
        // like relocated by shading from app/ to shaded/app/, the prefix shaded/ not relocating lib/, relocated to the root package
        val owner = kmClass("app/Owner", supertype = "app/Mid").apply {
            functions += functionTaking("on", "lib/Event")
            functions += functionTaking("on", "lib/Other")
        }
        val resolver = overrideResolver(
            classes = mapOf("shaded/app/Mid" to kmClass("app/Mid", supertype = "lib/Listener")),
            classFiles = mapOf(
                "shaded/app/Owner" to classFile("shaded/app/Owner", listOf("on(LEvent;)V", "on(LOther;)V"), superName = "shaded/app/Mid")
            ),
            unloadableClassFiles = mapOf("Listener" to classFile("Listener", listOf("on(LEvent;)V"), isInterface = true))
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner, "shaded/app/Owner"), it) }

        // then
        // the JVM methods of the owner tell the classes of lib/ relocated to the root package, not by the prefix shaded/
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should not take overload of the same shape for shrunk override of owner relocated by prefix next to library relocated to the root package`() {
        // given
        // like relocated by shading from app/ to shaded/app/, and lib/ to the root package, lib/sub/ to sub/,
        // the overloads of the owner all of the same shape, its class app/Mid implementing lib/Listener
        val owner = kmClass("app/Owner", supertype = "app/Mid").apply {
            functions += functionTaking("on", "lib/Event", "ext/Event")
            functions += functionTaking("on", "ext/Event", "lib/Event")
            functions += functionTaking("on", "lib/sub/Event", "ext/Event")
        }
        val resolver = overrideResolver(
            classes = mapOf("shaded/app/Mid" to kmClass("app/Mid", supertype = "lib/Listener")),
            classFiles = mapOf(
                "shaded/app/Owner" to classFile(
                    "shaded/app/Owner",
                    listOf("on(LEvent;Lext/Event;)V", "on(Lext/Event;LEvent;)V", "on(Lsub/Event;Lext/Event;)V"),
                    superName = "shaded/app/Mid"
                ),
                "shaded/app/Mid" to classFile("shaded/app/Mid", listOf(), interfaces = listOf("Listener"))
            ),
            unloadableClassFiles = mapOf("Listener" to classFile("Listener", listOf("on(LEvent;Lext/Event;)V"), isInterface = true))
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner, "shaded/app/Owner"), it) }

        // then
        // the prefix shaded/ only guesses where lib/sub/Event is, so the JVM method taking sub/Event might be its relocated one
        assert(overrides == listOf(true, false, false))
    }

    @Test
    fun `should tell relocation of overloads of the same shape by the package prefix relocated for most of them`() {
        // given
        // like relocated by shading from app/ to shaded/app/, and lib/ to the root package, lib/sub/ to sub/,
        // the owner implementing the Java interface app/Listener of the app, taking lib/Event
        val owner = kmClass("app/Owner", supertype = "app/Listener").apply {
            functions += functionTaking("on", "lib/Event")
            functions += functionTaking("on", "lib/sub/Event")
        }
        val resolver = overrideResolver(
            classFiles = mapOf(
                "shaded/app/Owner" to classFile(
                    "shaded/app/Owner", listOf("on(LEvent;)V", "on(Lsub/Event;)V"), interfaces = listOf("shaded/app/Listener")
                )
            ),
            unloadableClassFiles = mapOf(
                "shaded/app/Listener" to classFile("shaded/app/Listener", listOf("on(LEvent;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner, "shaded/app/Owner"), it) }

        // then
        // lib/ relocated to the root package tells both JVM methods, unlike lib/ to sub/, or lib/sub/ to the root package
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should not read class files of supertypes when overloads of the same shape are paired by the package prefix`() {
        // given
        // like above, through the class app/Mid of the app
        val owner = kmClass("app/Owner", supertype = "app/Mid").apply {
            functions += functionTaking("on", "lib/Event")
            functions += functionTaking("on", "lib/sub/Event")
        }
        val classFiles = mapOf(
            "shaded/app/Owner" to classFile("shaded/app/Owner", listOf("on(LEvent;)V", "on(Lsub/Event;)V"), superName = "shaded/app/Mid"),
            "shaded/app/Mid" to classFile("shaded/app/Mid", listOf(), interfaces = listOf("shaded/app/Listener"))
        )
        val reads = mutableListOf<String>()
        val resolver = overrideResolver(
            classes = mapOf("shaded/app/Mid" to kmClass("app/Mid", supertype = "app/Listener")),
            unloadableClassFiles = mapOf(
                "shaded/app/Listener" to classFile("shaded/app/Listener", listOf("on(LEvent;)V"), isInterface = true)
            ),
            readClassFile = { name -> reads += name; classFiles[name] }
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner, "shaded/app/Owner"), it) }

        // then
        // nothing is left for the class file of app/Mid to tell
        assert(overrides == listOf(true, false))
        assert("shaded/app/Mid" !in reads)
    }

    @Test
    fun `should prefer relocation told by JVM method of its own shape over the package prefix of overloads of the same shape`() {
        // given
        // like an app shading its dependencies, its JVM methods telling lib/Event relocated to shaded/Event,
        // while lib/ relocated to the root package tells the overloads of the same shape best
        val owner = kmClass("app/Owner", supertype = "app/Listener").apply {
            functions += functionTaking("emit", "lib/Event")
            functions += functionTaking("on", "lib/Event")
            functions += functionTaking("on", "lib/sub/Event")
        }
        val resolver = overrideResolver(
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner", listOf("emit(Lshaded/Event;)V", "on(LEvent;)V", "on(Lsub/Event;)V"), interfaces = listOf("app/Listener")
                )
            ),
            unloadableClassFiles = mapOf(
                "app/Listener" to classFile("app/Listener", listOf("emit(Lshaded/Event;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // the JVM method of emit, the only one of its shape, tells lib/Event as shaded/Event
        assert(overrides == listOf(true, false, false))
    }

    @Test
    fun `should match relocated parameter types of Java interface of the app implemented by owner which is not relocated`() {
        // given
        // like an app shading its dependencies, its own Java interface app/JavaListener taking the relocated lib/Event
        val owner = kmClass("app/Owner", supertype = "app/JavaListener").apply {
            functions += functionTaking("on", "lib/Event")
            functions += functionTaking("on", "lib/Other")
        }
        val resolver = overrideResolver(
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner", listOf("on(Lapp/shaded/lib/Event;)V", "on(Lapp/shaded/lib/Other;)V"),
                    interfaces = listOf("app/JavaListener")
                )
            ),
            unloadableClassFiles = mapOf(
                "app/JavaListener" to classFile("app/JavaListener", listOf("on(Lapp/shaded/lib/Event;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should not take overload for override of Java supertype of owner relocated to the root package taking types of subpackage`() {
        // given
        // like relocated by shading from test/ to the root package, and test/sub/ to sub/
        val owner = kmClass("test/Owner", supertype = "test/Listener").apply {
            functions += functionTaking("on", "test/sub/Event")
            functions += functionTaking("on", "test/sub/Other")
        }
        val resolver = overrideResolver(
            classFiles = mapOf(
                "Owner" to classFile("Owner", listOf("on(Lsub/Event;)V", "on(Lsub/Other;)V"), interfaces = listOf("Listener"))
            ),
            unloadableClassFiles = mapOf("Listener" to classFile("Listener", listOf("on(Lsub/Event;)V"), isInterface = true))
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner, "Owner"), it) }

        // then
        // the supertype is relocated with the owner, so only the JVM methods tell test/sub/ relocated to sub/
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should not take overload for override of relocated Java supertype of the root package next to the original one`() {
        // given
        // like an app shading its dependencies, the Java interface Listener of the root package relocated to app/shaded/
        val owner = kmClass("app/Owner", supertype = "Listener").apply {
            functions += functionTaking("on", "Event")
            functions += functionTaking("on", "Other")
        }
        val resolver = overrideResolver(
            classFiles = mapOf(
                "app/Owner" to classFile(
                    "app/Owner", listOf("on(Lapp/shaded/Event;)V", "on(Lapp/shaded/Other;)V"),
                    interfaces = listOf("app/shaded/Listener")
                )
            ),
            unloadableClassFiles = mapOf(
                "Listener" to classFile("Listener", listOf("on(LEvent;)V"), isInterface = true),
                "app/shaded/Listener" to classFile("app/shaded/Listener", listOf("on(Lapp/shaded/Event;)V"), isInterface = true)
            )
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner), it) }

        // then
        // the classes of the root package are relocated one by one, not with every other package
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should match types of Java method of relocated owner by JVM name or by metadata name each`() {
        // given
        // like relocated by shading from test/ to com/test/, the prefix com/ not relocating com/google/,
        // a Java interface taking a type variable bounded by the relocated test/Event, and the com/google/Json
        fun on(bound: String) = function("on").apply {
            typeParameters += KmTypeParameter("E", 0, KmVariance.INVARIANT).apply { upperBounds += type(bound) }
            valueParameters += KmValueParameter("event").apply { type = KmType().apply { classifier = KmClassifier.TypeParameter(0) } }
            valueParameters += KmValueParameter("json").apply { type = type("com/google/Json") }
            signature = JvmMethodSignature("on", "(L$bound;Lcom/google/Json;)V")
        }
        val owner = kmClass("test/Owner", supertype = "test/Listener").apply {
            functions += on("test/Event")
            functions += on("test/Other")
        }
        val listener = ClassWriter(0).apply {
            visit(
                Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
                "com/test/Listener", null, "java/lang/Object", null
            )
            visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "on", "(Lcom/test/Event;Lcom/google/Json;)V",
                "<E:Lcom/test/Event;>(TE;Lcom/google/Json;)V", null
            )
            visitEnd()
        }.toByteArray()
        val resolver = overrideResolver(
            classFiles = mapOf(
                "com/test/Owner" to classFile(
                    "com/test/Owner",
                    listOf("on(Lcom/test/Event;Lcom/google/Json;)V", "on(Lcom/test/Other;Lcom/google/Json;)V"),
                    interfaces = listOf("com/test/Listener")
                )
            ),
            unloadableClassFiles = mapOf("com/test/Listener" to listener)
        )

        // when
        val overrides = owner.functions.map { resolver.isOverride(OverrideResolver.Owner(owner, "com/test/Owner"), it) }

        // then
        // the erasure of the type variable is matched by its metadata name, test/Event, and the Json by its JVM name,
        // and so are the JVM methods of the owner, so that the overload is not taken for a shrunk override
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should tell redundant use-site variance of type parameter of class found by its located name`() {
        // given
        // like relocated by shading from test/ to shaded/test/, with only the shaded jar on the classpath
        fun boxOf(variance: KmVariance) = type("test/Box").apply { arguments += KmTypeProjection(variance, type("test/Item")) }
        val owner = kmClass("test/Owner", supertype = "test/Base").apply {
            functions += function("f").apply { valueParameters += KmValueParameter("box").apply { type = boxOf(KmVariance.INVARIANT) } }
        }
        val base = kmClass("test/Base").apply {
            functions += function("f").apply { valueParameters += KmValueParameter("box").apply { type = boxOf(KmVariance.OUT) } }
        }
        val box = kmClass("test/Box").apply { typeParameters += KmTypeParameter("T", 0, KmVariance.OUT) }
        val resolver = overrideResolver(classes = mapOf("shaded/test/Base" to base, "shaded/test/Box" to box))

        // when
        val isOverride = resolver.isOverride(OverrideResolver.Owner(owner, "shaded/test/Owner"), owner.functions.single())

        // then
        // Box<out Item> is Box<Item>, as Box declares its type parameter covariant
        assert(isOverride)
    }

    @Test
    fun `should not relocate subpackage of package relocated to the root package by relocation of enclosing package`() {
        // given
        val relocation = checkNotNull(Relocation.ofDependencies(mapOf("a" to "shaded/a", "a/b" to ""), emptySet()))

        // when
        val located = listOf(relocation.located("a/X"), relocation.located("a/b/X"), relocation.located("a/b/c/X"))

        // then
        // the subpackages of a/b, relocated to the root package, cannot be told
        assert(located == listOf("shaded/a/X", "X", null))
    }

    @Test
    fun `should not translate back names of root package to which also other packages are relocated`() {
        // given
        // like relocated by shading from app/ to the root package, with the classes of lib/
        val relocation = checkNotNull(Relocation.of("app/Owner", "Owner"))
            .withDependencies(emptyMap(), emptySet(), dependencyClasses = mapOf("lib/Listener" to "Listener"), dependencyRootPackages = setOf("lib"))

        // when
        val unlocated = listOf(relocation.unlocated("Event"), relocation.unlocated("Listener"))

        // then
        // Event might be app/Event or lib/Event, while Listener is told class by class
        assert(unlocated == listOf("Event", "lib/Listener"))
    }

    @Test
    fun `should only guess names of root package and of removed prefix the metadata might refer to`() {
        // given
        // like an app shading its dependencies, relocating the classes of lib/ to the root package
        val rootRelocation = checkNotNull(
            Relocation.ofDependencies(emptyMap(), emptySet(), mapOf("lib/Listener" to "Listener"), setOf("lib"))
        )
        // like relocated by shading from p/app/ to app/
        val removedPrefixRelocation = checkNotNull(Relocation.of("p/app/Owner", "app/Owner"))

        // when
        val unlocated = listOf(rootRelocation.unlocated("Event"), removedPrefixRelocation.unlocated("ext/Event"))
        val guessed = listOf(
            rootRelocation.guessedUnlocated("Event"),
            rootRelocation.guessedUnlocated("Listener"),
            removedPrefixRelocation.guessedUnlocated("ext/Event"),
            removedPrefixRelocation.guessedUnlocated("java/lang/Object")
        )

        // then
        // Event might be a genuine class of the root package, and ext/Event of the package ext/, while a JDK class is never relocated
        assert(unlocated == listOf("Event", "ext/Event"))
        assert(guessed == listOf("lib/Event", null, "p/ext/Event", null))
    }

    @Test
    fun `should tell class which might be relocated to JVM name by its located name, unless relocated elsewhere`() {
        // given
        val rootRelocation = checkNotNull(Relocation.of("app/Owner", "Owner"))
            .withDependencies(emptyMap(), emptySet(), dependencyClasses = mapOf("lib/Listener" to "Listener"), dependencyRootPackages = setOf("lib"))
        val dependencyRelocation = checkNotNull(Relocation.ofDependencies(mapOf("lib" to "shaded/lib"), emptySet()))

        // when
        val mightBeRelocated = listOf(
            rootRelocation.mightBeRelocated("app/Event", "Event"),
            rootRelocation.mightBeRelocated("lib/Event", "Event"),
            dependencyRelocation.mightBeRelocated("lib/Event", "shaded/lib/Event"),
            dependencyRelocation.mightBeRelocated("app/Event", "shaded/lib/Event")
        )

        // then
        // Event, which cannot be translated back, is where app/Event is found, and where lib/Event might be,
        // while shaded/lib/Event is lib/Event, not app/Event
        assert(mightBeRelocated == listOf(true, true, true, false))
    }

    @Test
    fun `should not translate back names of package to which more than one package is relocated`() {
        // given
        val relocation = checkNotNull(Relocation.ofDependencies(mapOf("a" to "shaded", "b" to "shaded", "c" to "other"), emptySet()))

        // when
        val unlocated = listOf(relocation.unlocated("shaded/X"), relocation.unlocated("other/X"))

        // then
        assert(unlocated == listOf("shaded/X", "c/X"))
    }

    @Test
    fun `should find supertype of relocated owner which its class file tells is not relocated by its metadata name`() {
        // given
        // like a class excluded from the relocation, while another jar has a copy relocated with the same prefix
        val owner = kmClass("test/Owner", supertype = "other/Base")
        val resolver = overrideResolver(
            classes = mapOf(
                "other/Base" to kmClass("other/Base", null, "original"),
                "shaded/other/Base" to kmClass("other/Base", null, "relocated")
            ),
            classFiles = mapOf("shaded/test/Owner" to classFile("shaded/test/Owner", "other/Base"))
        )
        val relocated = OverrideResolver.Owner(owner, "shaded/test/Owner")

        // when
        val overrides = listOf(
            resolver.isOverride(relocated, function("original")),
            resolver.isOverride(relocated, function("relocated"))
        )

        // then
        assert(overrides == listOf(true, false))
    }

    @Test
    fun `should tell override of package-private Java method by local class of the same package`() {
        // given
        val javaPackage = "com/xemantic/kotlin/introspection/test/other"
        // the metadata name of a local class starts with '.'
        val owner = kmClass(".$javaPackage/Local", supertype = "$javaPackage/PackagePrivateBase")
        val resolver = overrideResolver(
            loadClass = { name -> if (name == "$javaPackage/PackagePrivateBase") PackagePrivateBase::class.java else null }
        )

        // when
        val isOverride = resolver.isOverride(OverrideResolver.Owner(owner), function("hidden"))

        // then
        assert(isOverride)
    }

    @Test
    fun `should resolve type parameter of outer class of relocated inner class found by its located name`() {
        // given
        // like `class Outer<K> { inner class Inner : Base<String>() { fun accept(value: K) } }`,
        // relocated with the outer class, but not with the base class
        val outer = kmClass("test/Outer").apply { typeParameters += KmTypeParameter("K", 0, KmVariance.INVARIANT) }
        val inner = kmClass("test/Outer.Inner").apply {
            isInner = true
            supertypes += type("test/Base").apply {
                arguments += KmTypeProjection(KmVariance.INVARIANT, type("kotlin/String"))
            }
        }
        val resolver = overrideResolver(classes = mapOf("shaded/test/Outer" to outer, "test/Base" to genericBase("test/Base")))

        // when
        val isOverride = resolver.isOverride(
            OverrideResolver.Owner(inner, "shaded/test/Outer.Inner"), typeParameterFunction("accept", 0)
        )

        // then
        // accept(value: K) overloads accept(value: String), unless K, not found, were taken for an unknown type matching any
        assert(!isOverride)
    }

    @Test
    fun `should cache overridables computed while another thread fails transiently`() {
        // given
        val transientFailures = TransientFailures()
        val base = kmClass("test/Base", null, "foo")
        val owner = kmClass("test/Owner", supertype = "test/Base")
        val lookups = AtomicInteger()
        val lookingUp = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val resolver = overrideResolver(transientFailures = transientFailures) { name ->
            lookups.incrementAndGet()
            if (name == "test/Base" && lookingUp.count > 0) {
                lookingUp.countDown()
                awaitOrFail(failed)
            }
            if (name == "test/Base") base else null
        }
        // a transient failure of a lookup of another computation, on another thread
        val other = testThread {
            awaitOrFail(lookingUp)
            transientFailures.mark()
            failed.countDown()
        }

        // when
        val first = resolver.isOverride(OverrideResolver.Owner(owner), function("foo"))
        joinOrFail(other)
        val lookupsOfFirst = lookups.get()
        val second = resolver.isOverride(OverrideResolver.Owner(owner), function("foo"))

        // then
        assert(first && second)
        assert(lookups.get() == lookupsOfFirst)
    }

    @Test
    fun `should tell DefaultImpls of interface whose class files are not served from its metadata`() {
        // given
        // like an in-memory class loader of a shrunk app, defining the classes without serving their class files,
        // and without the InnerClasses attribute, which R8 removes unless told to keep it
        val fixturesPackage = "com.xemantic.kotlin.introspection.defaultimpls."
        val classLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
                if (!name.startsWith(fixturesPackage)) return super.loadClass(name, resolve)
                findLoadedClass(name)?.let { return it }
                val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")
                    ?.use { it.readBytes() }
                    ?: throw ClassNotFoundException(name)
                withoutInnerClasses(bytes).let { defineClass(name, it, 0, it.size) }
            }
        }
        val failedLoads = mutableListOf<String>()
        fun loadClass(name: String): Class<*>? = try {
            Class.forName(binaryNameOf(name), false, classLoader)
        } catch (_: ClassNotFoundException) {
            failedLoads += name
            null
        }
        fun loadKmClass(name: String): KmClass? = loadClass(name)?.getAnnotation(Metadata::class.java)?.let {
            (KotlinClassMetadata.readStrict(it) as? KotlinClassMetadata.Class)?.kmClass
        }
        val resolver = overrideResolver(classLoader = classLoader, loadClass = ::loadClass, loadKmClass = ::loadKmClass)
        // compiled with -jvm-default=disable, its private overload is only in DefaultImplsGreeter${'$'}DefaultImpls
        val greeter = checkNotNull(loadKmClass("com/xemantic/kotlin/introspection/defaultimpls/DefaultImplsGreeter"))
        val privateOverload = greeter.functions.single { it.name == "greet" && it.visibility == Visibility.PRIVATE }
        // compiled with the default -jvm-default, without any member body, so without DefaultImpls
        val processor = checkNotNull(loadKmClass("com/xemantic/kotlin/introspection/test/OverloadingProcessorInterface"))
        val overload = processor.functions.single { it.name == "process" }

        // when
        val isPrivateOverloadOverride = resolver.isOverride(OverrideResolver.Owner(greeter), privateOverload)
        val isOverloadOverride = resolver.isOverride(OverrideResolver.Owner(processor), overload)

        // then
        // the private overload, absent from the interface itself, is not taken for a shrunk override
        assert(!isPrivateOverloadOverride && !isOverloadOverride)
        // the DefaultImpls class, which an interface without member bodies does not have, is not probed in vain
        assert(failedLoads.none { it.endsWith(".DefaultImpls") })
    }

}
