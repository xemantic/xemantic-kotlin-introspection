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
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * A function type, like `suspend context(Ctx) Foo.(id: Int) -> String`,
 * decoded from its runtime representation, a [KmType] of the Kotlin metadata,
 * also of a type described by kotlin-reflect, see [toKmType].
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
    val arity = parseFunctionTypeName(classifier.name)?.takeIf { it.kind == FunctionTypeKind.FUNCTION }?.arity ?: return null
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
 * This type, but of a suspend function type, stored as the function type of one more runtime parameter, the continuation,
 * like `Function2<Int, Continuation<String>, Any?>` of `suspend (Int) -> String`, see [decodeRuntimeFunctionType],
 * as the type of its own function type class, like `SuspendFunction1<Int, String>`, see [FunctionTypeClass],
 * declaring the suspend `invoke` operator, which a class of such a supertype inherits.
 */
internal fun KmType.withSuspendFunctionTypeClass(): KmType {
    if (!isSuspend) return this
    val functionType = decodeRuntimeFunctionType()?.takeIf { it.isSuspend } ?: return this
    return classType(FunctionTypeKind.SUSPEND_FUNCTION.metadataName(functionType.parameters.size)).also { type ->
        (functionType.parameters + functionType.returnType).mapTo(type.arguments) { KmTypeProjection(KmVariance.INVARIANT, it) }
    }
}

/**
 * The declaration of the function type class of the metadata [name], like `kotlin/Function1`,
 * or `kotlin/coroutines/SuspendFunction1`, see [FunctionTypeClass], which no class file declares,
 * or `null` if it is not one, like a function reference type, a class of its own:
 * `interface Function1<in P1, out R> : Function<R>`, declaring `operator fun invoke(p1: P1): R`,
 * which a class of such a supertype inherits, like `abstract class Handler : (Int) -> String`.
 */
internal fun functionTypeClassDeclaration(name: String): KmClass? {
    val functionTypeClass = FunctionTypeClass.of(name.replace('/', '.'))?.takeUnless { it.kind.isReference } ?: return null
    val arity = functionTypeClass.arity
    return KmClass().also { kmClass ->
        kmClass.name = name
        kmClass.kind = ClassKind.INTERFACE
        kmClass.modality = Modality.ABSTRACT
        kmClass.visibility = Visibility.PUBLIC
        (1..arity).mapTo(kmClass.typeParameters) { KmTypeParameter("P$it", it - 1, KmVariance.IN) }
        kmClass.typeParameters += KmTypeParameter("R", arity, KmVariance.OUT)
        kmClass.supertypes += classType(FUNCTION_CLASS_NAME.replace('.', '/')).also {
            it.arguments += KmTypeProjection(KmVariance.INVARIANT, typeParameterType(arity))
        }
        kmClass.functions += KmFunction("invoke").also { invoke ->
            invoke.visibility = Visibility.PUBLIC
            invoke.modality = Modality.ABSTRACT
            invoke.isOperator = true
            invoke.isSuspend = functionTypeClass.kind.isSuspend
            (1..arity).mapTo(invoke.valueParameters) { position ->
                KmValueParameter("p$position").also { it.type = typeParameterType(position - 1) }
            }
            invoke.returnType = typeParameterType(arity)
        }
    }
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
 * Tells if this `FunctionN` type of kotlin-reflect is a suspend function type,
 * or `null` if it is not a function type of the expected shape.
 * A suspend function type is not marked as such in the public API of kotlin-reflect,
 * which replaces its trailing `Continuation<R>, Any?` type arguments with `R`,
 * thus it is told by the type arguments being one short of `N + 1`.
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
        val arity = parseFunctionTypeName(kClass.qualifiedName?.replace('.', '/') ?: return null)
            ?.takeIf { it.kind == FunctionTypeKind.FUNCTION }
            ?.arity
            ?: return null
        return when (arguments.size) {
            arity + 1 -> false
            arity -> true
            else -> null
        }
    }

/**
 * The kinds of the function type classes, which the compiler synthesizes, see [FunctionTypeClass],
 * by the prefix of their metadata names, followed by the arity, like `kotlin/Function1`, see [parseFunctionTypeName].
 *
 * @property isSuspend whether it is the type of a suspend function, like `SuspendFunction0`, or `KSuspendFunction0`.
 * @property isReference whether it is the type of a function reference, like `KFunction1`, a subtype of `KFunction`.
 */
internal enum class FunctionTypeKind(
    val metadataPrefix: String,
    val isSuspend: Boolean = false,
    val isReference: Boolean = false
) {

    /**
     * Like `kotlin/Function1` of `(P1) -> R`.
     */
    FUNCTION("kotlin/Function"),

    /**
     * Like `kotlin/coroutines/SuspendFunction0` of `suspend () -> R`, never stored in the metadata under this name,
     * but as the function type of one more parameter, the continuation, see [decodeRuntimeFunctionType].
     */
    SUSPEND_FUNCTION("kotlin/coroutines/SuspendFunction", isSuspend = true),

    /**
     * Like `kotlin/reflect/KFunction1` of a reference to a function of one parameter.
     */
    FUNCTION_REFERENCE("kotlin/reflect/KFunction", isReference = true),

    /**
     * Like `kotlin/reflect/KSuspendFunction1` of a reference to a suspend function of one parameter.
     */
    SUSPEND_FUNCTION_REFERENCE("kotlin/reflect/KSuspendFunction", isSuspend = true, isReference = true);

    /**
     * The metadata name of the function type class of this kind and the given [arity], like `kotlin/Function1`.
     */
    fun metadataName(arity: Int): String = "$metadataPrefix$arity"

    /**
     * The Kotlin name of the function type class of this kind and the given [arity], like `kotlin.Function1`.
     */
    fun kotlinName(arity: Int): String = metadataName(arity).replace('/', '.')

    /**
     * The simple name of the function type class of this kind and the given [arity], like `Function1`.
     */
    fun simpleName(arity: Int): String = metadataPrefix.substringAfterLast('/') + arity

    /**
     * The kind of the function types, which the function reference types of this kind are subtypes of,
     * like [FUNCTION] of [FUNCTION_REFERENCE], or this kind itself, if it is not the one of the function reference types.
     */
    val functionTypeKind: FunctionTypeKind
        get() = when (this) {
            FUNCTION_REFERENCE -> FUNCTION
            SUSPEND_FUNCTION_REFERENCE -> SUSPEND_FUNCTION
            else -> this
        }

}

/**
 * The name of a function type class of the [kind] and the [arity], see [parseFunctionTypeName].
 */
internal class FunctionTypeName(val kind: FunctionTypeKind, val arity: Int)

/**
 * The function type class named by the [metadataName], like `kotlin/Function1`, or `null` if it is not one,
 * written with the arity without leading zeros, like the name of any class the compiler synthesizes,
 * see [FunctionTypeKind].
 */
internal fun parseFunctionTypeName(metadataName: String): FunctionTypeName? {
    for (kind in FunctionTypeKind.entries) {
        functionArity(metadataName, kind.metadataPrefix)?.let { return FunctionTypeName(kind, it) }
    }
    return null
}

/**
 * The arity of a function type class, like `2` of `kotlin/Function2`, given its name and the expected [prefix],
 * or `null` if the [name] is not the one of a function type class.
 * The arity is written without leading zeros, like in the name of any class the compiler synthesizes,
 * or compiles a function type to, like `kotlin/jvm/functions/Function2`.
 * The prefix is checked before anything else, as most of the types are not function types.
 */
private fun functionArity(name: String, prefix: String): Int? {
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
        ?.let { FunctionTypeKind.FUNCTION.metadataName(it) }

/**
 * The highest arity of a function type class resolved by its name, as an instance JVM method,
 * like the `invoke` of a function type, takes at most 254 parameters, besides `this` (JVMS §4.3.3).
 * The one of a suspend function type is one lower, as it takes the continuation as well.
 */
private const val MAX_FUNCTION_TYPE_CLASS_ARITY = 254

/**
 * The class of a function type, like `kotlin.Function1` of `(Int) -> String`,
 * or `kotlin.coroutines.SuspendFunction0` of `suspend () -> Unit`, by its Kotlin name,
 * or of a function reference type, like `kotlin.reflect.KFunction1`, see [FunctionTypeKind].
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
 * A function reference type extends `KFunction<R>`, and the function type of the same arity,
 * like `interface KFunction1<in P1, out R> : KFunction<R>, Function1<P1, R>`, inheriting its `invoke` operator.
 */
internal class FunctionTypeClass private constructor(val kind: FunctionTypeKind, val arity: Int) {

    val simpleName: String = kind.simpleName(arity)

    /**
     * The Kotlin names of the supertypes, whose extensions are listed for this class:
     * `kotlin.Function`, and of a function reference type, `kotlin.reflect.KFunction`, with its supertypes,
     * `kotlin.reflect.KCallable` and `kotlin.reflect.KAnnotatedElement`, and its function type,
     * like `kotlin.Function1` of `kotlin.reflect.KFunction1`.
     */
    val supertypeNames: List<String> =
        if (kind.isReference) {
            listOf(
                KFUNCTION_CLASS_NAME, KCALLABLE_CLASS_NAME, KANNOTATED_ELEMENT_CLASS_NAME,
                kind.functionTypeKind.kotlinName(arity), FUNCTION_CLASS_NAME
            )
        } else {
            listOf(FUNCTION_CLASS_NAME)
        }

    /**
     * The interface declaration, like `interface Function1<in P1, out R> : Function<R>`.
     */
    val header: String = buildString {
        append("interface ")
        append(simpleName)
        append(((1..arity).map { "in P$it" } + "out R").joinToString(", ", prefix = "<", postfix = ">"))
        if (kind.isReference) {
            append(" : KFunction<R>, ")
            append(kind.functionTypeKind.simpleName(arity))
            append(((1..arity).map { "P$it" } + "R").joinToString(", ", prefix = "<", postfix = ">"))
        } else {
            append(" : Function<R>")
        }
    }

    /**
     * The `invoke` operator, like `operator fun invoke(p1: P1): R`.
     */
    val invoke: String = buildString {
        if (kind.isSuspend) append("suspend ")
        append("operator fun invoke")
        append((1..arity).joinToString(", ", prefix = "(", postfix = ")") { "p$it: P$it" })
        append(": R")
    }

    companion object {

        /**
         * The function type class of the given Kotlin [name], like `kotlin.Function1`, or `null` if it is not one,
         * see [parseFunctionTypeName].
         */
        fun of(name: String): FunctionTypeClass? {
            // a metadata name, like kotlin/Function1, is not a Kotlin name
            if ('/' in name) return null
            val functionTypeName = parseFunctionTypeName(name.replace('.', '/')) ?: return null
            val maxArity = if (functionTypeName.kind.isSuspend) MAX_FUNCTION_TYPE_CLASS_ARITY - 1 else MAX_FUNCTION_TYPE_CLASS_ARITY
            return if (functionTypeName.arity <= maxArity) FunctionTypeClass(functionTypeName.kind, functionTypeName.arity) else null
        }

    }

}

/**
 * The supertype of all the function reference types, see [FunctionTypeClass].
 */
internal const val KFUNCTION_CLASS_NAME = "kotlin.reflect.KFunction"

private const val KCALLABLE_CLASS_NAME = "kotlin.reflect.KCallable"

private const val KANNOTATED_ELEMENT_CLASS_NAME = "kotlin.reflect.KAnnotatedElement"

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

/**
 * The name of a function type parameter, like `id` in `(id: Int) -> Unit`,
 * given by the `@ParameterName` type annotation of this parameter type.
 */
internal val KmType.parameterName: String?
    get() = annotations.find { it.className == "kotlin/ParameterName" }
        ?.let { (it.arguments["name"] as? KmAnnotationArgument.StringValue)?.value }

/**
 * The notation of the types of a runtime representation, like a [KmType] of the Kotlin metadata,
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
     * Tells if the [type] is a type parameter written as definitely not nullable, like `T & Any`,
     * never marked nullable as well.
     */
    protected abstract fun isDefinitelyNonNull(type: T): Boolean

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
     * which would otherwise absorb the following `.name`, or a definitely not nullable one, like `(T & Any)`,
     * as `T & Any.name` does not parse.
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
            isDefinitelyNonNull(type) -> formatClassType(type, nullable = false) + " & Any"
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
