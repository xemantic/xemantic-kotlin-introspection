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
import kotlin.reflect.typeOf
import kotlin.test.Test

/**
 * Decoding of function types from their runtime representation in the Kotlin metadata,
 * on hand-made types, as some of the shapes cannot be produced by the compiler.
 */
class FunctionTypeTest {

    private val introspector = DefaultKotlinIntrospector(scanEntireClasspath = false)

    // a type described by kotlin-reflect, converted to the Kotlin metadata, see toKmType
    private fun formatReflected(type: KType): String =
        introspector.formatType(type.toKmType(ReflectedTypeParameters.classTypeParameters(emptyList())))

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
        val formatted = types.map { formatReflected(it) }

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
        val type = kotlinReflectType(Function0::class)

        // when
        val formatted = formatReflected(type)

        // then
        assert(formatted == "Function0")
    }

    @Test
    fun `should format FunctionN with fewer type arguments than of a function type with kotlin-reflect as written`() {
        // given - a FunctionN<String> written as such, not standing for a function type of more than 22 parameters
        val type = kotlinReflectType(kotlin.jvm.functions.FunctionN::class, KTypeProjection.invariant(typeOf<String>()))

        // when
        val formatted = formatReflected(type)

        // then
        assert(formatted == "FunctionN<String>")
    }

    @Test
    fun `should format undecodable FunctionN with kotlin-reflect without suspend marker`() {
        // given - 23 type arguments, like of a suspend function type of 22 parameters, one of them star-projected
        val type = kotlinReflectType(
            kotlin.jvm.functions.FunctionN::class,
            KTypeProjection.STAR,
            *Array(22) { KTypeProjection.invariant(typeOf<Int>()) }
        )

        // when
        val formatted = formatReflected(type)

        // then
        assert(formatted == "FunctionN<*, ${List(22) { "Int" }.joinToString(", ")}>")
    }

    @Test
    fun `should format function type above arity 22 with kotlin-reflect`() {
        // given
        val types = LargeArityFunctionTypesSample::class.declaredMemberFunctions
            .sortedBy { it.name }
            .map { it.parameters[1].type }

        // when
        val formatted = types.map { formatReflected(it) }

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
        val formatted = types.map { formatReflected(it) }

        // then
        assert(
            formatted == listOf(
                "(index: Int, Char) -> Boolean",
                "suspend (id: Int, name: String) -> Unit",
                "String.(index: Int) -> Unit"
            )
        )
    }

    // function type class names, see parseFunctionTypeName

    @Test
    fun `should parse function type name of every kind by its metadata name`() {
        // when
        val function = parseFunctionTypeName("kotlin/Function2")
        val suspendFunction = parseFunctionTypeName("kotlin/coroutines/SuspendFunction0")
        val functionReference = parseFunctionTypeName("kotlin/reflect/KFunction1")
        val suspendFunctionReference = parseFunctionTypeName("kotlin/reflect/KSuspendFunction3")

        // then
        assert(function?.kind == FunctionTypeKind.FUNCTION && function.arity == 2)
        assert(suspendFunction?.kind == FunctionTypeKind.SUSPEND_FUNCTION && suspendFunction.arity == 0)
        assert(functionReference?.kind == FunctionTypeKind.FUNCTION_REFERENCE && functionReference.arity == 1)
        assert(suspendFunctionReference?.kind == FunctionTypeKind.SUSPEND_FUNCTION_REFERENCE && suspendFunctionReference.arity == 3)
    }

    @Test
    fun `should not parse function type name with leading zero in arity`() {
        // like the compiler, which never synthesizes such a class

        // when
        val functionReference = parseFunctionTypeName("kotlin/reflect/KFunction01")
        val function = parseFunctionTypeName("kotlin/Function00")

        // then
        assert(functionReference == null && function == null)
    }

    @Test
    fun `should not parse function type name mixing the packages of the kinds`() {
        // when
        val functionReference = parseFunctionTypeName("kotlin/KFunction1")
        val function = parseFunctionTypeName("kotlin/reflect/Function1")
        val suspendFunction = parseFunctionTypeName("kotlin/reflect/SuspendFunction1")

        // then
        assert(functionReference == null && function == null && suspendFunction == null)
    }

    @Test
    fun `should not parse function type name without arity`() {
        // when
        val function = parseFunctionTypeName("kotlin/Function")
        val functionReference = parseFunctionTypeName("kotlin/reflect/KFunction")

        // then
        assert(function == null && functionReference == null)
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
 * Creates a kotlin-reflect type of the [classifier] with the given type [arguments],
 * none for a raw type.
 */
private fun kotlinReflectType(classifier: KClass<*>, vararg arguments: KTypeProjection): KType = object : KType {
    override val classifier = classifier
    override val arguments = arguments.toList()
    override val isMarkedNullable = false
    override val annotations = emptyList<Annotation>()
}
