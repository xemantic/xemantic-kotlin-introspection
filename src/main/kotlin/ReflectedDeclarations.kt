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

import org.objectweb.asm.Type as AsmType
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.lang.reflect.Array as JavaArray
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
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.javaType
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
 * The Kotlin members of the built-in types are also backed by the same JDK methods,
 * so the presence of the parameter names in the Java reflection cannot tell them apart,
 * while their Kotlin names come from the built-in declarations,
 * and a parameterless Java-only method is told by its signature instead, see [javaOnlyFunctions].
 */
internal fun hasSyntheticParameterNames(function: KFunction<*>): Boolean =
    function.parameters.filter { it.kind == KParameter.Kind.VALUE }.withIndex().any { (position, parameter) ->
        parameter.name.let { it == null || it == "arg$position" }
    }

/**
 * Tells whether the [callable] reflected by kotlin-reflect is a JDK member of a built-in type which Kotlin does not call,
 * newer than the members mapped to the built-in type, like `String.indent(Int)`, `strip()`, or `CharSequence.getChars`,
 * which kotlin-reflect tells deprecated as "not fully supported by Kotlin compiler", mirroring the warning of K1,
 * while K2 does not resolve it at all, or one which Kotlin hides, see [HIDDEN_JDK_METHODS],
 * so that it is neither rendered, nor shadows the extensions of its signature.
 * A member whose annotations cannot be read, like one kotlin-reflect cannot describe, is not told callable either,
 * which might leave out a member Kotlin does call, rather than list one it does not.
 */
internal fun isUnsupportedByCompiler(callable: KCallable<*>): Boolean =
    reflectedValue(default = true) {
        callable.annotations.any { it is Deprecated && it.message.startsWith(UNSUPPORTED_MEMBER_MESSAGE) }
    } || callable.name in hiddenJdkMethodNames && reflectedValue(default = false) {
        (callable as? KFunction<*>)?.javaMethod?.let { jvmSignature(it) in HIDDEN_JDK_METHODS } == true
    }

private const val UNSUPPORTED_MEMBER_MESSAGE = "This member is not fully supported by Kotlin compiler"

/**
 * The JDK methods of the built-in types which Kotlin hides, though kotlin-reflect lists them without telling,
 * like `getDeclaringClass()` of `java.lang.Enum`, in favor of the `declaringJavaClass` extension,
 * or `reversed()` of `java.util.List` since JDK 21, so that `list.reversed()` calls the `Iterable<T>.reversed()` extension,
 * or `isEmpty()` of `java.lang.CharSequence` since JDK 15, in favor of the `CharSequence.isEmpty()` extension,
 * by their JVM signatures, see [jvmSignature]: the `HIDDEN_METHOD_SIGNATURES` of `JvmBuiltInsSignatures` of the compiler,
 * but the ones of the final classes, like `String.intern()`, which kotlin-reflect leaves out itself,
 * compared with the copy of kotlin-reflect by `ReflectedDeclarationsTest`.
 */
internal val HIDDEN_JDK_METHODS: Set<String> = setOf(
    "java/lang/CharSequence.isEmpty()Z",
    "java/lang/Enum.finalize()V",
    "java/lang/Enum.getDeclaringClass()Ljava/lang/Class;",
    "java/util/List.reversed()Ljava/util/List;",
    "java/util/List.sort(Ljava/util/Comparator;)V"
)

// telling most members apart without resolving their JVM methods
private val hiddenJdkMethodNames: Set<String> = HIDDEN_JDK_METHODS.mapTo(HashSet()) { it.substringAfter('.').substringBefore('(') }

/**
 * Whether the [method], of a Java class, overrides one of the [HIDDEN_JDK_METHODS] of its supertypes,
 * like the `reversed()` of `java.util.LinkedList`, of `java.util.List`, which Kotlin hides in the subclasses too,
 * so that `linkedList.reversed()` calls the `Iterable<T>.reversed()` extension.
 */
internal fun overridesHiddenJdkMethod(method: Method): Boolean =
    method.name in hiddenJdkMethodNames && reflectedValue(default = false) {
        val parameterTypes = method.parameterTypes
        val visited = HashSet<Class<*>>()
        val pending = ArrayDeque<Class<*>>()
        fun Class<*>.enqueueSupertypes() {
            superclass?.let { pending += it }
            pending += interfaces
        }
        method.declaringClass.enqueueSupertypes()
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst()
            if (!visited.add(type)) continue
            // also among the bridges, like the reversed(): SequencedCollection of java.util.List
            val overridden = type.declaredMethods.filter { it.name == method.name && it.parameterTypes.contentEquals(parameterTypes) }
            if (overridden.any { jvmSignature(it) in HIDDEN_JDK_METHODS }) return@reflectedValue true
            type.enqueueSupertypes()
        }
        false
    }

/**
 * The JVM signature of the [method], like `java/util/List.reversed()Ljava/util/List;`, see [HIDDEN_JDK_METHODS].
 */
internal fun jvmSignature(method: Method): String =
    AsmType.getInternalName(method.declaringClass) + "." + method.name + AsmType.getMethodDescriptor(method)

/**
 * A [value] which kotlin-reflect builds from the metadata of the built-ins, like an annotation,
 * or the companion object of a built-in type,
 * or the [default] when it cannot be read, like a parameter with the default value, absent from the metadata,
 * whose getter throws `KotlinReflectionInternalError`, as for the `level` of `@Deprecated` of `Long.toChar()`,
 * but never when the virtual machine fails, like when out of memory.
 */
internal inline fun <T> reflectedValue(default: T, value: () -> T): T = try {
    value()
} catch (_: Exception) {
    default
} catch (e: Error) {
    if (e is VirtualMachineError) throw e
    default
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
 * The declaration of this built-in type in the Kotlin metadata, named like `kotlin/String`, as kotlin-reflect describes it,
 * when its declaration in the `.kotlin_builtins` resources cannot be read, see [readKotlinBuiltinClasses].
 *
 * kotlin-reflect describes the JVM class, like `java.lang.String`, so that the declaration is approximate:
 * its supertypes are only the Kotlin ones, its members are the ones the JVM class declares,
 * but the Java-only ones, see [javaOnlyFunctions], like `initCause(arg0: Throwable?)`, or `fillInStackTrace()`,
 * left to the JVM-only members, like when the declarations of the built-ins are read,
 * so that they are hidden by extensions, and overridden, alike,
 * and the ones Kotlin does not call, see [isUnsupportedByCompiler],
 * and its constructors are the ones of the JVM class whose parameter names are known, like `String()`,
 * see [hasSyntheticParameterNames],
 * as the others, like `Integer(int)`, have no counterpart in Kotlin.
 * Its companion object is not declared.
 */
internal fun KClass<*>.toKmBuiltinClass(name: String): KmClass {
    val kmClass = toKmClassHeader(name) { typeParameters ->
        supertypes
            .filter { supertype -> (supertype.classifier as? KClass<*>)?.qualifiedName?.startsWith("kotlin.") == true }
            .map { it.toKmType(typeParameters) }
    }
    val typeParameters = ReflectedTypeParameters.classTypeParameters(kmClass.typeParameters)
    val functions = declaredMemberFunctions.filter { '$' !in it.name && !isUnsupportedByCompiler(it) }
    val javaOnly = javaOnlyFunctions(functions)
    functions
        .filter { it !in javaOnly }
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
                // a constructor infers the type arguments of the class at the call, like a function its own ones
                constructor.parameters.withIndex().mapTo(kmConstructor.valueParameters) { (position, parameter) ->
                    parameter.toKmValueParameter(position, typeParameters, ownTypeParameters = this.typeParameters)
                }
            }
        }
    return kmClass
}

/**
 * The Java-only ones of the [functions] of a built-in type described by kotlin-reflect, see [toKmBuiltinClass],
 * which it does not tell apart from the ones the built-in type declares, both backed by the JDK methods:
 * the ones whose parameter names are not known, like `initCause(arg0: Throwable?)`, see [hasSyntheticParameterNames],
 * the ones referring to a platform type, like `fillInStackTrace(): Throwable!`, or to a Java class,
 * like `stream(): java.util.stream.Stream<E>` of `Collection`, which no built-in declaration does,
 * and the overloads of these, like `printStackTrace()`, as a built-in type never overloads a JDK method it does not map,
 * which `ReflectedDeclarationsTest` guards, comparing the members left with the ones of the `.kotlin_builtins` declarations.
 */
private fun javaOnlyFunctions(functions: List<KFunction<*>>): Set<KFunction<*>> {
    val javaOnly = functions.filterTo(HashSet()) { function ->
        // a function whose types cannot be read is no built-in member, which always has readable types
        hasSyntheticParameterNames(function) || reflectedValue(default = true) {
            (function.parameters.filter { it.kind == KParameter.Kind.VALUE }.map { it.type } + function.returnType)
                .any { it.refersToJavaType() }
        }
    }
    val javaOnlyNames = javaOnly.mapTo(HashSet()) { it.name }
    return functions.filterTo(javaOnly) { it.name in javaOnlyNames }
}

/**
 * Whether this type, or one of its type arguments, is a platform type, see [isPlatformNullable], or a Java class,
 * one not of the `kotlin.*` packages, see [javaOnlyFunctions].
 */
private fun KType.refersToJavaType(): Boolean =
    isPlatformNullable ||
        (classifier as? KClass<*>)?.qualifiedName?.startsWith("kotlin.") == false ||
        arguments.any { it.type?.refersToJavaType() == true }

/**
 * The declaration of this Java class in the Kotlin metadata, named like `java/util/ArrayList`, as kotlin-reflect describes it,
 * without its members, which the caller describes one by one, see [toKmFunction], and [toKmProperty].
 * Its supertypes are named by their JVM classes, see [supertypeName],
 * like `kotlin/collections/MutableList` of `java.util.List`, as kotlin-reflect tells only its read-only counterpart.
 */
internal fun KClass<*>.toKmJavaClassHeader(name: String, supertypeName: (Class<*>) -> String): KmClass =
    toKmClassHeader(name) { typeParameters ->
        supertypes.map { supertype ->
            supertype.toKmType(typeParameters).also { type ->
                (supertype.classifier as? KClass<*>)?.let { type.classifier = KmClassifier.Class(supertypeName(it.java)) }
            }
        }
    }

/**
 * The declaration of this class in the Kotlin metadata, named [name], without its members and constructors,
 * of its [supertypes], given the type parameters of the class.
 */
private fun KClass<*>.toKmClassHeader(name: String, supertypes: (ReflectedTypeParameters) -> List<KmType>): KmClass {
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
        // like the implicit Any! bound of a Java type parameter, which admits nullable type arguments, see [toKmTypeParameter]
        typeParameter.upperBounds.mapTo(kmTypeParameter.upperBounds) { it.toKmType(typeParameters, platformAsNullable = true) }
    }
    kmClass.supertypes += supertypes(typeParameters)
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
    // a member of a JDK class, like `merge` of `java.util.HashMap`, takes the type parameters of its class as in Kotlin,
    // while a member of another Java class, like the override of `merge` of a `MyMap<K, V> extends HashMap<K, V>`, takes them as `K!`
    val isJdkMember = lazy(LazyThreadSafetyMode.NONE) {
        reflectedValue(default = true) { javaMethod?.declaringClass?.isJdkClass ?: true }
    }
    parameters.filter { it.kind == KParameter.Kind.VALUE }.withIndex().mapTo(function.valueParameters) { (position, parameter) ->
        parameter.toKmValueParameter(position, scope, ownTypeParameters = this.typeParameters, isJdkMember = isJdkMember)
    }
    function.returnType = returnType.toKmType(scope)
    enhanceDefinitelyNotNull(function)
    enhanceNullableReturn(function)
    return function
}

/**
 * The types of the JDK methods which K2 enhances as definitely not nullable, like `V & Any`,
 * by the JVM signatures of the methods, like `java/util/Map.merge(…)`,
 * and the positions of their value parameters, like `1` of the value of `merge`,
 * by the positions of the types in each one, the type itself first, then its type arguments, in depth-first order,
 * like `1` and `2` of the `V` and `V` of `BiFunction<V, V, V>`:
 * the definitely not nullable ones of `PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE` of the compiler,
 * but the ones of `java.util.Optional`, a final class, whose members are never inherited,
 * compared with the copy of kotlin-reflect by `ReflectedDeclarationsTest`,
 * which also guards that they are only the value parameters of the methods of `java.util.Map`,
 * the only ones [enhanceDefinitelyNotNull] applies.
 * kotlin-reflect applies them too, though its public API does not tell such a type apart from a type parameter
 * of a Java method overriding a mapped member, like `E` of `add` of `java.util.TreeSet`, which is not enhanced so.
 */
internal val DEFINITELY_NOT_NULL_JDK_TYPES: Map<String, Map<Int, Set<Int>>> = mapOf(
    "java/util/Map.computeIfPresent(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;" to
        mapOf(1 to setOf(2)),
    "java/util/Map.merge(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;" to
        mapOf(1 to setOf(0), 2 to setOf(1, 2))
)

// telling most functions apart without resolving their JVM methods
private val definitelyNotNullJdkMethodNames: Set<String> =
    DEFINITELY_NOT_NULL_JDK_TYPES.keys.mapTo(HashSet()) { it.substringAfter('.').substringBefore('(') }

/**
 * A [method] of `java.util.Map` of the [DEFINITELY_NOT_NULL_JDK_TYPES], with its [genericParameterTypes],
 * and its [enhanced] types, by the positions of its value parameters.
 */
private class EnhancedMapMethod(val method: Method, val genericParameterTypes: List<Type>, val enhanced: Map<Int, Set<Int>>)

/**
 * The methods of `java.util.Map` of the [DEFINITELY_NOT_NULL_JDK_TYPES], read once.
 */
private val definitelyNotNullMapMethods: List<EnhancedMapMethod> by lazy {
    val mapClass = Map::class.java
    mapClass.methods.mapNotNull { method ->
        val signature = AsmType.getInternalName(mapClass) + "." + method.name + AsmType.getMethodDescriptor(method)
        DEFINITELY_NOT_NULL_JDK_TYPES[signature]?.let { EnhancedMapMethod(method, method.genericParameterTypes.toList(), it) }
    }
}

/**
 * Marks the types of the value parameters of the [function], converted from this one,
 * which K2 enhances as definitely not nullable, see [DEFINITELY_NOT_NULL_JDK_TYPES],
 * also of a method overriding the JDK one, like `merge` of `java.util.HashMap`, which inherits the enhancement,
 * even with narrower parameter types, see [overriddenMapMethod],
 * when they are type parameters, like the `V` of the value of `merge`, unlike a type argument given by a subclass,
 * like the `List<T>` of a Java `ListValueMap<T> extends HashMap<String, List<T>>`, whose `T` is not enhanced,
 * the types of the override told by the ones of the `java.util.Map` method they stand for, see [alignedTypes].
 * Whether such a type is rendered as definitely not nullable, like `V & Any`, is decided by the bounds of its type parameter,
 * see [notNullableTypeParameters], as the one substituted for it might be not nullable, like the `V` of a `V : Any`.
 */
private fun KFunction<*>.enhanceDefinitelyNotNull(function: KmFunction) {
    if (name !in definitelyNotNullJdkMethodNames) return
    val method = reflectedValue(default = null) { javaMethod } ?: return
    val overridden = reflectedValue(default = null) { overriddenMapMethod(method) } ?: return
    overridden.enhanced.forEach { (position, indices) ->
        val mapType = overridden.genericParameterTypes.getOrNull(position) ?: return@forEach
        val types = alignedTypes(mapType, function.valueParameters.getOrNull(position)?.type)
        indices.mapNotNull { types.getOrNull(it) }.filter { it.classifier is KmClassifier.TypeParameter }.forEach { type ->
            type.isNullable = false
            type.isDefinitelyNonNull = true
        }
    }
}

/**
 * The method of `java.util.Map` of the [DEFINITELY_NOT_NULL_JDK_TYPES], see [EnhancedMapMethod], which the [method] declares or overrides,
 * or `null` if none.
 * An override might take narrower parameter types, like `merge(String, V, BiFunction)`
 * of a Java `StringKeyMap<V> extends HashMap<String, V>`, of a JVM signature of its own,
 * which overrides by the Java rule: its parameter types are the erasures of the ones of the `java.util.Map` method,
 * of the type arguments of `java.util.Map` given by the class declaring the [method], see [supertypeErasures].
 */
private fun overriddenMapMethod(method: Method): EnhancedMapMethod? {
    val candidates = definitelyNotNullMapMethods.filter {
        it.method.name == method.name && it.genericParameterTypes.size == method.parameterCount
    }
    if (candidates.isEmpty()) return null
    val erasures = mapErasures.get(method.declaringClass) ?: return null
    val parameterTypes = method.parameterTypes.toList()
    return candidates.firstOrNull { candidate -> candidate.genericParameterTypes.map { erasure(it, erasures) } == parameterTypes }
}

/**
 * The erasures of the type arguments of `java.util.Map`, see [supertypeErasures], by the classes implementing it,
 * computed once per class, like for each of its methods enhanced.
 */
private val mapErasures = object : ClassValue<Map<TypeVariable<*>, Class<*>>?>() {
    override fun computeValue(type: Class<*>): Map<TypeVariable<*>, Class<*>>? =
        reflectedValue(default = null) { supertypeErasures(type, Map::class.java) }
}

/**
 * The types of the value parameter of a method overriding a method of `java.util.Map`, the [type],
 * standing for the ones of the parameter of the `java.util.Map` method, the [mapType], in depth-first order,
 * the type itself first, then its type arguments, see [DEFINITELY_NOT_NULL_JDK_TYPES],
 * where a type variable of `java.util.Map`, like `V`, stands for the whole type given for it,
 * like `List<T>`, of whose type arguments none stands for a type of the [mapType],
 * and `null` stands for a type the [type] does not tell, like the type argument of a raw type, or a star projection.
 */
private fun alignedTypes(mapType: Type, type: KmType?): List<KmType?> = buildList {
    fun visit(mapType: Type, type: KmType?) {
        add(type)
        if (mapType !is ParameterizedType) return
        mapType.actualTypeArguments.forEachIndexed { index, argument ->
            // a wildcard, like `? super V`, is a projection of its bound, like `in V`
            val bound = (argument as? WildcardType)?.let { it.lowerBounds.firstOrNull() ?: it.upperBounds.firstOrNull() } ?: argument
            visit(bound, type?.arguments?.getOrNull(index)?.type)
        }
    }
    visit(mapType, type)
}

/**
 * The erasures of the type arguments of the type variables of the [target] class, a supertype of the [javaClass],
 * where the type variables of the [javaClass] are the erasures of their bounds,
 * like `String` and `Object` of `K` and `V` of `java.util.Map` seen from a `StringKeyMap<V> extends HashMap<String, V>`,
 * or `null` if the [target] is not a supertype of the [javaClass].
 * It does not reuse the Java erasure of the [OverrideResolver], whose `javaSubstitution` resolves the types,
 * by their Kotlin names, of the members of an owner class it reads, while this compares the JVM classes of a reflected method.
 */
private fun supertypeErasures(
    javaClass: Class<*>,
    target: Class<*>,
    erasures: Map<TypeVariable<*>, Class<*>> = javaClass.typeParameters.associateWith { erasure(it, emptyMap()) }
): Map<TypeVariable<*>, Class<*>>? {
    if (javaClass == target) return erasures
    if (!target.isAssignableFrom(javaClass)) return null
    return (listOfNotNull(javaClass.genericSuperclass) + javaClass.genericInterfaces).firstNotNullOfOrNull { supertype ->
        val rawType = (if (supertype is ParameterizedType) supertype.rawType else supertype) as? Class<*>
            ?: return@firstNotNullOfOrNull null
        // a raw supertype gives the erasures of the bounds of its type variables
        val arguments = (supertype as? ParameterizedType)?.actualTypeArguments
        supertypeErasures(rawType, target, rawType.typeParameters.withIndex().associate { (index, typeVariable) ->
            typeVariable to erasure(arguments?.getOrNull(index) ?: typeVariable, erasures)
        })
    }
}

/**
 * The erasure of the Java [type], where the type variables of the [erasures] are erased to them,
 * and any other one to the erasure of its first bound.
 */
private fun erasure(type: Type, erasures: Map<TypeVariable<*>, Class<*>>): Class<*> {
    var erased: Type = type
    // javac rejects cyclic bounds, but a malformed class file might declare them, which reflection does not check
    val visited = mutableSetOf<TypeVariable<*>>()
    while (erased is TypeVariable<*> && visited.add(erased)) {
        erased = erasures[erased] ?: erased.bounds.firstOrNull() ?: Any::class.java
    }
    return when (erased) {
        is Class<*> -> erased
        is ParameterizedType -> erased.rawType as Class<*>
        is GenericArrayType -> JavaArray.newInstance(erasure(erased.genericComponentType, erasures), 0).javaClass
        is WildcardType -> erasure(erased.upperBounds.firstOrNull() ?: Any::class.java, erasures)
        else -> Any::class.java
    }
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

/**
 * The declaration of this parameter in the Kotlin metadata, at the [position] among the value parameters,
 * of a callable declaring the [ownTypeParameters], whose type arguments are inferred at the call,
 * unlike the ones of the class, given by the receiver, and whether it is a member of a JDK class, the [isJdkMember],
 * told only when needed.
 */
private fun KParameter.toKmValueParameter(
    position: Int,
    scope: ReflectedTypeParameters,
    ownTypeParameters: List<KTypeParameter> = emptyList(),
    isJdkMember: Lazy<Boolean> = lazyOf(true)
): KmValueParameter =
    KmValueParameter(name ?: "arg$position").also { parameter ->
        // a Java parameter of a platform type, like Any! of Object, accepts null,
        // but a vararg one, whose array is never null, and which tells the nullability of its elements below,
        // and one of a type parameter of the class of a JDK member, like E! of `addFirst` of `java.util.List`, which accepts null
        // only when the type argument of the receiver does, unlike for `addFirst(null)` of a `MutableList<String>`,
        // while K2 takes the one of a member of another Java class as a platform type, like `addFirst(null)` of a Java subclass
        // of `java.util.ArrayList<String>` overriding it, which compiles
        val classifier = type.classifier
        val isClassTypeParameter = classifier is KTypeParameter && classifier !in ownTypeParameters && isJdkMember.value
        val type = type.toKmType(scope, platformAsNullable = !isVararg && !isClassTypeParameter)
        // a raw type of Java, like the BiFunction of `merge` of a Java subclass of a raw `java.util.HashMap`,
        // which kotlin-reflect tells by the type arguments of its lower bound, like `BiFunction<Any!, Any!, Any!>`,
        // is `(BiFunction<Any!, Any!, Any?>..BiFunction<*, *, *>)` in K2, overridden by its upper bound, and not by `BiFunction<Any, Any, Any>`
        if (this.type.isRawJavaType) type.arguments.replaceAll { KmTypeProjection.STAR }
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

/**
 * Whether this type is a raw type of Java, like `BiFunction` of a raw `java.util.HashMap`,
 * which kotlin-reflect tells only by its Java type, a [Class] of type parameters, rather than a [ParameterizedType].
 */
private val KType.isRawJavaType: Boolean
    get() = arguments.isNotEmpty() && reflectedValue(default = false) {
        val javaType = javaType
        javaType is Class<*> && javaType.typeParameters.isNotEmpty() && (classifier as? KClass<*>)?.java == javaType
    }

/**
 * The JDK methods whose return types K2 enhances, as not nullable, or nullable, so that they are not platform types,
 * like the `T` of `get()` of `java.util.function.Supplier`, by their JVM signatures:
 * the ones of `PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE` of the compiler, of the classes which are not final,
 * compared with the copy of kotlin-reflect by `ReflectedDeclarationsTest`,
 * as kotlin-reflect applies them, though its public API still tells such a type as a platform type, like `T!`.
 */
internal val ENHANCED_RETURN_JDK_METHODS: Set<String> = setOf(
    "java/lang/Iterable.spliterator()Ljava/util/Spliterator;",
    "java/lang/ref/Reference.get()Ljava/lang/Object;",
    "java/util/Collection.parallelStream()Ljava/util/stream/Stream;",
    "java/util/Collection.stream()Ljava/util/stream/Stream;",
    "java/util/LinkedHashMap.putFirst(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
    "java/util/LinkedHashMap.putLast(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
    "java/util/LinkedHashSet.getFirst()Ljava/lang/Object;",
    "java/util/LinkedHashSet.getLast()Ljava/lang/Object;",
    "java/util/LinkedHashSet.removeFirst()Ljava/lang/Object;",
    "java/util/LinkedHashSet.removeLast()Ljava/lang/Object;",
    "java/util/LinkedList.removeFirst()Ljava/lang/Object;",
    "java/util/LinkedList.removeLast()Ljava/lang/Object;",
    "java/util/List.removeFirst()Ljava/lang/Object;",
    "java/util/List.removeLast()Ljava/lang/Object;",
    "java/util/Map.compute(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;",
    "java/util/Map.computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;",
    "java/util/Map.computeIfPresent(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;",
    "java/util/Map.merge(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;",
    "java/util/Map.putIfAbsent(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
    "java/util/Map.replace(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
    "java/util/function/BiFunction.apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
    "java/util/function/Function.apply(Ljava/lang/Object;)Ljava/lang/Object;",
    "java/util/function/Supplier.get()Ljava/lang/Object;"
)

/**
 * The JDK methods whose return types K2 enhances as nullable, of the [ENHANCED_RETURN_JDK_METHODS],
 * like the `T?` of `get()` of `java.lang.ref.Reference`, see [enhanceNullableReturn],
 * compared with the copy of kotlin-reflect by `ReflectedDeclarationsTest`.
 */
internal val NULLABLE_RETURN_JDK_METHODS: Set<String> = setOf(
    "java/lang/ref/Reference.get()Ljava/lang/Object;",
    "java/util/LinkedHashMap.putFirst(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
    "java/util/LinkedHashMap.putLast(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
    "java/util/Map.compute(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;",
    "java/util/Map.computeIfPresent(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;",
    "java/util/Map.merge(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;",
    "java/util/Map.putIfAbsent(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
    "java/util/Map.replace(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
)

// the names and JVM descriptors of the methods, like `get()Ljava/lang/Object;`, see isEnhancedJdkMethod
private val enhancedReturnJdkMethodDescriptors: Set<String> =
    ENHANCED_RETURN_JDK_METHODS.mapTo(HashSet()) { it.substringAfter('.') }

// telling most functions apart without resolving their JVM methods, see enhanceNullableReturn
private val nullableReturnJdkMethodNames: Set<String> =
    NULLABLE_RETURN_JDK_METHODS.mapTo(HashSet()) { it.substringAfter('.').substringBefore('(') }

/**
 * Whether the [method] is one of the JDK methods of the [signatures], see [ENHANCED_RETURN_JDK_METHODS],
 * or overrides one of them by the same JVM descriptor, inheriting its enhancement, where [isOverrideEnhanced],
 * like `get()` of `java.lang.ref.SoftReference` the one of `java.lang.ref.Reference`,
 * unlike an override of another descriptor, like the `String get()` of a Java interface extending `Supplier<String>`,
 * or an overload of the same name.
 * The supertypes are walked only for a name and descriptor of the [signatures].
 */
private fun isEnhancedJdkMethod(method: Method, signatures: Set<String>, isOverrideEnhanced: (Method) -> Boolean): Boolean {
    val nameAndDescriptor = method.name + AsmType.getMethodDescriptor(method)
    if (nameAndDescriptor !in enhancedReturnJdkMethodDescriptors) return false
    if (jvmSignature(method) in signatures) return true
    if (!isOverrideEnhanced(method)) return false
    val visited = HashSet<Class<*>>()
    val pending = ArrayDeque(listOf<Class<*>>(method.declaringClass))
    while (pending.isNotEmpty()) {
        val clazz = pending.removeFirst()
        if (!visited.add(clazz)) continue
        if (AsmType.getInternalName(clazz) + "." + nameAndDescriptor in signatures) return true
        clazz.superclass?.let { pending += it }
        pending += clazz.interfaces
    }
    return false
}

/**
 * Whether this class is a class of the JDK, defined by the bootstrap, or the platform, class loader.
 */
private val Class<*>.isJdkClass: Boolean
    get() = classLoader.let { it == null || it == ClassLoader.getPlatformClassLoader() }

/**
 * Marks the return type of the [function], converted from this one, nullable, when K2 enhances it so,
 * see [NULLABLE_RETURN_JDK_METHODS], also of a method overriding the JDK one, see [isEnhancedJdkMethod],
 * unlike the platform type kotlin-reflect tells, rendered as not nullable,
 * like the `V` of `putFirst` of `java.util.LinkedHashMap`, which returns `V?` in Kotlin.
 */
private fun KFunction<*>.enhanceNullableReturn(function: KmFunction) {
    if (name !in nullableReturnJdkMethodNames) return
    val method = reflectedValue(default = null) { javaMethod } ?: return
    if (reflectedValue(default = false) { isEnhancedJdkMethod(method, NULLABLE_RETURN_JDK_METHODS) { true } }) {
        function.returnType.isNullable = true
    }
}

/**
 * Whether this function, of a Java class, returns a platform type of Java, see [isPlatformNullable],
 * unless it is one of the JDK methods K2 enhances, see [ENHANCED_RETURN_JDK_METHODS],
 * or a JDK method overriding one of them by the same JVM descriptor, inheriting the enhancement, see [isEnhancedJdkMethod],
 * like `computeIfAbsent` of `java.util.HashMap`, unlike the same override of a Java class of the user code,
 * like `T get()` of a Java interface extending `java.util.function.Supplier<T>`, returning `T!`,
 * or the `String get()` of one extending `Supplier<String>`, or an overload of the same name.
 * The nullable return types are enhanced also of the overrides of the user code, see [enhanceNullableReturn].
 */
internal val KFunction<*>.returnsPlatformType: Boolean
    get() {
        if (!returnType.isPlatformNullable) return false
        val method = reflectedValue(default = null) { javaMethod } ?: return true
        return !reflectedValue(default = false) {
            isEnhancedJdkMethod(method, ENHANCED_RETURN_JDK_METHODS) { it.declaringClass.isJdkClass }
        }
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
        // telling their arity by the type arguments, apart from KFunction<R> itself, which takes one,
        // so that a KFunction0<R> stays a KFunction<R>, and a KSuspendFunctionN is a KFunctionN,
        // as the public API cannot tell them apart, unreachable for the members of the built-in types
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
    val mutableName = (type.classifier as? KmClassifier.Class)?.name?.let { MUTABLE_COLLECTION_TYPES[it] }
    if (mutableName != null && classifier is KClass<*> && isContravariantlyProjected(classifier, arguments)) {
        type.classifier = KmClassifier.Class(mutableName)
    }
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
 * The mutable counterparts of the read-only collection types, by their class names,
 * like `kotlin/collections/MutableCollection` of `kotlin/collections/Collection`.
 */
internal val MUTABLE_COLLECTION_TYPES: Map<String, String> = listOf(
    "Iterable", "Collection", "List", "Set", "Map", "Iterator", "ListIterator"
).associate { "kotlin/collections/$it" to "kotlin/collections/Mutable$it" } +
    ("kotlin/collections/Map.Entry" to "kotlin/collections/MutableMap.MutableEntry")

/**
 * Whether the type of the read-only collection [kClass], like `Collection<out E>`, described by kotlin-reflect,
 * takes an `in` projection, the [arguments], of a type parameter it declares `out`, like `Collection<in E>`,
 * which conflicts with its variance, so that it stands for a Java type seen as a flexible collection type,
 * like `(Mutable)Collection<in E!>!` of `Collection<? super E>`, callable only as its mutable counterpart,
 * like `MutableCollection<in E>`, see [MUTABLE_COLLECTION_TYPES].
 */
private fun isContravariantlyProjected(kClass: KClass<*>, arguments: List<KmTypeProjection>): Boolean {
    val typeParameters = kClass.typeParameters
    return arguments.withIndex().any { (index, argument) ->
        argument.variance == KmVariance.IN && typeParameters.getOrNull(index)?.variance == KVariance.OUT
    }
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
