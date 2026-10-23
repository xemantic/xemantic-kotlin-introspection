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

import com.xemantic.kotlin.introspection.test.FunctionTypesSample
import com.xemantic.kotlin.introspection.test.LargeArityFunctionTypesSample
import com.xemantic.kotlin.introspection.test.NamedFunctionTypeParametersSample
import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.metadata.KmAnnotation
import kotlin.metadata.KmAnnotationArgument
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmVariance
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.jvm.annotations
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.test.Test

/**
 * Decoding of function types from their runtime representation in the Kotlin metadata,
 * on hand-made types, as some of the shapes cannot be produced by the compiler.
 */
class FunctionTypeTest {

    private val introspector = DefaultKotlinIntrospector(scanEntireClasspath = false)

    private val ctx = classType("test/Ctx")
    private val foo = classType("test/Foo")
    private val int = classType("kotlin/Int")
    private val unit = classType("kotlin/Unit")
    private val string = classType("kotlin/String")
    private val nullableAny = classType("kotlin/Any", isNullable = true)

    private val extensionFunctionType = KmAnnotation("kotlin/ExtensionFunctionType", emptyMap())

    private fun contextFunctionTypeParams(count: Int) = KmAnnotation(
        "kotlin/ContextFunctionTypeParams",
        mapOf("count" to KmAnnotationArgument.IntValue(count))
    )

    private fun continuation(returnType: KmType) = classType("kotlin/coroutines/Continuation", returnType)

    @Test
    fun `should decode suspend function type with context parameter and receiver`() {
        // given - suspend context(Ctx) Foo.(Int) -> Unit
        val type = classType("kotlin/Function4", ctx, foo, int, continuation(unit), nullableAny).apply {
            isSuspend = true
            annotations += contextFunctionTypeParams(1)
            annotations += extensionFunctionType
        }

        // when
        val functionType = type.decodeFunctionType()

        // then
        functionType should {
            have(isSuspend)
            have(contextParameters == listOf(ctx))
            have(receiver === foo)
            have(valueParameters == listOf(int))
            have(returnType === unit)
        }
    }

    @Test
    fun `should decode suspend function type with context parameters only`() {
        // given - suspend context(Ctx, Foo) () -> String
        val type = classType("kotlin/Function3", ctx, foo, continuation(string), nullableAny).apply {
            isSuspend = true
            annotations += contextFunctionTypeParams(2)
        }

        // when
        val functionType = type.decodeFunctionType()

        // then
        functionType should {
            have(contextParameters == listOf(ctx, foo))
            have(receiver == null)
            have(valueParameters.isEmpty())
            have(returnType === string)
        }
    }

    @Test
    fun `should decode function type with context parameter and receiver`() {
        // given - context(Ctx) Foo.(Int) -> String
        val type = classType("kotlin/Function3", ctx, foo, int, string).apply {
            annotations += contextFunctionTypeParams(1)
            annotations += extensionFunctionType
        }

        // when
        val functionType = type.decodeFunctionType()

        // then
        functionType should {
            have(!isSuspend)
            have(contextParameters == listOf(ctx))
            have(receiver === foo)
            have(valueParameters == listOf(int))
            have(returnType === string)
        }
    }

    @Test
    fun `should not decode suspend function type without Continuation`() {
        // given
        val type = classType("kotlin/Function2", int, classType("kotlin/collections/List", unit), nullableAny).apply {
            isSuspend = true
        }

        // when
        val functionType = type.decodeFunctionType()

        // then
        assert(functionType == null)
    }

    @Test
    fun `should not decode star-projected function type`() {
        // given
        val type = classType("kotlin/Function2", null, continuation(unit), nullableAny).apply {
            isSuspend = true
        }

        // when
        val functionType = type.decodeFunctionType()

        // then
        assert(functionType == null)
    }

    @Test
    fun `should not decode more context parameters than parameters`() {
        // given
        val type = classType("kotlin/Function2", ctx, continuation(unit), nullableAny).apply {
            isSuspend = true
            annotations += contextFunctionTypeParams(2)
        }

        // when
        val functionType = type.decodeFunctionType()

        // then
        assert(functionType == null)
    }

    @Test
    fun `should not decode function type with type arguments not matching its arity`() {
        // given
        val type = classType("kotlin/Function2", int, string)

        // when
        val functionType = type.decodeFunctionType()

        // then
        assert(functionType == null)
    }

    @Test
    fun `should not decode non-function type`() {
        // given
        val type = classType("kotlin/collections/List", int)

        // when
        val functionType = type.decodeFunctionType()

        // then
        assert(functionType == null)
    }

    @Test
    fun `should keep suspend marker of undecodable suspend function type`() {
        // given
        val type = classType("kotlin/Function2", null, continuation(unit), nullableAny).apply {
            isSuspend = true
        }

        // when
        val formatted = introspector.formatType(type)

        // then
        assert(formatted == "suspend Function2<*, Continuation<Unit>, Any?>")
    }

    @Test
    fun `should keep suspend marker of undecodable nullable suspend function type`() {
        // given
        val type = classType("kotlin/Function2", null, continuation(unit), nullableAny, isNullable = true).apply {
            isSuspend = true
        }

        // when
        val formatted = introspector.formatType(type)

        // then
        assert(formatted == "(suspend Function2<*, Continuation<Unit>, Any?>)?")
    }

    @Test
    fun `should format function type with kotlin-reflect`() {
        // given
        val types = FunctionTypesSample::class.declaredMemberFunctions
            .sortedBy { it.name }
            .flatMap { function -> function.parameters.drop(1).map { it.type } + function.returnType }
            .filter { it.classifier != Unit::class }

        // when
        val formatted = types.map { formatKTypeProjection(it) }

        // then
        assert(
            formatted == listOf(
                "(Int) -> (Int) -> Int",
                "() -> Unit",
                "suspend () -> Unit",
                "suspend (() -> Unit) -> Unit",
                "context(String) () -> Unit",
                "context(String) Int.(Long) -> Boolean",
                "(() -> Unit)?",
                "(() -> Unit).() -> Unit",
                "com.xemantic.kotlin.introspection.test.FunctionModifiersTestClass.() -> Unit",
                "KFunction1<Int, String>"
            )
        )
    }

    @Test
    fun `should format raw function type with kotlin-reflect without suspend marker`() {
        // given - a Function0 without type arguments, having as many of them as its arity
        val type = rawType(Function0::class)

        // when
        val formatted = formatKTypeProjection(type)

        // then
        assert(formatted == "Function0")
    }

    @Test
    fun `should format function type above arity 22 with kotlin-reflect`() {
        // given
        val types = LargeArityFunctionTypesSample::class.declaredMemberFunctions
            .sortedBy { it.name }
            .map { it.parameters[1].type }

        // when
        val formatted = types.map { formatKTypeProjection(it) }

        // then
        assert(
            formatted == listOf(
                "(${List(23) { "Int" }.joinToString(", ")}) -> Unit",
                "suspend (${List(22) { "Int" }.joinToString(", ")}) -> Unit"
            )
        )
    }

    @Test
    fun `should format parameter names of function type with kotlin-reflect`() {
        // given
        val types = NamedFunctionTypeParametersSample::class.declaredMemberFunctions
            .sortedBy { it.name }
            .map { it.parameters[1].type }

        // when
        val formatted = types.map { formatKTypeProjection(it) }

        // then
        assert(
            formatted == listOf(
                "(index: Int, Char) -> Boolean",
                "suspend (id: Int, name: String) -> Unit",
                "String.(index: Int) -> Unit"
            )
        )
    }

}

/**
 * Creates a class type, with `null` [arguments] standing for star projections.
 */
private fun classType(
    name: String,
    vararg arguments: KmType?,
    isNullable: Boolean = false
): KmType = KmType().apply {
    classifier = KmClassifier.Class(name)
    arguments.mapTo(this.arguments) { argument ->
        if (argument == null) KmTypeProjection.STAR else KmTypeProjection(KmVariance.INVARIANT, argument)
    }
    this.isNullable = isNullable
}

/**
 * Creates a kotlin-reflect type of the [classifier] without type arguments, like a raw type.
 */
private fun rawType(classifier: KClass<*>): KType = object : KType {
    override val classifier = classifier
    override val arguments = emptyList<KTypeProjection>()
    override val isMarkedNullable = false
    override val annotations = emptyList<Annotation>()
}
