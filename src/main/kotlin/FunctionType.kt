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
import kotlin.metadata.isSuspend
import kotlin.metadata.jvm.annotations

/**
 * A function type, like `suspend context(Ctx) Foo.(Int) -> String`,
 * decoded from its runtime representation in the Kotlin metadata.
 */
internal class FunctionType(
    val isSuspend: Boolean,
    val contextParameters: List<KmType>,
    val receiver: KmType?,
    val valueParameters: List<KmType>,
    val returnType: KmType
)

private val functionClassName = Regex("kotlin/Function\\d+")

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
 * @return the decoded function type, or `null` if this type does not have the expected shape,
 * like a star-projected type argument.
 */
internal fun KmType.decodeFunctionType(): FunctionType? {
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
    val contextParameterCount = annotations.contextParameterCount ?: return null
    val hasReceiver = annotations.any { it.className == "kotlin/ExtensionFunctionType" }
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
