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

import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmPropertyAccessorAttributes
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmValueParameter
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.hasConstant
import kotlin.metadata.hasNonStableParameterNames
import kotlin.metadata.isConst
import kotlin.metadata.isCrossinline
import kotlin.metadata.isDefinitelyNonNull
import kotlin.metadata.isDelegated
import kotlin.metadata.isExpect
import kotlin.metadata.isExternal
import kotlin.metadata.isInfix
import kotlin.metadata.isInline
import kotlin.metadata.isLateinit
import kotlin.metadata.isNoinline
import kotlin.metadata.isNotDefault
import kotlin.metadata.isNullable
import kotlin.metadata.isOperator
import kotlin.metadata.isReified
import kotlin.metadata.isSuspend
import kotlin.metadata.isTailrec
import kotlin.metadata.isVar
import kotlin.metadata.jvm.annotations
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.modality
import kotlin.metadata.visibility

/*
 * The declarations of the members of a supertype with the type arguments given by an inheriting class substituted
 * for the type parameters of the supertype, like `fun take(): String` of `fun take(): T` of `Container<T>`,
 * inherited as `Container<String>`, so that they read as members of the inheriting class.
 * They are copies, as the declarations of the metadata are shared, and they are only rendered,
 * while the declarations themselves tell their overrides, and their deprecations.
 */

/**
 * This type with the type arguments of the [substitution], by the ids of the type parameters they are given for,
 * substituted for these type parameters, or this very type, when it refers to none of them.
 * A type argument is nullable also when the type parameter is used as nullable, like `T?`,
 * and not nullable when the type parameter is used as definitely not nullable, like `T & Any`.
 */
internal fun KmType.substituted(substitution: Map<Int, KmType>): KmType {
    if (substitution.isEmpty()) return this
    val classifier = classifier
    if (classifier is KmClassifier.TypeParameter) {
        val argument = substitution[classifier.id] ?: return this
        return argument.copy().also { type ->
            type.isNullable = !isDefinitelyNonNull && (isNullable || argument.isNullable)
            // only a type parameter can be definitely not nullable
            type.isDefinitelyNonNull = argument.classifier is KmClassifier.TypeParameter &&
                (isDefinitelyNonNull || argument.isDefinitelyNonNull)
        }
    }
    val arguments = arguments.map { projection ->
        val type = projection.type ?: return@map projection
        val substituted = type.substituted(substitution)
        if (substituted === type) projection else KmTypeProjection(projection.variance, substituted)
    }
    val outerType = outerType?.let { it.substituted(substitution) }
    if (outerType === this.outerType && arguments.indices.all { arguments[it] === this.arguments[it] }) return this
    // the abbreviation of a typealias, and the upper bound of a flexible type, neither of them rendered,
    // would tell the type arguments not substituted
    return copy(arguments, outerType).also {
        it.abbreviatedType = null
        it.flexibleTypeUpperBound = null
    }
}

/**
 * Whether this type refers, if at all, only to the type parameters whose ids the [isKnown] tells.
 */
internal fun KmType.refersOnlyTo(isKnown: (id: Int) -> Boolean): Boolean = when (val classifier = classifier) {
    is KmClassifier.TypeParameter -> isKnown(classifier.id)
    else -> arguments.all { it.type?.refersOnlyTo(isKnown) != false } && outerType?.refersOnlyTo(isKnown) != false
}

/**
 * The type of the class of the metadata [name], like `kotlin/Any`, without type arguments.
 */
internal fun classType(name: String, nullable: Boolean = false): KmType = KmType().also {
    it.classifier = KmClassifier.Class(name)
    it.isNullable = nullable
}

/**
 * The type of the type parameter of the [id].
 */
internal fun typeParameterType(id: Int): KmType = KmType().also { it.classifier = KmClassifier.TypeParameter(id) }

/**
 * This type, but not nullable, as a copy, as the types of the metadata are shared.
 */
internal fun KmType.notNullable(): KmType = copy().also { it.isNullable = false }

/**
 * The ids of the type parameters this type refers to.
 */
private fun KmType.typeParameterIds(): Sequence<Int> = when (val classifier = classifier) {
    is KmClassifier.TypeParameter -> sequenceOf(classifier.id)
    else -> arguments.asSequence().mapNotNull { it.type }.flatMap { it.typeParameterIds() } +
        (outerType?.typeParameterIds() ?: emptySequence())
}

/**
 * A copy of this type, of the given [arguments], and [outerType], as the types of the metadata are shared.
 */
internal fun KmType.copy(
    arguments: List<KmTypeProjection> = this.arguments,
    outerType: KmType? = this.outerType
): KmType = KmType().also { type ->
    type.classifier = classifier
    type.arguments += arguments
    type.abbreviatedType = abbreviatedType
    type.outerType = outerType
    type.flexibleTypeUpperBound = flexibleTypeUpperBound
    type.isNullable = isNullable
    type.isSuspend = isSuspend
    type.isDefinitelyNonNull = isDefinitelyNonNull
    type.annotations += annotations
}

/**
 * This function with the type arguments of the [substitution] substituted, see [KmType.substituted],
 * and its own type parameters renamed apart from the type parameters the type arguments refer to,
 * named by the [names], by their ids, see [renameApartFrom].
 */
@OptIn(ExperimentalContextParameters::class)
internal fun KmFunction.substituted(substitution: Map<Int, KmType>, names: Map<Int, String>): KmFunction {
    if (substitution.isEmpty()) return this
    return copy(type = { it.substituted(substitution) }).also { function ->
        val types = function.contextParameters.map { it.type } + listOfNotNull(function.receiverParameterType) +
            function.valueParameters.flatMap { listOfNotNull(it.type, it.varargElementType) } + function.returnType
        function.typeParameters.renameApartFrom(types, names)
    }
}

/**
 * This function with the nullable types of its value parameters not nullable,
 * like the platform types of Java, rendered as nullable, see [toKmFunction], which a Kotlin override declares not nullable,
 * telling the members of the Kotlin supertypes it overrides, see [OverrideResolver.overriddenMembers].
 */
internal fun KmFunction.withNotNullableParameters(): KmFunction = copy(
    valueParameterType = { type -> if (type.isNullable) type.notNullable() else type }
)

/**
 * This function, its types, but the ones of its value parameters, given by the [type],
 * and the ones of its value parameters given by the [valueParameterType].
 */
@OptIn(ExperimentalContextParameters::class)
private fun KmFunction.copy(
    type: (KmType) -> KmType = { it },
    valueParameterType: (KmType) -> KmType = type
): KmFunction {
    val original = this
    return KmFunction(name).also { function ->
        function.visibility = visibility
        function.modality = modality
        function.kind = kind
        function.isOperator = isOperator
        function.isInfix = isInfix
        function.isInline = isInline
        function.isTailrec = isTailrec
        function.isExternal = isExternal
        function.isSuspend = isSuspend
        function.isExpect = isExpect
        function.hasNonStableParameterNames = hasNonStableParameterNames
        typeParameters.mapTo(function.typeParameters) { it.copy(type) }
        contextParameters.mapTo(function.contextParameters) { it.copy(type) }
        function.receiverParameterType = receiverParameterType?.let(type)
        valueParameters.mapTo(function.valueParameters) { it.copy(valueParameterType) }
        function.returnType = type(original.returnType)
        function.signature = signature
    }
}

/**
 * This property with the type arguments of the [substitution] substituted, see [KmType.substituted].
 */
@OptIn(ExperimentalContextParameters::class)
internal fun KmProperty.substituted(substitution: Map<Int, KmType>, names: Map<Int, String>): KmProperty {
    if (substitution.isEmpty()) return this
    val original = this
    return KmProperty(name).also { property ->
        property.visibility = visibility
        property.modality = modality
        property.kind = kind
        property.isVar = isVar
        property.isConst = isConst
        property.isLateinit = isLateinit
        property.hasConstant = hasConstant
        property.isExternal = isExternal
        property.isDelegated = isDelegated
        property.isExpect = isExpect
        getter.copyTo(property.getter)
        property.setter = setter?.let { setter -> KmPropertyAccessorAttributes().also { setter.copyTo(it) } }
        property.setterParameter = setterParameter?.copy { it.substituted(substitution) }
        typeParameters.mapTo(property.typeParameters) { typeParameter -> typeParameter.copy { it.substituted(substitution) } }
        contextParameters.mapTo(property.contextParameters) { parameter -> parameter.copy { it.substituted(substitution) } }
        property.receiverParameterType = receiverParameterType?.substituted(substitution)
        property.returnType = original.returnType.substituted(substitution)
        property.getterSignature = getterSignature
        property.setterSignature = setterSignature
        val types = property.contextParameters.map { it.type } + listOfNotNull(property.receiverParameterType) +
            property.returnType
        property.typeParameters.renameApartFrom(types, names)
    }
}

/**
 * Renames these own type parameters of a member, which the [types] of the member refer to,
 * apart from the type parameters of the [names], by their ids, which the [types] refer to as well,
 * like the type parameters of a class inheriting the member, given as type arguments for the ones of its supertype,
 * which would otherwise capture them, like `fun <U1> thenApply(fn: Function<in U, out U1>)`
 * of `fun <U> thenApply(fn: Function<in T, out U>)` of `CompletableFuture<T>`, inherited as `CompletableFuture<U>`.
 * The types refer to the type parameters by their ids, so that only the declarations are renamed.
 */
private fun MutableList<KmTypeParameter>.renameApartFrom(types: List<KmType>, names: Map<Int, String>) {
    val referenced = (types.asSequence() + flatMap { it.upperBounds })
        .flatMap { it.typeParameterIds() }
        .mapNotNullTo(HashSet()) { names[it] }
    if (none { it.name in referenced }) return
    val taken = HashSet(names.values).also { taken -> mapTo(taken) { it.name } }
    replaceAll { typeParameter ->
        if (typeParameter.name !in referenced) return@replaceAll typeParameter
        val name = generateSequence(1) { it + 1 }.map { "${typeParameter.name}$it" }.first { it !in taken }
        taken += name
        KmTypeParameter(name, typeParameter.id, typeParameter.variance).also { renamed ->
            renamed.isReified = typeParameter.isReified
            renamed.upperBounds += typeParameter.upperBounds
        }
    }
}

private fun KmPropertyAccessorAttributes.copyTo(accessor: KmPropertyAccessorAttributes) {
    accessor.visibility = visibility
    accessor.modality = modality
    accessor.isNotDefault = isNotDefault
    accessor.isExternal = isExternal
    accessor.isInline = isInline
}

private fun KmTypeParameter.copy(type: (KmType) -> KmType): KmTypeParameter =
    KmTypeParameter(name, id, variance).also { typeParameter ->
        typeParameter.isReified = isReified
        upperBounds.mapTo(typeParameter.upperBounds, type)
    }

private fun KmValueParameter.copy(type: (KmType) -> KmType): KmValueParameter =
    KmValueParameter(name).also { parameter ->
        parameter.type = type(this.type)
        parameter.varargElementType = varargElementType?.let(type)
        parameter.declaresDefaultValue = declaresDefaultValue
        parameter.isCrossinline = isCrossinline
        parameter.isNoinline = isNoinline
    }
