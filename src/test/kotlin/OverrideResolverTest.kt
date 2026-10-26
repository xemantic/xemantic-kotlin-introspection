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
import kotlin.concurrent.thread
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
                failed.await()
            }
            if (name == "test/Base") base else null
        }
        // a transient failure of a lookup of another computation, on another thread
        val other = thread {
            lookingUp.await()
            transientFailures.mark()
            failed.countDown()
        }

        // when
        val first = resolver.isOverride(owner, function("foo"))
        other.join()
        val lookupsOfFirst = lookups.get()
        val second = resolver.isOverride(owner, function("foo"))

        // then
        assert(first && second)
        assert(lookups.get() == lookupsOfFirst)
    }

    @Test
    fun `should find DefaultImpls among nested classes of interface whose class files are not served`() {
        // given
        val requestedClasses = mutableListOf<String>()
        fun loadClass(name: String): Class<*>? {
            requestedClasses += name
            return try {
                Class.forName(binaryNameOf(name), false, javaClass.classLoader)
            } catch (_: ClassNotFoundException) {
                null
            }
        }
        fun loadKmClass(name: String): KmClass? = loadClass(name)?.getAnnotation(Metadata::class.java)?.let {
            (KotlinClassMetadata.readStrict(it) as? KotlinClassMetadata.Class)?.kmClass
        }
        // like an in-memory class loader, defining the classes without serving their class files as resources
        val resolver = OverrideResolver(
            classLoader = javaClass.classLoader,
            loadClassByMetadataName = ::loadClass,
            loadKmClassByMetadataName = ::loadKmClass,
            readClassBytesByMetadataName = { null },
            unloadableClassBytesByMetadataName = { null },
            transientFailures = TransientFailures(),
            builtinClasses = { emptyMap() },
            builtinTypes = emptyMap()
        )
        val greeterName = "com/xemantic/kotlin/introspection/defaultimpls/DefaultImplsGreeter"
        val greeter = checkNotNull(loadKmClass(greeterName))
        // compiled with -jvm-default=disable, its JVM method is only in DefaultImplsGreeter${'$'}DefaultImpls
        val privateOverload = greeter.functions.single { it.name == "greet" && it.visibility == Visibility.PRIVATE }

        // when
        val isOverride = resolver.isOverride(greeter, privateOverload)

        // then
        // the private overload, absent from the interface itself, is not taken for a shrunk override
        assert(!isOverride)
        // the DefaultImpls class, which most interfaces do not have, is not probed by its name
        assert(requestedClasses.none { it.endsWith(".DefaultImpls") })
    }

}
