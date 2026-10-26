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
import kotlin.metadata.Modality
import kotlin.metadata.Visibility
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
            resolver.isOverride(builtinOwner, function("fromBuiltin")),
            resolver.isOverride(builtinOwner, function("fromClassFile"))
        )
        val classFileOverrides = listOf(
            resolver.isOverride(classFileOwner, function("fromBuiltin")),
            resolver.isOverride(classFileOwner, function("fromClassFile"))
        )

        // then
        assert(builtinOverrides == listOf(true, false))
        assert(classFileOverrides == listOf(false, true))
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
        val first = resolver.isOverride(owner, function("foo"))
        joinOrFail(other)
        val lookupsOfFirst = lookups.get()
        val second = resolver.isOverride(owner, function("foo"))

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
        val isPrivateOverloadOverride = resolver.isOverride(greeter, privateOverload)
        val isOverloadOverride = resolver.isOverride(processor, overload)

        // then
        // the private overload, absent from the interface itself, is not taken for a shrunk override
        assert(!isPrivateOverloadOverride && !isOverloadOverride)
        // the DefaultImpls class, which an interface without member bodies does not have, is not probed in vain
        assert(failedLoads.none { it.endsWith(".DefaultImpls") })
    }

}
