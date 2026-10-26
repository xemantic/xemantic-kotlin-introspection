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
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.visibility
import kotlin.test.Test

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

    private fun overrideResolver(
        builtinClasses: Map<String, KmClass> = emptyMap(),
        classes: Map<String, KmClass> = emptyMap(),
        transientFailures: TransientFailures = TransientFailures(),
        loadKmClass: (String) -> KmClass? = { classes[it] }
    ) = OverrideResolver(
        classLoader = javaClass.classLoader,
        loadClassByMetadataName = { null },
        loadKmClassByMetadataName = loadKmClass,
        readClassBytesByMetadataName = { null },
        unloadableClassBytesByMetadataName = { null },
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
            resolver.isOverride(OverrideResolver.Owner(builtinOwner, builtinOwner.name), function("fromBuiltin")),
            resolver.isOverride(OverrideResolver.Owner(builtinOwner, builtinOwner.name), function("fromClassFile"))
        )
        val classFileOverrides = listOf(
            resolver.isOverride(OverrideResolver.Owner(classFileOwner, classFileOwner.name), function("fromBuiltin")),
            resolver.isOverride(OverrideResolver.Owner(classFileOwner, classFileOwner.name), function("fromClassFile"))
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
    fun `should find supertypes of relocated owner which are not relocated by their metadata names`() {
        // given
        // like a supertype of another package, which is not told to be relocated, or one excluded from the relocation
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
        val first = resolver.isOverride(OverrideResolver.Owner(owner, owner.name), function("foo"))
        joinOrFail(other)
        val lookupsOfFirst = lookups.get()
        val second = resolver.isOverride(OverrideResolver.Owner(owner, owner.name), function("foo"))

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
        val resolver = OverrideResolver(
            classLoader = classLoader,
            loadClassByMetadataName = ::loadClass,
            loadKmClassByMetadataName = ::loadKmClass,
            readClassBytesByMetadataName = { null },
            unloadableClassBytesByMetadataName = { null },
            transientFailures = TransientFailures(),
            builtinClasses = { emptyMap() },
            builtinTypes = emptyMap()
        )
        // compiled with -jvm-default=disable, its private overload is only in DefaultImplsGreeter${'$'}DefaultImpls
        val greeter = checkNotNull(loadKmClass("com/xemantic/kotlin/introspection/defaultimpls/DefaultImplsGreeter"))
        val privateOverload = greeter.functions.single { it.name == "greet" && it.visibility == Visibility.PRIVATE }
        // compiled with the default -jvm-default, without any member body, so without DefaultImpls
        val processor = checkNotNull(loadKmClass("com/xemantic/kotlin/introspection/test/OverloadingProcessorInterface"))
        val overload = processor.functions.single { it.name == "process" }

        // when
        val isPrivateOverloadOverride = resolver.isOverride(OverrideResolver.Owner(greeter, greeter.name), privateOverload)
        val isOverloadOverride = resolver.isOverride(OverrideResolver.Owner(processor, processor.name), overload)

        // then
        // the private overload, absent from the interface itself, is not taken for a shrunk override
        assert(!isPrivateOverloadOverride && !isOverloadOverride)
        // the DefaultImpls class, which an interface without member bodies does not have, is not probed in vain
        assert(failedLoads.none { it.endsWith(".DefaultImpls") })
    }

}
