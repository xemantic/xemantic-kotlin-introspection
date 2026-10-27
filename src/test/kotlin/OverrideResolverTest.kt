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
import kotlin.metadata.modality
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility
import kotlin.test.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

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
     */
    private fun overrideResolver(
        builtinClasses: Map<String, KmClass> = emptyMap(),
        classes: Map<String, KmClass> = emptyMap(),
        classFiles: Map<String, ByteArray> = emptyMap(),
        unloadableClassFiles: Map<String, ByteArray> = emptyMap(),
        transientFailures: TransientFailures = TransientFailures(),
        classLoader: ClassLoader = javaClass.classLoader,
        loadClass: (String) -> Class<*>? = { null },
        loadKmClass: (String) -> KmClass? = { classes[it] }
    ) = OverrideResolver(
        classLoader = classLoader,
        loadClassByMetadataName = loadClass,
        loadKmClassByMetadataName = loadKmClass,
        readClassBytesByMetadataName = { classFiles[it] },
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

    /**
     * A function taking a value of the class of the [typeName], like `on(event: lib/Event)`,
     * with its JVM signature, which shading does not rewrite in the metadata.
     */
    private fun functionTaking(name: String, typeName: String) = function(name).apply {
        valueParameters += KmValueParameter("value").apply { type = type(typeName) }
        signature = JvmMethodSignature(name, "(L$typeName;)V")
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
     */
    private fun classFile(
        internalName: String,
        methods: List<String>,
        superName: String = "java/lang/Object",
        interfaces: List<String> = emptyList(),
        isInterface: Boolean = false
    ): ByteArray = ClassWriter(0).apply {
        val access = if (isInterface) Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE else 0
        visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or access, internalName, null, superName, interfaces.toTypedArray())
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
