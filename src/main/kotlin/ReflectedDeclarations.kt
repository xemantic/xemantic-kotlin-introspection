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
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.typeOf

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
 * Such a method is still rendered with these names, as Kotlin calls it all the same,
 * while such a constructor tells a JVM constructor, like `Integer(int)`, which has no counterpart in Kotlin,
 * see [toKmBuiltinClass].
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
 * Tells whether the [callable] reflected by kotlin-reflect is a JDK member of a built-in type which Kotlin does not call,
 * newer than the members mapped to the built-in type, like `String.indent(Int)`, `strip()`, or `CharSequence.getChars`,
 * which kotlin-reflect tells deprecated as "not fully supported by Kotlin compiler", mirroring the warning of K1,
 * while K2 does not resolve it at all, so that it is neither rendered, nor shadows the extensions of its signature.
 */
internal fun isUnsupportedByCompiler(callable: KCallable<*>): Boolean = try {
    callable.annotations.any { it is Deprecated && it.message.startsWith(UNSUPPORTED_MEMBER_MESSAGE) }
} catch (_: Exception) {
    false
} catch (e: Error) {
    // like the KotlinReflectionInternalError of a member kotlin-reflect cannot describe
    if (e is VirtualMachineError) throw e
    false
}

private const val UNSUPPORTED_MEMBER_MESSAGE = "This member is not fully supported by Kotlin compiler"

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
 * The declaration of this built-in type in the Kotlin metadata, named like `kotlin/String`, as kotlin-reflect describes it,
 * when its declaration in the `.kotlin_builtins` resources cannot be read, see [readKotlinBuiltinClasses].
 *
 * kotlin-reflect describes the JVM class, like `java.lang.String`, so that the declaration is approximate:
 * its supertypes are only the Kotlin ones, its members are the ones the JVM class declares,
 * also the ones whose parameter names are not known, like `initCause(arg0: Throwable?)`,
 * but the ones Kotlin does not call, see [isUnsupportedByCompiler],
 * and its constructors are the ones of the JVM class whose parameter names are known, like `String()`,
 * see [hasSyntheticParameterNames],
 * as the others, like `Integer(int)`, have no counterpart in Kotlin.
 * Its companion object is not declared.
 */
internal fun KClass<*>.toKmBuiltinClass(name: String): KmClass {
    val kmClass = KmClass()
    kmClass.name = name
    kmClass.kind = if (java.isInterface) ClassKind.INTERFACE else ClassKind.CLASS
    kmClass.visibility = Visibility.PUBLIC
    kmClass.modality = when {
        isAbstract || java.isInterface -> Modality.ABSTRACT
        isOpen -> Modality.OPEN
        else -> Modality.FINAL
    }
    this.typeParameters.mapIndexedTo(kmClass.typeParameters) { id, typeParameter ->
        KmTypeParameter(typeParameter.name, id, typeParameter.variance.toKmVariance())
    }
    val typeParameters = ReflectedTypeParameters.classTypeParameters(kmClass.typeParameters)
    this.typeParameters.zip(kmClass.typeParameters) { typeParameter, kmTypeParameter ->
        typeParameter.upperBounds.mapTo(kmTypeParameter.upperBounds) { it.toKmType(typeParameters) }
    }
    supertypes
        .filter { supertype -> (supertype.classifier as? KClass<*>)?.qualifiedName?.startsWith("kotlin.") == true }
        .mapTo(kmClass.supertypes) { it.toKmType(typeParameters) }
    declaredMemberFunctions
        .filter { '$' !in it.name && !isUnsupportedByCompiler(it) }
        .mapTo(kmClass.functions) { function ->
            function.toKmFunction(typeParameters).also { it.modality = it.modality.finalIn(kmClass) }
        }
    declaredMemberProperties
        .filter { '$' !in it.name && !isUnsupportedByCompiler(it) }
        .mapTo(kmClass.properties) { property ->
            property.toKmProperty(typeParameters).also { it.modality = it.modality.finalIn(kmClass) }
        }
    constructors
        .filter { it.visibility == KVisibility.PUBLIC && !hasSyntheticParameterNames(it) }
        .mapTo(kmClass.constructors) { constructor ->
            KmConstructor().also { kmConstructor ->
                kmConstructor.visibility = Visibility.PUBLIC
                constructor.parameters.withIndex().mapTo(kmConstructor.valueParameters) { (position, parameter) ->
                    parameter.toKmValueParameter(position, typeParameters)
                }
            }
        }
    return kmClass
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
    parameters.contextParametersTo(function.contextParameters, scope)
    function.receiverParameterType = parameters.extensionReceiverType(scope)
    parameters.filter { it.kind == KParameter.Kind.VALUE }.withIndex().mapTo(function.valueParameters) { (position, parameter) ->
        parameter.toKmValueParameter(position, scope)
    }
    function.returnType = returnType.toKmType(scope)
    return function
}

/**
 * The declaration of this property in the Kotlin metadata, like for a function, see [toKmFunction].
 * A property with context parameters, or an extension receiver, is listed by kotlin-reflect among the members,
 * but not among the member properties, thus never a property of a built-in type, see [toKmBuiltinClass].
 */
@OptIn(ExperimentalContextParameters::class)
internal fun KProperty<*>.toKmProperty(typeParameters: ReflectedTypeParameters): KmProperty {
    val scope = typeParameters.withOwn(this.typeParameters)
    val property = KmProperty(name)
    property.visibility = reflectedVisibility(visibility)
    property.modality = reflectedModality(this)
    property.typeParameters += this.typeParameters.map { it.toKmTypeParameter(scope) }
    parameters.contextParametersTo(property.contextParameters, scope)
    property.receiverParameterType = parameters.extensionReceiverType(scope)
    if (this is KMutableProperty<*>) {
        property.isVar = true
        property.setter = KmPropertyAccessorAttributes().also { it.visibility = reflectedVisibility(setter.visibility) }
    }
    property.returnType = returnType.toKmType(scope)
    return property
}

/**
 * Adds the context parameters among these parameters of a callable to the [contextParameters] of its declaration,
 * an anonymous one named `_`, like in the Kotlin metadata.
 */
@OptIn(ExperimentalContextParameters::class)
private fun List<KParameter>.contextParametersTo(contextParameters: MutableList<KmValueParameter>, scope: ReflectedTypeParameters) {
    filter { it.kind == KParameter.Kind.CONTEXT }.mapTo(contextParameters) {
        KmValueParameter(it.name ?: "_").apply { type = it.type.toKmType(scope) }
    }
}

/**
 * The type of the extension receiver among these parameters of a callable, or `null` if it is not an extension.
 */
private fun List<KParameter>.extensionReceiverType(scope: ReflectedTypeParameters): KmType? =
    firstOrNull { it.kind == KParameter.Kind.EXTENSION_RECEIVER }?.type?.toKmType(scope)

/**
 * This modality of a member described by kotlin-reflect, declared in the [kmClass], where an open member of a final class
 * is final, like a method of a final Java class, which Java does not have to declare final,
 * like `describeConstable()` of `java.lang.String`.
 */
internal fun Modality.finalIn(kmClass: KmClass): Modality =
    if (kmClass.modality == Modality.FINAL && this == Modality.OPEN) Modality.FINAL else this

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
        // like the implicit Any! bound of a Java type parameter, which admits nullable type arguments
        upperBounds.mapTo(typeParameter.upperBounds) { it.toKmType(scope, platformAsNullable = true) }
    }

/**
 * The element types of the arrays of primitive and unsigned values, like `IntArray`, typing a vararg parameter.
 */
private val primitiveArrayElementTypes: Map<String, String> = listOf(
    "Boolean", "Char", "Byte", "Short", "Int", "Long", "Float", "Double", "UByte", "UShort", "UInt", "ULong"
).associate { "kotlin/${it}Array" to "kotlin/$it" }

/**
 * The element type of a vararg parameter typed as the [arrayType], like `T` of `Array<out T>`, or `Int` of `IntArray`,
 * or else the [arrayType] itself, not to fail the whole class, should a future standard library declare another array type.
 */
internal fun varargElementType(arrayType: KmType): KmType =
    arrayType.arguments.singleOrNull()?.type
        ?: primitiveArrayElementTypes[(arrayType.classifier as? KmClassifier.Class)?.name]?.let { classType(it) }
        ?: arrayType

private fun KParameter.toKmValueParameter(position: Int, scope: ReflectedTypeParameters): KmValueParameter =
    KmValueParameter(name ?: "arg$position").also { parameter ->
        // a Java parameter of a platform type, like Any! of Object, accepts null,
        // but a vararg one, whose array is never null, and which tells the nullability of its elements below
        val type = type.toKmType(scope, platformAsNullable = !isVararg)
        parameter.type = type
        // a vararg parameter is typed as an array, like Array<out T> or IntArray, of its element type
        if (isVararg) {
            val platformElementType = this.type.arguments.singleOrNull()?.type?.takeIf { it.isPlatformNullable }
            parameter.varargElementType = if (platformElementType != null) {
                // the element type of a Java vararg, like Any! of Object..., accepts null elements,
                // converted anew, not to change the element type of the array type, telling the signature
                platformElementType.toKmType(scope).also { it.isNullable = true }
            } else {
                varargElementType(type)
            }
        }
        parameter.declaresDefaultValue = isOptional
    }

/**
 * Whether this type is a platform type of Java, like `Any!`, accepting `null`, though not marked nullable,
 * which kotlin-reflect tells only by `Nothing?` being its subtype.
 */
private val KType.isPlatformNullable: Boolean
    get() = !isMarkedNullable && nullableNothing.isSubtypeOf(this)

private val nullableNothing: KType = typeOf<Nothing?>()

private fun classType(name: String, nullable: Boolean = false): KmType = KmType().also {
    it.classifier = KmClassifier.Class(name)
    it.isNullable = nullable
}

/**
 * The type of the Kotlin metadata described by this [KType], where the type parameters are in the [scope].
 *
 * A platform type of Java, like `Any!`, see [isPlatformNullable], which cannot be rendered as valid Kotlin,
 * is nullable when [platformAsNullable], like the type of a value parameter, or the bound of a type parameter,
 * where it accepts `null`, and else not nullable, like a return type, which Kotlin lets be used as such,
 * or a type argument, not to flood the signatures with the nullable types, like `Array<StackTraceElement?>`.
 *
 * A function type is stored like in the Kotlin metadata, see [KmType.decodeFunctionType]:
 * a suspend function type, which kotlin-reflect describes with `R` in place of its trailing `Continuation<R>, Any?`,
 * see [KType.isSuspendFunctionType], gets them back,
 * and the type annotations telling its receiver, its context parameters, and the names of its parameters, are kept.
 */
internal fun KType.toKmType(scope: ReflectedTypeParameters, platformAsNullable: Boolean = false): KmType {
    val type = KmType()
    type.isNullable = isMarkedNullable || (platformAsNullable && isPlatformNullable)
    val classifier = classifier
    val isSuspendFunctionType = isSuspendFunctionType
    // a FunctionN is only guessed to be a function type, which an undecodable shape does not confirm
    val isFunctionNType = classifier == FunctionN::class && isSuspendFunctionType != null && arguments.all { it.type != null }
    type.classifier = when (classifier) {
        // kotlin-reflect gives the KFunction class for the function reference types, like KFunction1<P, R>,
        // telling their arity by the type arguments, apart from KFunction<R> itself, which takes one;
        // a KSuspendFunctionN cannot be told apart from a KFunctionN in the public API
        KFunction::class if arguments.size > 1 -> KmClassifier.Class(FunctionTypeKind.FUNCTION_REFERENCE.metadataName(arguments.size - 1))
        // a function type of more than 22 runtime parameters, stored in the metadata as a FunctionN of its arity
        FunctionN::class if isFunctionNType ->
            KmClassifier.Class(
                FunctionTypeKind.FUNCTION.metadataName(if (isSuspendFunctionType) arguments.size else arguments.size - 1)
            )
        is KClass<*> -> KmClassifier.Class(reflectedClassName(classifier))
        is KTypeParameter -> KmClassifier.TypeParameter(scope.idOf(classifier))
        else -> KmClassifier.Class("kotlin/Any")
    }
    val arguments = arguments.map { it.toKmTypeProjection(scope) }
    // kotlin-reflect lists the type arguments of the outer classes of an inner class, like `Outer<String>.Inner<Int>`,
    // after its own ones, while the metadata keeps them in the outer type
    val innerClass = (classifier as? KClass<*>)?.takeIf { it.isInner }
    val ownArgumentCount = innerClass?.typeParameters?.size ?: arguments.size
    if (innerClass != null && arguments.size > ownArgumentCount) {
        type.arguments += arguments.take(ownArgumentCount)
        type.outerType = outerType(innerClass, arguments.drop(ownArgumentCount))
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

/**
 * The Kotlin class name of the [kClass], like `java/util/Map.Entry`, told by its Java class, see [kotlinClassName],
 * as its fully qualified name does not tell where its package ends, like for a package `com.example.Api`,
 * but of a type mapped to a JVM type, like `kotlin/collections/Map.Entry` of `java.util.Map.Entry`,
 * whose fully qualified name is a built-in one, of the `kotlin.*` packages, starting with lowercase letters.
 */
private fun reflectedClassName(kClass: KClass<*>): String {
    val qualifiedName = kClass.qualifiedName
    return if (qualifiedName != null && qualifiedName != kClass.java.canonicalName) {
        kotlinClassName(qualifiedName)
    } else {
        kotlinClassName(kClass.java)
    }
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
        type.classifier = KmClassifier.Class(reflectedClassName(outerClass))
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
