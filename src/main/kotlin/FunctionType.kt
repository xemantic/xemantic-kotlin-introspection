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
import kotlin.metadata.KmAnnotation
import kotlin.metadata.KmAnnotationArgument
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
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

/**
 * A function type as stored in the Kotlin metadata, a `FunctionN<…, R>`, see [KmType.decodeFunctionType],
 * with the [parameters] not yet told apart into the context parameters, the receiver and the value parameters,
 * and a suspend function type already stripped of its trailing `Continuation<R>, Any?` type arguments.
 *
 * It is all the overriding rules and the extension index need to know of a function type.
 */
internal class RuntimeFunctionType(
    val isSuspend: Boolean,
    val parameters: List<KmType>,
    val returnType: KmType
)

/**
 * Decodes this type as a function type stored in the Kotlin metadata, see [KmType.decodeFunctionType],
 * without telling apart the kinds of its parameters.
 *
 * A function reference type, like `KFunction1` or `KSuspendFunction0`, is stored under its own name,
 * not as a function type.
 *
 * @return the decoded function type, or `null` if this type is not a function type,
 * or does not have the expected shape, like a star-projected type argument.
 */
internal fun KmType.decodeRuntimeFunctionType(): RuntimeFunctionType? {
    val classifier = classifier as? KmClassifier.Class ?: return null
    val arity = functionArity(classifier.name, prefix = "kotlin/Function") ?: return null
    if (arguments.size != arity + 1) return null
    val arguments = arguments.map { it.type ?: return null }
    if (!isSuspend) return RuntimeFunctionType(isSuspend = false, arguments.dropLast(1), arguments.last())
    val continuation = arguments.getOrNull(arity - 1) ?: return null
    val continuationClassifier = continuation.classifier
    if (continuationClassifier !is KmClassifier.Class
        || continuationClassifier.name != "kotlin/coroutines/Continuation"
    ) return null
    val returnType = continuation.arguments.singleOrNull()?.type ?: return null
    return RuntimeFunctionType(isSuspend = true, arguments.take(arity - 1), returnType)
}

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
 * Only the JVM metadata carries the type annotations, as kotlin-metadata-jvm registers just the JVM extensions,
 * so a type read through `KotlinCommonMetadata` has none, and is decoded without the receiver and the context parameters:
 * `suspend context(Ctx) Foo.() -> Unit` would be decoded as `suspend (Ctx, Foo) -> Unit`.
 * No such function type is declared in the `.kotlin_builtins` files read here.
 *
 * @return the decoded function type, or `null` if this type is not a function type,
 * or does not have the expected shape, like a star-projected type argument.
 */
internal fun KmType.decodeFunctionType(): FunctionType<KmType>? {
    val runtimeType = decodeRuntimeFunctionType() ?: return null
    return decodeParameters(
        isSuspend = runtimeType.isSuspend,
        parameters = runtimeType.parameters,
        returnType = runtimeType.returnType,
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
 * thus it is told by the type arguments being one short of `N + 1`, see [KType.isSuspendFunctionType].
 *
 * @return the decoded function type, or `null` if this type is not a function type,
 * or does not have the expected shape, like a star-projected type argument.
 */
internal fun KType.decodeFunctionType(): FunctionType<KType>? {
    val isSuspend = isSuspendFunctionType ?: return null
    val arguments = arguments.map { it.type ?: return null }
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
 *
 * A function type of more than 22 runtime parameters is a `kotlin.jvm.functions.FunctionN`, not telling its arity.
 * With 23 type arguments it is told to be a suspend function type of 22 parameters,
 * as the function type of 22 parameters is a `Function22`.
 * With more of them it cannot be told apart from a suspend function type, so it is taken as not suspend.
 * With fewer of them it is not a function type.
 */
internal val KType.isSuspendFunctionType: Boolean?
    get() {
        val kClass = classifier as? KClass<*> ?: return null
        // a raw function type, without the type arguments, is not decoded
        if (arguments.isEmpty()) return null
        if (kClass == FunctionN::class) return when {
            // a FunctionN written as such, like FunctionN<R>
            arguments.size < 23 -> null
            else -> arguments.size == 23
        }
        val arity = functionArity(kClass.qualifiedName ?: return null, prefix = FUNCTION_TYPE_PREFIX) ?: return null
        return when (arguments.size) {
            arity + 1 -> false
            arity -> true
            else -> null
        }
    }

/**
 * The arity of a function type class, like `2` of `kotlin/Function2`, given its name and the expected [prefix],
 * or `null` if the [name] is not the one of a function type class.
 * The arity is written without leading zeros, like in the name of any class the compiler synthesizes,
 * or compiles a function type to, like `kotlin.jvm.functions.Function2`.
 * The prefix is checked before anything else, as most of the types are not function types.
 */
internal fun functionArity(name: String, prefix: String): Int? {
    if (!name.startsWith(prefix) || name.length == prefix.length) return null
    for (index in prefix.length until name.length) {
        if (name[index] !in '0'..'9') return null
    }
    // a leading zero only of the arity 0 itself
    if (name[prefix.length] == '0' && name.length > prefix.length + 1) return null
    return name.substring(prefix.length).toIntOrNull()
}

/**
 * The supertype of all the function types, see [FunctionTypeClass].
 */
internal const val FUNCTION_CLASS_NAME = "kotlin.Function"

/**
 * The prefix of the Kotlin name of a function type class, like `kotlin.Function1`.
 */
internal const val FUNCTION_TYPE_PREFIX = FUNCTION_CLASS_NAME

/**
 * The prefix of the metadata name of a JVM interface of a function type, like `kotlin/jvm/functions/Function1`.
 */
private const val JVM_FUNCTION_INTERFACE_PREFIX = "kotlin/jvm/functions/Function"

/**
 * The highest arity of a function type compiled to its own JVM interface, like `kotlin/jvm/functions/Function22`.
 */
internal const val MAX_JVM_FUNCTION_INTERFACE_ARITY = 22

/**
 * The metadata name of the function type class compiled to the JVM interface of the given metadata [name],
 * like `kotlin/Function1` of `kotlin/jvm/functions/Function1`, or `null` if it is not one.
 */
internal fun functionTypeOfJvmInterface(name: String): String? =
    functionArity(name, JVM_FUNCTION_INTERFACE_PREFIX)
        ?.takeIf { it <= MAX_JVM_FUNCTION_INTERFACE_ARITY }
        ?.let { "kotlin/Function$it" }

/**
 * The prefix of the Kotlin name of a suspend function type class, like `kotlin.coroutines.SuspendFunction0`,
 * also the key of the extension index of the extensions on a suspend function type, see [receiverIndexKeys].
 */
internal const val SUSPEND_FUNCTION_PREFIX = "kotlin.coroutines.SuspendFunction"

/**
 * The highest arity of a function type class resolved by its name, as an instance JVM method,
 * like the `invoke` of a function type, takes at most 254 parameters, besides `this` (JVMS §4.3.3).
 * The one of a suspend function type is one lower, as it takes the continuation as well.
 */
private const val MAX_FUNCTION_TYPE_CLASS_ARITY = 254

/**
 * The class of a function type, like `kotlin.Function1` of `(Int) -> String`,
 * or `kotlin.coroutines.SuspendFunction0` of `suspend () -> Unit`, by its Kotlin name.
 *
 * The compiler synthesizes these classes, there is neither a class file, nor any metadata, of them:
 * the ones up to `Function22` are compiled to `kotlin.jvm.functions.Function0`…`Function22`,
 * the higher ones to `kotlin.jvm.functions.FunctionN`, and a suspend function type of `N` parameters
 * to the function type of `N + 1` runtime parameters, the last one being the continuation.
 * Thus they are rendered like declared in the standard library sources of `kotlin.jvm.functions`,
 * as an interface of the type parameters `P1`…`PN`, and `R`, extending `Function<R>`,
 * with the `invoke` operator, a suspend one of a suspend function type:
 * `interface Function1<in P1, out R> : Function<R> { operator fun invoke(p1: P1): R }`.
 * A function type with a receiver, or with context parameters, is of the same class,
 * like `String.() -> Unit` and `context(String) () -> Unit` are both `Function1<String, Unit>`.
 */
internal class FunctionTypeClass private constructor(val isSuspend: Boolean, val arity: Int) {

    val simpleName: String = (if (isSuspend) "SuspendFunction" else "Function") + arity

    /**
     * The interface declaration, like `interface Function1<in P1, out R> : Function<R>`.
     */
    val header: String = buildString {
        append("interface ")
        append(simpleName)
        append(((1..arity).map { "in P$it" } + "out R").joinToString(", ", prefix = "<", postfix = ">"))
        append(" : Function<R>")
    }

    /**
     * The `invoke` operator, like `operator fun invoke(p1: P1): R`.
     */
    val invoke: String = buildString {
        if (isSuspend) append("suspend ")
        append("operator fun invoke")
        append((1..arity).joinToString(", ", prefix = "(", postfix = ")") { "p$it: P$it" })
        append(": R")
    }

    companion object {

        /**
         * The function type class of the given Kotlin [name], like `kotlin.Function1`, or `null` if it is not one,
         * see [functionArity].
         */
        fun of(name: String): FunctionTypeClass? =
            functionArity(name, SUSPEND_FUNCTION_PREFIX)
                ?.takeIf { it < MAX_FUNCTION_TYPE_CLASS_ARITY }
                ?.let { FunctionTypeClass(isSuspend = true, arity = it) }
                ?: functionArity(name, FUNCTION_TYPE_PREFIX)
                    ?.takeIf { it <= MAX_FUNCTION_TYPE_CLASS_ARITY }
                    ?.let { FunctionTypeClass(isSuspend = false, arity = it) }

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
 * The notation of the types of one runtime representation, either a [KmType] of the Kotlin metadata,
 * or a [KType] of kotlin-reflect,
 * writing a function type in its special syntax, like `suspend context(Ctx) Foo.(id: Int) -> String`,
 * and any other type as a class type, like `List<String>`.
 */
internal abstract class TypeNotation<T> {

    /**
     * Decodes the [type] as a function type, or returns `null` if it is not one, or does not have the expected shape.
     */
    protected abstract fun decodeFunctionType(type: T): FunctionType<T>?

    /**
     * Tells if the [type] is marked as a suspend function type,
     * kept by a function type of an unexpected shape, which is written as a class type.
     */
    protected abstract fun isMarkedSuspend(type: T): Boolean

    protected abstract fun isMarkedNullable(type: T): Boolean

    /**
     * The name of a function type parameter of the given [type], like `id` in `(id: Int) -> Unit`, if declared.
     */
    protected abstract fun parameterName(type: T): String?

    /**
     * Formats a [type] as written, like `List<String>`, without the special syntax of function types.
     *
     * @param nullable whether to mark the type as nullable, like `List<String>?`.
     */
    protected abstract fun formatClassType(type: T, nullable: Boolean): String

    /**
     * Formats a [type], like `List<String>` or `(Int) -> Unit`.
     */
    fun format(type: T): String = format(type, asReceiver = false)

    /**
     * Formats a receiver [type], parenthesizing a function type, like `(() -> Unit)`,
     * which would otherwise absorb the following `.name`.
     * A nullable one is parenthesized anyway, like `(() -> Unit)?`.
     */
    fun formatReceiver(type: T): String = format(type, asReceiver = true)

    private fun format(type: T, asReceiver: Boolean): String {
        val nullable = isMarkedNullable(type)
        val functionType = decodeFunctionType(type)
        val formatted = when {
            functionType != null -> formatFunctionType(functionType)
            // an unexpected shape, the runtime type is printed, still marked as suspend
            isMarkedSuspend(type) -> "suspend " + formatClassType(type, nullable = false)
            else -> return formatClassType(type, nullable)
        }
        return when {
            nullable -> "($formatted)?"
            asReceiver -> "($formatted)"
            else -> formatted
        }
    }

    private fun formatFunctionType(functionType: FunctionType<T>): String = buildString {
        if (functionType.isSuspend) append("suspend ")
        if (functionType.contextParameters.isNotEmpty()) {
            append(functionType.contextParameters.joinToString(", ", prefix = "context(", postfix = ") ", transform = ::format))
        }
        functionType.receiver?.let {
            append(formatReceiver(it))
            append(".")
        }
        append(functionType.valueParameters.joinToString(", ", prefix = "(", postfix = ")") { parameter ->
            val type = format(parameter)
            parameterName(parameter)?.let { "${escapeIdentifier(it)}: $type" } ?: type
        })
        append(" -> ")
        append(format(functionType.returnType))
    }

}
