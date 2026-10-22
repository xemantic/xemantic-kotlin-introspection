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
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmVariance
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
        val key = receiverIndexKey(type)

        // then
        assert(key == "kotlin.collections.List")
    }

    @Test
    fun `should key a type parameter receiver apart from any class name`() {
        // given
        val type = KmType().apply { classifier = KmClassifier.TypeParameter(0) }

        // when
        val key = receiverIndexKey(type)

        // then
        // '<' cannot appear in a class name on the JVM
        assert(key != "TypeParameter" && '<' in key)
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
        val key = receiverIndexKey(type)

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
        val key = receiverIndexKey(type)

        // then
        assert(key == "kotlin.coroutines.SuspendFunction1")
    }

}
