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

import kotlin.metadata.KmAnnotation
import kotlin.metadata.KmAnnotationArgument
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.jvm.annotations
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * A function type, like `suspend context(Ctx) Foo.(id: Int) -> String`,
 * decoded from its runtime representation, either a [KmType] of the Kotlin metadata,
 * or a [KType] of kotlin-reflect.
 */
internal class FunctionType<T>(
    val isSuspend: Boolean,
    val contextParameters: List<T>,
    val receiver: T?,
    val valueParameters: List<T>,
    val returnType: T
)

private val functionClassName = Regex("kotlin/Function(\\d+)")

private val functionQualifiedName = Regex("kotlin\\.Function(\\d+)")

/**
 * Decodes this function type from its runtime representation in the Kotlin metadata.
 *
 * The metadata stores a function type as `FunctionN<…, R>`, with all the parameters as the leading type arguments:
 * first the context parameters, counted by the `@ContextFunctionTypeParams` type annotation,
 * then the receiver, marked with the `@ExtensionFunctionType` type annotation,
 * and the value parameters.
 * A suspend function type, marked with [KmType.isSuspend], is stored as `FunctionN<…, Continuation<R>, Any?>`.
 * Thus `suspend context(Ctx) Foo.(Int) -> String` is `Function4<Ctx, Foo, Int, Continuation<String>, Any?>`.
 *
 * @return the decoded function type, or `null` if this type is not a function type,
 * or does not have the expected shape, like a star-projected type argument.
 */
internal fun KmType.decodeFunctionType(): FunctionType<KmType>? {
    val classifier = classifier
    if (classifier !is KmClassifier.Class || !functionClassName.matches(classifier.name)) return null
    val arguments = arguments.map { it.type ?: return null }
    val (parameters, returnType) = if (isSuspend) {
        if (arguments.size < 2) return null
        val continuation = arguments[arguments.size - 2]
        val continuationClassifier = continuation.classifier
        if (continuationClassifier !is KmClassifier.Class
            || continuationClassifier.name != "kotlin/coroutines/Continuation"
        ) return null
        arguments.dropLast(2) to (continuation.arguments.singleOrNull()?.type ?: return null)
    } else {
        if (arguments.isEmpty()) return null
        arguments.dropLast(1) to arguments.last()
    }
    // Only the JVM metadata carries the type annotations: kotlin-metadata-jvm registers just the JVM extensions,
    // so a type read through KotlinCommonMetadata has none, and its receiver and context parameters cannot be told.
    // No function type with a receiver or context parameters is declared in the .kotlin_builtins files read here.
    return decodeParameters(
        isSuspend = isSuspend,
        parameters = parameters,
        returnType = returnType,
        contextParameterCount = annotations.contextParameterCount ?: return null,
        hasReceiver = annotations.any { it.className == "kotlin/ExtensionFunctionType" }
    )
}

/**
 * Decodes this function type from its runtime representation in kotlin-reflect.
 *
 * Like in the Kotlin metadata, a function type is a `FunctionN<…, R>`,
 * with the context parameters and the receiver given by the `@ContextFunctionTypeParams`
 * and `@ExtensionFunctionType` type annotations,
 * but a suspend function type is not marked as such in the public API,
 * and kotlin-reflect replaces its trailing `Continuation<R>, Any?` type arguments with `R`,
 * thus it is told by the type arguments being one short of `N + 1`.
 *
 * @return the decoded function type, or `null` if this type is not a function type,
 * or does not have the expected shape, like a star-projected type argument.
 */
internal fun KType.decodeFunctionType(): FunctionType<KType>? {
    val isSuspend = isSuspendFunctionType ?: return null
    val arguments = arguments.map { it.type ?: return null }
    if (arguments.isEmpty()) return null
    return decodeParameters(
        isSuspend = isSuspend,
        parameters = arguments.dropLast(1),
        returnType = arguments.last(),
        contextParameterCount = annotations.contextParameterCount ?: return null,
        hasReceiver = annotations.any { it is ExtensionFunctionType }
    )
}

/**
 * Tells if this `FunctionN` type is a suspend function type, see [KType.decodeFunctionType],
 * or `null` if it is not a function type of the expected shape.
 */
internal val KType.isSuspendFunctionType: Boolean?
    get() {
        val qualifiedName = (classifier as? KClass<*>)?.qualifiedName ?: return null
        val arity = functionQualifiedName.matchEntire(qualifiedName)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return when (arguments.size) {
            arity + 1 -> false
            arity -> true
            else -> null
        }
    }

private fun <T> decodeParameters(
    isSuspend: Boolean,
    parameters: List<T>,
    returnType: T,
    contextParameterCount: Int,
    hasReceiver: Boolean
): FunctionType<T>? {
    val valueParametersStart = contextParameterCount + if (hasReceiver) 1 else 0
    if (valueParametersStart > parameters.size) return null
    return FunctionType(
        isSuspend = isSuspend,
        contextParameters = parameters.take(contextParameterCount),
        receiver = if (hasReceiver) parameters[contextParameterCount] else null,
        valueParameters = parameters.drop(valueParametersStart),
        returnType = returnType
    )
}

/**
 * The count of context parameters given by the `@ContextFunctionTypeParams` type annotation,
 * `0` without the annotation, or `null` if the annotation does not carry a valid count.
 */
private val List<KmAnnotation>.contextParameterCount: Int?
    get() {
        val annotation = find { it.className == "kotlin/ContextFunctionTypeParams" } ?: return 0
        val count = (annotation.arguments["count"] as? KmAnnotationArgument.IntValue)?.value ?: return null
        return count.takeIf { it >= 0 }
    }

@get:JvmName("reflectContextParameterCount")
private val List<Annotation>.contextParameterCount: Int?
    get() {
        val annotation = filterIsInstance<ContextFunctionTypeParams>().firstOrNull() ?: return 0
        return annotation.count.takeIf { it >= 0 }
    }

/**
 * The name of a function type parameter, like `id` in `(id: Int) -> Unit`,
 * given by the `@ParameterName` type annotation of this parameter type.
 */
internal val KmType.parameterName: String?
    get() = annotations.find { it.className == "kotlin/ParameterName" }
        ?.let { (it.arguments["name"] as? KmAnnotationArgument.StringValue)?.value }

/**
 * The name of a function type parameter, like `id` in `(id: Int) -> Unit`,
 * given by the `@ParameterName` type annotation of this parameter type.
 */
internal val KType.parameterName: String?
    get() = annotations.filterIsInstance<ParameterName>().firstOrNull()?.name

/**
 * Formats this function type, like `suspend context(Ctx) Foo.(id: Int) -> String`, without the nullability.
 *
 * @param formatType formats a type, like a value parameter type.
 * @param formatReceiverType formats a receiver type, see [KmType.needsParenthesesAsReceiver].
 * @param parameterName gives the name of a value parameter, if declared.
 */
internal fun <T> FunctionType<T>.format(
    formatType: (T) -> String,
    formatReceiverType: (T) -> String,
    parameterName: (T) -> String?
): String = buildString {
    if (isSuspend) append("suspend ")
    if (contextParameters.isNotEmpty()) {
        append(contextParameters.joinToString(", ", prefix = "context(", postfix = ") ", transform = formatType))
    }
    receiver?.let {
        append(formatReceiverType(it))
        append(".")
    }
    append(valueParameters.joinToString(", ", prefix = "(", postfix = ")") { parameter ->
        val type = formatType(parameter)
        parameterName(parameter)?.let { "${escapeIdentifier(it)}: $type" } ?: type
    })
    append(" -> ")
    append(formatType(returnType))
}

/**
 * Tells if this type, as a receiver, has to be parenthesized, like `(() -> Unit).foo()`,
 * as a function type would otherwise absorb the following `.foo`.
 * A nullable type is already parenthesized when formatted, like `(() -> Unit)?`.
 */
internal val KmType.needsParenthesesAsReceiver: Boolean
    get() = !isNullable && (isSuspend || decodeFunctionType() != null)

/**
 * Tells if this type, as a receiver, has to be parenthesized, like `(() -> Unit).foo()`,
 * as a function type would otherwise absorb the following `.foo`.
 * A nullable type is already parenthesized when formatted, like `(() -> Unit)?`.
 */
internal val KType.needsParenthesesAsReceiver: Boolean
    get() = !isMarkedNullable && (isSuspendFunctionType == true || decodeFunctionType() != null)

/**
 * Wraps this formatted type in parentheses if the [condition] holds.
 */
internal fun String.parenthesizedIf(condition: Boolean): String = if (condition) "($this)" else this
