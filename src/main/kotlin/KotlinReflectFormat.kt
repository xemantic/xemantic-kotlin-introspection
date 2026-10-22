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

import com.xemantic.kotlin.core.text.unaryPlus
import kotlin.reflect.KClass
import kotlin.reflect.KClassifier
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KVariance

/*
 * The rendering of the Kotlin built-in types, which have no Kotlin metadata of their own,
 * using kotlin-reflect.
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
 * Format a KFunction using kotlin-reflect.
 */
internal fun formatKFunction(func: KFunction<*>): String = buildString {

    if (func.isOperator) +"operator "
    if (func.isInfix) +"infix "
    if (func.isSuspend) +"suspend "

    +"fun "
    formatKTypeParameters(func.typeParameters).let { if (it.isNotEmpty()) +"$it " }
    +func.name
    +"("

    // Skip first parameter (receiver) for member functions
    val params = func.parameters.drop(1)  // Drop 'this'
    +(params.joinToString(", ") { formatKParameter(it) })

    +")"

    val returnType = formatKTypeProjection(func.returnType)
    if (returnType != "Unit") {
        +": "
        +returnType
    }
}

/**
 * The element types of the arrays of primitive and unsigned values, like `IntArray`, typing a vararg parameter.
 */
@OptIn(ExperimentalUnsignedTypes::class)
private val primitiveArrayElementTypes: Map<KClassifier, String> = mapOf(
    BooleanArray::class to "Boolean",
    CharArray::class to "Char",
    ByteArray::class to "Byte",
    ShortArray::class to "Short",
    IntArray::class to "Int",
    LongArray::class to "Long",
    FloatArray::class to "Float",
    DoubleArray::class to "Double",
    UByteArray::class to "UByte",
    UShortArray::class to "UShort",
    UIntArray::class to "UInt",
    ULongArray::class to "ULong",
)

/**
 * Format a value parameter using kotlin-reflect, like `vararg elements: T` or `limit: Int = …`.
 */
internal fun formatKParameter(parameter: KParameter): String = buildString {
    if (parameter.isVararg) +"vararg "
    +(parameter.name ?: "arg${parameter.index}")
    +": "
    // a vararg parameter is typed as an array, like Array<out T> or IntArray, while its element type is rendered
    val type = if (parameter.isVararg) {
        parameter.type.arguments.singleOrNull()?.type?.let { formatKTypeProjection(it) }
            ?: primitiveArrayElementTypes[parameter.type.classifier]
            ?: error("Unexpected type of vararg parameter: ${parameter.type}")
    } else {
        formatKTypeProjection(parameter.type)
    }
    +type
    if (parameter.isOptional) +" = ${DefaultKotlinIntrospector.DEFAULT_VALUE_MARKER}"
}

/**
 * Format a KProperty using kotlin-reflect.
 */
internal fun formatKProperty(prop: KProperty1<*, *>): String = buildString {
    +"val "
    +prop.name
    +": "
    +formatKTypeProjection(prop.returnType)
}

/**
 * Format type parameter declarations, like `<out E>`, using kotlin-reflect.
 */
internal fun formatKTypeParameters(typeParameters: List<KTypeParameter>): String {
    if (typeParameters.isEmpty()) return ""
    return typeParameters.joinToString(", ", prefix = "<", postfix = ">") { typeParameter ->
        buildString {
            if (typeParameter.isReified) +"reified "
            when (typeParameter.variance) {
                KVariance.IN -> +"in "
                KVariance.OUT -> +"out "
                KVariance.INVARIANT -> {}
            }
            +typeParameter.name
            typeParameter.upperBounds.singleOrNull()
                ?.takeUnless { it.classifier == Any::class && it.isMarkedNullable }
                ?.let {
                    +" : "
                    +formatKTypeProjection(it)
                }
        }
    }
}

/**
 * Format a KType for display, following the same rules as [DefaultKotlinIntrospector.formatType].
 * Uses simple names for kotlin.* types and fully qualified names for java.* types.
 */
internal fun formatKTypeProjection(type: KType): String {
    val functionType = type.decodeFunctionType()
    val formatted = when {
        functionType != null -> functionType.format(
            formatType = ::formatKTypeProjection,
            formatReceiverType = { formatKTypeProjection(it).parenthesizedIf(it.needsParenthesesAsReceiver) },
            parameterName = { it.parameterName }
        )
        // an unexpected shape, the runtime type is printed, still marked as suspend
        type.isSuspendFunctionType == true -> "suspend " + formatKClassType(type).removeSuffix("?")
        else -> return formatKClassType(type)
    }
    return if (type.isMarkedNullable) "($formatted)?" else formatted
}

/**
 * Formats a [type] as written, like `List<String>`, without the special syntax of function types, using kotlin-reflect.
 */
private fun formatKClassType(type: KType): String {
    val baseName = when (val classifier = type.classifier) {
        is KClass<*> -> {
            val qualifiedName = classifier.qualifiedName ?: classifier.simpleName ?: "Unknown"
            // Use simple name for kotlin.* types, fully qualified for others
            if (qualifiedName.startsWith("kotlin.")) {
                classifier.simpleName ?: "Unknown"
            } else {
                qualifiedName
            }
        }
        // the name only, without the platform type marker
        is KTypeParameter -> classifier.name
        else -> type.toString()
    }

    val args = type.arguments.map { projection ->
        val projType = projection.type ?: return@map "*"
        when (projection.variance) {
            KVariance.INVARIANT, null -> formatKTypeProjection(projType)
            KVariance.IN -> "in ${formatKTypeProjection(projType)}"
            KVariance.OUT -> "out ${formatKTypeProjection(projType)}"
        }
    }

    val result = if (args.isEmpty()) {
        baseName
    } else {
        "$baseName<${args.joinToString(", ")}>"
    }

    return if (type.isMarkedNullable) "$result?" else result
}
