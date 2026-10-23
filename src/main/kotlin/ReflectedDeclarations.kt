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

import kotlin.jvm.functions.FunctionN
import kotlin.metadata.*
import kotlin.metadata.jvm.annotations
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVariance
import kotlin.reflect.KVisibility

/*
 * The declarations of the members of the built-in types described by kotlin-reflect, like the JVM-only ones,
 * which the `.kotlin_builtins` metadata does not declare, like `String.chars()`,
 * converted to the Kotlin metadata, so that they are rendered, and told to be overrides, like any other member.
 */

/**
 * Tells whether any value parameter of the [function] has no real name.
 *
 * The JVM classes of the built-in types expose, through kotlin-reflect, also their Java-only methods,
 * and the JDK is compiled without parameter names,
 * so kotlin-reflect names the parameters of these methods after their position, like `arg0`, `arg1`.
 * Such a method would be rendered without telling what its parameters are, therefore it is left out.
 * Only the exact positional name is matched, as real Kotlin names like `args` or `argument` are common.
 *
 * The parameter names are the only signal available here:
 * the Kotlin members of the built-in types are also backed by the same JDK methods,
 * so the presence of the parameter names in the Java reflection cannot tell them apart,
 * while their Kotlin names come from the built-in declarations.
 */
internal fun hasSyntheticParameterNames(function: KFunction<*>): Boolean =
    function.parameters.filter { it.kind == KParameter.Kind.VALUE }.withIndex().any { (position, parameter) ->
        parameter.name.let { it == null || it == "arg$position" }
    }

/**
 * The ids of the type parameters a reflected declaration can refer to, by their names:
 * the ones of its declaring class, declared in the Kotlin metadata, see [classTypeParameters],
 * and its own ones, numbered after them, see [withOwn].
 */
internal class ReflectedTypeParameters(private val ids: Map<String, Int>) {

    /**
     * The id of a type parameter, or `-1` if it is not in scope, rendered then with a placeholder name.
     */
    fun idOf(typeParameter: KTypeParameter): Int = ids[typeParameter.name] ?: -1

    /**
     * The type parameters in scope of a declaration declaring the [own] type parameters,
     * shadowing the ones of the class of the same name.
     */
    fun withOwn(own: List<KTypeParameter>): ReflectedTypeParameters {
        val firstId = (ids.values.maxOrNull() ?: -1) + 1
        return ReflectedTypeParameters(ids + own.mapIndexed { index, typeParameter -> typeParameter.name to firstId + index })
    }

    companion object {

        /**
         * The type parameters of a class, by their names, with the ids of the Kotlin metadata, see [KmTypeParameter.id].
         */
        fun classTypeParameters(typeParameters: List<KmTypeParameter>): ReflectedTypeParameters =
            ReflectedTypeParameters(typeParameters.associate { it.name to it.id })

    }

}

/**
 * The declaration of this function in the Kotlin metadata, as kotlin-reflect describes it,
 * where the type parameters of its class are the [typeParameters], see [ReflectedTypeParameters].
 */
@OptIn(ExperimentalContextParameters::class)
internal fun KFunction<*>.toKmFunction(typeParameters: ReflectedTypeParameters): KmFunction {
    val scope = typeParameters.withOwn(this.typeParameters)
    val function = KmFunction(name)
    function.visibility = reflectedVisibility(visibility)
    function.modality = reflectedModality(this)
    function.isOperator = isOperator
    function.isInfix = isInfix
    function.isInline = isInline
    function.isExternal = isExternal
    function.isSuspend = isSuspend
    function.typeParameters += this.typeParameters.map { it.toKmTypeParameter(scope) }
    parameters.filter { it.kind == KParameter.Kind.CONTEXT }.mapTo(function.contextParameters) {
        KmValueParameter(it.name ?: "_").apply { type = it.type.toKmType(scope) }
    }
    function.receiverParameterType = parameters.firstOrNull { it.kind == KParameter.Kind.EXTENSION_RECEIVER }
        ?.type?.toKmType(scope)
    parameters.filter { it.kind == KParameter.Kind.VALUE }.withIndex().mapTo(function.valueParameters) { (position, parameter) ->
        parameter.toKmValueParameter(position, scope)
    }
    function.returnType = returnType.toKmType(scope)
    return function
}

/**
 * The declaration of this property in the Kotlin metadata, like for a function, see [toKmFunction].
 */
internal fun KProperty<*>.toKmProperty(typeParameters: ReflectedTypeParameters): KmProperty {
    val scope = typeParameters.withOwn(this.typeParameters)
    val property = KmProperty(name)
    property.visibility = reflectedVisibility(visibility)
    property.modality = reflectedModality(this)
    property.typeParameters += this.typeParameters.map { it.toKmTypeParameter(scope) }
    if (this is KMutableProperty<*>) {
        property.isVar = true
        property.setter = KmPropertyAccessorAttributes().also { it.visibility = reflectedVisibility(setter.visibility) }
    }
    property.returnType = returnType.toKmType(scope)
    return property
}

private fun reflectedVisibility(visibility: KVisibility?): Visibility = when (visibility) {
    KVisibility.PUBLIC -> Visibility.PUBLIC
    KVisibility.PROTECTED -> Visibility.PROTECTED
    KVisibility.INTERNAL -> Visibility.INTERNAL
    KVisibility.PRIVATE -> Visibility.PRIVATE
    null -> Visibility.LOCAL
}

private fun reflectedModality(callable: KCallable<*>): Modality = when {
    callable.isAbstract -> Modality.ABSTRACT
    callable.isOpen -> Modality.OPEN
    else -> Modality.FINAL
}

private fun KTypeParameter.toKmTypeParameter(scope: ReflectedTypeParameters): KmTypeParameter =
    KmTypeParameter(name, scope.idOf(this), variance.toKmVariance()).also { typeParameter ->
        typeParameter.isReified = isReified
        upperBounds.mapTo(typeParameter.upperBounds) { it.toKmType(scope) }
    }

/**
 * The element types of the arrays of primitive and unsigned values, like `IntArray`, typing a vararg parameter.
 */
private val primitiveArrayElementTypes: Map<String, String> = listOf(
    "Boolean", "Char", "Byte", "Short", "Int", "Long", "Float", "Double", "UByte", "UShort", "UInt", "ULong"
).associate { "kotlin/${it}Array" to "kotlin/$it" }

private fun KParameter.toKmValueParameter(position: Int, scope: ReflectedTypeParameters): KmValueParameter =
    KmValueParameter(name ?: "arg$position").also { parameter ->
        val type = type.toKmType(scope)
        parameter.type = type
        // a vararg parameter is typed as an array, like Array<out T> or IntArray, of its element type
        if (isVararg) {
            parameter.varargElementType = type.arguments.singleOrNull()?.type
                ?: primitiveArrayElementTypes[(type.classifier as? KmClassifier.Class)?.name]?.let { classType(it) }
                ?: error("Unexpected type of vararg parameter: ${this.type}")
        }
        parameter.declaresDefaultValue = isOptional
    }

private fun classType(name: String, nullable: Boolean = false): KmType = KmType().also {
    it.classifier = KmClassifier.Class(name)
    it.isNullable = nullable
}

/**
 * The type of the Kotlin metadata described by this [KType], where the type parameters are in the [scope].
 *
 * A function type is stored like in the Kotlin metadata, see [KmType.decodeFunctionType]:
 * a suspend function type, which kotlin-reflect describes with `R` in place of its trailing `Continuation<R>, Any?`,
 * see [KType.isSuspendFunctionType], gets them back,
 * and the type annotations telling its receiver, its context parameters, and the names of its parameters, are kept.
 */
internal fun KType.toKmType(scope: ReflectedTypeParameters): KmType {
    val type = KmType()
    type.isNullable = isMarkedNullable
    val classifier = classifier
    val isSuspendFunctionType = isSuspendFunctionType
    // a FunctionN is only guessed to be a function type, which an undecodable shape does not confirm
    val isFunctionNType = classifier == FunctionN::class && isSuspendFunctionType != null && arguments.all { it.type != null }
    type.classifier = when (classifier) {
        // kotlin-reflect gives the KFunction class for the function reference types, like KFunction1<P, R>,
        // telling their arity by the type arguments, apart from KFunction<R> itself, which takes one;
        // a KSuspendFunctionN cannot be told apart from a KFunctionN in the public API
        KFunction::class if arguments.size > 1 -> KmClassifier.Class("kotlin/reflect/KFunction${arguments.size - 1}")
        // a function type of more than 22 runtime parameters, stored in the metadata as a FunctionN of its arity
        FunctionN::class if isFunctionNType ->
            KmClassifier.Class("kotlin/Function${if (isSuspendFunctionType) arguments.size else arguments.size - 1}")
        is KClass<*> -> KmClassifier.Class(classifier.qualifiedName?.let(::kotlinClassName) ?: kotlinClassName(classifier.java))
        is KTypeParameter -> KmClassifier.TypeParameter(scope.idOf(classifier))
        else -> KmClassifier.Class("kotlin/Any")
    }
    val arguments = arguments.map { it.toKmTypeProjection(scope) }
    // kotlin-reflect lists the type arguments of the outer classes of an inner class, like `Outer<String>.Inner<Int>`,
    // after its own ones, while the metadata keeps them in the outer type
    val ownArgumentCount = (classifier as? KClass<*>)?.takeIf { it.isInner }?.typeParameters?.size
    if (ownArgumentCount != null && arguments.size > ownArgumentCount) {
        type.arguments += arguments.take(ownArgumentCount)
        type.outerType = outerType(classifier as KClass<*>, arguments.drop(ownArgumentCount))
    } else {
        type.arguments += arguments
    }
    if (isSuspendFunctionType == true && (classifier != FunctionN::class || isFunctionNType)) {
        type.isSuspend = true
        val returnType = type.arguments.removeAt(type.arguments.lastIndex)
        type.arguments += KmTypeProjection(
            KmVariance.INVARIANT,
            classType("kotlin/coroutines/Continuation").also { it.arguments += returnType }
        )
        type.arguments += KmTypeProjection(KmVariance.INVARIANT, classType("kotlin/Any", nullable = true))
    }
    annotations.forEach { annotation ->
        when (annotation) {
            is ExtensionFunctionType -> type.annotations += KmAnnotation("kotlin/ExtensionFunctionType", emptyMap())
            is ContextFunctionTypeParams -> type.annotations += KmAnnotation(
                "kotlin/ContextFunctionTypeParams", mapOf("count" to KmAnnotationArgument.IntValue(annotation.count))
            )
            is ParameterName -> type.annotations += KmAnnotation(
                "kotlin/ParameterName", mapOf("name" to KmAnnotationArgument.StringValue(annotation.name))
            )
        }
    }
    return type
}

private fun KTypeProjection.toKmTypeProjection(scope: ReflectedTypeParameters): KmTypeProjection {
    val projectionType = type ?: return KmTypeProjection.STAR
    return KmTypeProjection(variance?.toKmVariance() ?: KmVariance.INVARIANT, projectionType.toKmType(scope))
}

/**
 * The type of the class enclosing the inner class [innerClass], with the [arguments] of its own type parameters,
 * followed by the ones of its outer classes, if it is an inner class too, or `null` if it cannot be told.
 */
private fun outerType(innerClass: KClass<*>, arguments: List<KmTypeProjection>): KmType? {
    val outerClass = innerClass.java.declaringClass?.kotlin ?: return null
    val ownArgumentCount = outerClass.typeParameters.size
    return KmType().also { type ->
        type.classifier = KmClassifier.Class(outerClass.qualifiedName?.let(::kotlinClassName) ?: kotlinClassName(outerClass.java))
        type.arguments += arguments.take(ownArgumentCount)
        if (outerClass.isInner && arguments.size > ownArgumentCount) {
            type.outerType = outerType(outerClass, arguments.drop(ownArgumentCount))
        }
    }
}

private fun KVariance.toKmVariance(): KmVariance = when (this) {
    KVariance.INVARIANT -> KmVariance.INVARIANT
    KVariance.IN -> KmVariance.IN
    KVariance.OUT -> KmVariance.OUT
}
