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
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmVariance
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.test.Test

class ReceiverIndexKeyTest {

    private fun classType(name: String, vararg arguments: KmType) = KmType().apply {
        classifier = KmClassifier.Class(name)
        this.arguments += arguments.map { KmTypeProjection(KmVariance.INVARIANT, it) }
    }

    @Test
    fun `should key a class receiver by its qualified name`() {
        // given
        val type = classType("kotlin/collections/List", classType("kotlin/String"))

        // when
        val key = receiverIndexKeys(type).single()

        // then
        assert(key == "kotlin.collections.List")
    }

    private fun typeParameterType(id: Int, nullable: Boolean = false) = KmType().apply {
        classifier = KmClassifier.TypeParameter(id)
        isNullable = nullable
    }

    private fun typeParameter(id: Int, vararg upperBounds: KmType) =
        KmTypeParameter("T$id", id, KmVariance.INVARIANT).apply { this.upperBounds += upperBounds }

    @Test
    fun `should key a type parameter receiver apart from any class name`() {
        // given
        val type = typeParameterType(0)

        // when
        val keys = receiverIndexKeys(type, listOf(typeParameter(0)))

        // then
        assert(keys.size == 1 && !couldBeClassKey(keys.single()))
    }

    @Test
    fun `should key a type parameter receiver bounded by nullable Any apart from any class name`() {
        // given
        val type = typeParameterType(0)
        val bound = classType("kotlin/Any").apply { isNullable = true }

        // when
        val keys = receiverIndexKeys(type, listOf(typeParameter(0, bound)))

        // then
        assert(keys.size == 1 && !couldBeClassKey(keys.single()))
    }

    @Test
    fun `should key a type parameter receiver of an unknown type parameter apart from any class name`() {
        // given
        val type = typeParameterType(1)

        // when
        val keys = receiverIndexKeys(type, listOf(typeParameter(0, classType("kotlin/CharSequence"))))

        // then
        assert(keys.size == 1 && !couldBeClassKey(keys.single()))
    }

    @Test
    fun `should key a bounded type parameter receiver by its bound`() {
        // given
        // fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T
        val type = typeParameterType(0)
        val bound = classType("kotlin/Comparable", typeParameterType(0))

        // when
        val keys = receiverIndexKeys(type, listOf(typeParameter(0, bound)))

        // then
        assert(keys == listOf("kotlin.Comparable"))
    }

    @Test
    fun `should key a type parameter receiver with a nullable bound by the bound`() {
        // given
        // fun <T : CharSequence?> T.foo()
        val type = typeParameterType(0, nullable = true)
        val bound = classType("kotlin/CharSequence").apply { isNullable = true }

        // when
        val keys = receiverIndexKeys(type, listOf(typeParameter(0, bound)))

        // then
        assert(keys == listOf("kotlin.CharSequence"))
    }

    @Test
    fun `should key a type parameter receiver bounded by Any by Any`() {
        // given
        // val <T : Any> T.javaClass: Class<T>
        val type = typeParameterType(0)

        // when
        val keys = receiverIndexKeys(type, listOf(typeParameter(0, classType("kotlin/Any"))))

        // then
        assert(keys == listOf("kotlin.Any"))
    }

    @Test
    fun `should key a type parameter receiver with multiple bounds by each bound`() {
        // given
        // fun <T> T.foo() where T : CharSequence, T : Comparable<T>
        val type = typeParameterType(0)
        val typeParameters = listOf(
            typeParameter(0, classType("kotlin/CharSequence"), classType("kotlin/Comparable", typeParameterType(0)))
        )

        // when
        val keys = receiverIndexKeys(type, typeParameters)

        // then
        assert(keys == listOf("kotlin.CharSequence", "kotlin.Comparable"))
    }

    @Test
    fun `should key a type parameter receiver with Any among multiple bounds by the other bounds`() {
        // given
        // fun <T> T.foo() where T : Any, T : Comparable<T>
        val type = typeParameterType(0)
        val typeParameters = listOf(
            typeParameter(0, classType("kotlin/Any"), classType("kotlin/Comparable", typeParameterType(0)))
        )

        // when
        val keys = receiverIndexKeys(type, typeParameters)

        // then
        assert(keys == listOf("kotlin.Comparable"))
    }

    @Test
    fun `should key a type parameter receiver bounded by a type parameter by the bounds of that type parameter`() {
        // given
        // fun <U : CharSequence, T : U> T.foo()
        val type = typeParameterType(1)
        val typeParameters = listOf(
            typeParameter(0, classType("kotlin/CharSequence")),
            typeParameter(1, typeParameterType(0))
        )

        // when
        val keys = receiverIndexKeys(type, typeParameters)

        // then
        assert(keys == listOf("kotlin.CharSequence"))
    }

    @Test
    fun `should skip an unbounded type parameter among the bounds of a type parameter receiver`() {
        // given
        // inline fun <C, R> C.ifEmpty(defaultValue: () -> R): R where C : CharSequence, C : R
        val type = typeParameterType(0)
        val typeParameters = listOf(
            typeParameter(0, classType("kotlin/CharSequence"), typeParameterType(1)),
            typeParameter(1)
        )

        // when
        val keys = receiverIndexKeys(type, typeParameters)

        // then
        assert(keys == listOf("kotlin.CharSequence"))
    }

    @Test
    fun `should key a type parameter receiver with cyclic type parameter bounds apart from any class name`() {
        // given
        // not valid Kotlin, but the metadata could still say so
        val type = typeParameterType(0)
        val typeParameters = listOf(
            typeParameter(0, typeParameterType(1)),
            typeParameter(1, typeParameterType(0))
        )

        // when
        val keys = receiverIndexKeys(type, typeParameters)

        // then
        assert(keys.size == 1 && !couldBeClassKey(keys.single()))
    }

    @Test
    fun `should not key a suspend FunctionN without Continuation as a suspend function type`() {
        // given
        val type = classType(
            "kotlin/Function2",
            classType("kotlin/String"),
            classType("kotlin/collections/List", classType("kotlin/Int")),
            classType("kotlin/Any")
        ).apply { isSuspend = true }

        // when
        val key = receiverIndexKeys(type).single()

        // then
        assert(key == "kotlin.Function2")
    }

    @Test
    fun `should key a suspend function type receiver apart from the FunctionN of the same runtime arity`() {
        // given
        // suspend () -> Unit is stored in the metadata as Function1<Continuation<Unit>, Any?>
        val type = classType(
            "kotlin/Function1",
            classType("kotlin/coroutines/Continuation", classType("kotlin/Unit")),
            classType("kotlin/Any")
        ).apply { isSuspend = true }

        // when
        val key = receiverIndexKeys(type).single()

        // then
        assert(key == "kotlin.coroutines.SuspendFunction0")
    }

    @Test
    fun `should key a suspend function type with receiver by its arity without the continuation`() {
        // given
        // suspend String.() -> Int is stored in the metadata as Function2<String, Continuation<Int>, Any?>
        val type = classType(
            "kotlin/Function2",
            classType("kotlin/String"),
            classType("kotlin/coroutines/Continuation", classType("kotlin/Int")),
            classType("kotlin/Any")
        ).apply { isSuspend = true }

        // when
        val key = receiverIndexKeys(type).single()

        // then
        assert(key == "kotlin.coroutines.SuspendFunction1")
    }

    /**
     * Tells if the [key] could be the one of a class, its qualified name,
     * where no segment can contain any of `. ; [ /`, see JVMS §4.2.2.
     */
    private fun couldBeClassKey(key: String): Boolean =
        key.split('.').all { segment -> segment.isNotEmpty() && segment.none { it in ";[/" } }

}
