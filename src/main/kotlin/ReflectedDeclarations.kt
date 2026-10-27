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

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.TypePath
import org.objectweb.asm.TypeReference
import org.objectweb.asm.Type as AsmType
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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
 * Whether the Java [method] overrides a method of a supertype of its class which kotlin-reflect describes as a built-in type,
 * like the `contains(Object)` of `java.util.Collection`, described as `Collection`, or the `hashCode()` of `java.lang.Object`,
 * by the same name and parameter types, or of the erasures of the type arguments given for its type variables,
 * see [toKmJavaFunction], like `compareTo(File)` of `java.io.File` the `compareTo(T)` of `java.lang.Comparable<File>`,
 * erased to `Object`, unlike an overload, like `equals(File)`, or `clone()`, a member of `java.lang.Object` but not of `kotlin.Any`.
 */
internal fun overridesBuiltinJavaMethod(method: Method): Boolean {
    val parameterTypes = method.parameterTypes
    val visited = HashSet<Class<*>>()
    val pending = ArrayDeque<Class<*>>()
    fun Class<*>.enqueueSupertypes() {
        superclass?.let { pending += it }
        pending += interfaces
    }
    method.declaringClass.enqueueSupertypes()
    // an interface, like java.util.Collection, also overrides the methods of java.lang.Object
    if (method.declaringClass.isInterface) pending += Any::class.java
    while (pending.isNotEmpty()) {
        val type = pending.removeFirst()
        if (!visited.add(type)) continue
        if (type.kotlin.qualifiedName != type.canonicalName &&
            type.declaredMethods.any { overridden ->
                overridden.name == method.name && overridden.parameterCount == parameterTypes.size &&
                    (type != Any::class.java || overridden.name in ANY_MEMBER_NAMES) &&
                    overridden.genericParameterTypes.indices.all { index ->
                        val erased = overridden.parameterTypes[index]
                        erased == parameterTypes[index] ||
                            (overridden.genericParameterTypes[index] is TypeVariable<*> && erased.isAssignableFrom(parameterTypes[index]))
                    }
            }
        ) {
            return true
        }
        type.enqueueSupertypes()
    }
    return false
}

// the methods of java.lang.Object which kotlin.Any declares, see overridesBuiltinJavaMethod
private val ANY_MEMBER_NAMES = setOf("equals", "hashCode", "toString")

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
    fun idOf(typeParameter: KTypeParameter): Int = idOf(typeParameter.name)

    /**
     * The id of a type parameter named [name], like for a type variable of Java, see [toKmJavaType].
     */
    fun idOf(name: String): Int = ids[name] ?: -1

    /**
     * The type parameters in scope of a declaration declaring the [own] type parameters,
     * shadowing the ones of the class of the same name.
     */
    fun withOwn(own: List<KTypeParameter>): ReflectedTypeParameters = withOwnNamed(own.map { it.name })

    /**
     * The type parameters in scope of a declaration declaring its own ones, by their [names], see [withOwn].
     */
    fun withOwnNamed(names: List<String>): ReflectedTypeParameters {
        val firstId = (ids.values.maxOrNull() ?: -1) + 1
        return ReflectedTypeParameters(ids + names.mapIndexed { index, name -> name to firstId + index })
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
 * where the type parameters of its class are the [typeParameters], see [ReflectedTypeParameters],
 * of a Java method of the nullability its annotations tell in the class file, see [enhanceJavaNullability].
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
        parameter.toKmValueParameter(position, scope, ownTypeParameters = this.typeParameters)
    }
    function.returnType = returnType.toKmType(scope)
    reflectedValue(default = null) { javaMethod }?.let { enhanceJavaNullability(it, function) }
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
    enhanceDefinitelyNotNull(reflectedValue(default = null) { javaMethod } ?: return, function)
}

/**
 * Marks the types of the value parameters of the [function], converted from the [method], see [KFunction.enhanceDefinitelyNotNull].
 */
private fun enhanceDefinitelyNotNull(method: Method, function: KmFunction) {
    if (method.name !in definitelyNotNullJdkMethodNames) return
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
 * unlike the ones of the class, given by the receiver.
 */
private fun KParameter.toKmValueParameter(
    position: Int,
    scope: ReflectedTypeParameters,
    ownTypeParameters: List<KTypeParameter> = emptyList()
): KmValueParameter =
    KmValueParameter(name ?: "arg$position").also { parameter ->
        // a Java parameter of a platform type, like Any! of Object, accepts null,
        // but a vararg one, whose array is never null, and which tells the nullability of its elements below,
        // and one of a type parameter of the class, like E! of `addFirst` of `java.util.List`, which accepts null
        // only when the type argument of the receiver does, unlike for `addFirst(null)` of a `MutableList<String>`
        val classifier = type.classifier
        val isClassTypeParameter = classifier is KTypeParameter && classifier !in ownTypeParameters
        val type = type.toKmType(scope, platformAsNullable = !isVararg && !isClassTypeParameter)
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

// the names and JVM descriptors of the parameters of the methods, like `get()`, see isEnhancedJdkMethod
private val enhancedReturnJdkMethodParameters: Set<String> =
    ENHANCED_RETURN_JDK_METHODS.mapTo(HashSet()) { it.substringAfter('.').withoutReturnType() }

// the nullable ones, by the JVM signatures without the return types, like `java/lang/ref/Reference.get()`
private val nullableReturnJdkMethodParameters: Set<String> =
    NULLABLE_RETURN_JDK_METHODS.mapTo(HashSet()) { it.withoutReturnType() }

// a JVM signature, or descriptor, without its return type, like `get()` of `get()Ljava/lang/Object;`
private fun String.withoutReturnType(): String = substringBefore(')') + ')'

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
    return method.overridesAnyOf(nameAndDescriptor, signatures)
}

/**
 * Whether the [method] is one of the JDK methods whose return types K2 enhances as nullable, see [NULLABLE_RETURN_JDK_METHODS],
 * or overrides one of them, inheriting the nullability, also with a covariant return type, of another JVM descriptor,
 * like the `String get()` of a Java subclass of `java.lang.ref.WeakReference<String>`, returning `String?` in Kotlin,
 * unlike an overload of the same name.
 */
private fun isNullableReturnJdkMethod(method: Method): Boolean {
    val nameAndParameters = method.name + AsmType.getMethodDescriptor(method).withoutReturnType()
    if (nameAndParameters !in enhancedReturnJdkMethodParameters) return false
    return method.overridesAnyOf(nameAndParameters, nullableReturnJdkMethodParameters)
}

/**
 * Whether this method, or one of its supertypes, declares the method of the [nameAndDescriptor],
 * like `get()Ljava/lang/Object;`, or, without its return type, `get()`, of one of the [signatures],
 * JVM signatures of the same form, like `java/lang/ref/Reference.get()`.
 */
private fun Method.overridesAnyOf(nameAndDescriptor: String, signatures: Set<String>): Boolean {
    val visited = HashSet<Class<*>>()
    val pending = ArrayDeque(listOf<Class<*>>(declaringClass))
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
internal val Class<*>.isJdkClass: Boolean
    get() = classLoader.let { it == null || it == ClassLoader.getPlatformClassLoader() }

/**
 * Marks the return type of the [function], converted from this one, nullable, when K2 enhances it so,
 * see [NULLABLE_RETURN_JDK_METHODS], also of a method overriding the JDK one, see [isNullableReturnJdkMethod],
 * unlike the platform type kotlin-reflect tells, rendered as not nullable,
 * like the `V` of `putFirst` of `java.util.LinkedHashMap`, which returns `V?` in Kotlin.
 */
private fun KFunction<*>.enhanceNullableReturn(function: KmFunction) {
    if (name !in nullableReturnJdkMethodNames) return
    enhanceNullableReturn(reflectedValue(default = null) { javaMethod } ?: return, function)
}

/**
 * Marks the return type of the [function], converted from the [method], nullable, see [KFunction.enhanceNullableReturn].
 */
private fun enhanceNullableReturn(method: Method, function: KmFunction) {
    if (method.name !in nullableReturnJdkMethodNames) return
    if (reflectedValue(default = false) { isNullableReturnJdkMethod(method) }) {
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
    get() = returnsPlatformType(isEnhancementLost = false)

/**
 * Whether this function, of a Java class, returns a platform type of Java, see [returnsPlatformType],
 * also when it is one of the JDK methods K2 enhances, where [isEnhancementLost],
 * like the `get()` of `java.util.function.Supplier` inherited through a Java class of the user code not declaring it again,
 * which the compiler sees there returning `T!`.
 */
internal fun KFunction<*>.returnsPlatformType(isEnhancementLost: Boolean): Boolean {
    // kotlin-reflect tells some of the enhanced types as such, like the V of computeIfAbsent of java.util.HashMap
    if (!returnType.isPlatformNullable && !isEnhancementLost) return false
    return (reflectedValue(default = null) { javaMethod } ?: return true).returnsPlatformType(isEnhancementLost)
}

/**
 * Whether this Java method returns a platform type of Java, see [KFunction.returnsPlatformType],
 * any type but a primitive one, or one told by the nullability annotations, see [javaNullabilityOf].
 */
internal fun Method.returnsPlatformType(isEnhancementLost: Boolean): Boolean {
    if (returnType.isPrimitive || javaNullabilityOf(this)?.returnType != null) return false
    if (isEnhancementLost) return true
    return !reflectedValue(default = false) {
        isEnhancedJdkMethod(this, ENHANCED_RETURN_JDK_METHODS) { it.declaringClass.isJdkClass }
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

/*
 * The declarations of the members of the Java classes which kotlin-reflect cannot describe, see [toKmJavaFunction],
 * converted from the Java reflection to the Kotlin metadata as kotlin-reflect would describe them,
 * which `ReflectedDeclarationsTest` compares on the members of the JDK classes it describes.
 */

/**
 * The declaration of this Java method in the Kotlin metadata, see [toKmFunction], converted from the Java reflection,
 * where the type parameters of its class are the [typeParameters], see [ReflectedTypeParameters],
 * when kotlin-reflect cannot describe it, like the members of `java.util.LinkedHashSet` since JDK 21,
 * on all of which it fails at once, as on its inherited `clone()`,
 * or describes another class, like the read-only built-in type `ListIterator` of `java.util.ListIterator`,
 * whose `set(E)` it does not tell.
 * The types are converted as kotlin-reflect converts them, see [toKmJavaType],
 * nullable, or not, as the nullability annotations tell, see [enhanceJavaNullability],
 * and enhanced like the ones of the JDK methods it describes, see [enhanceDefinitelyNotNull], and [enhanceNullableReturn].
 * An open method of a final class, the [isFinalClass], is final, see [finalIn].
 */
internal fun Method.toKmJavaFunction(typeParameters: ReflectedTypeParameters, isFinalClass: Boolean): KmFunction {
    val scope = typeParameters.withOwnNamed(this.typeParameters.map { it.name })
    val function = KmFunction(name)
    function.visibility = javaVisibility(modifiers)
    function.isOperator = isJavaOperator(this)
    function.modality = when {
        Modifier.isAbstract(modifiers) -> Modality.ABSTRACT
        Modifier.isFinal(modifiers) || Modifier.isPrivate(modifiers) || isFinalClass -> Modality.FINAL
        else -> Modality.OPEN
    }
    this.typeParameters.mapTo(function.typeParameters) { typeVariable ->
        KmTypeParameter(typeVariable.name, scope.idOf(typeVariable.name), KmVariance.INVARIANT).also { typeParameter ->
            // like the implicit Any! bound of a Java type parameter, which admits nullable type arguments
            typeVariable.bounds.mapTo(typeParameter.upperBounds) { it.toKmJavaType(scope, platformAsNullable = true) }
        }
    }
    parameters.withIndex().mapTo(function.valueParameters) { (position, parameter) ->
        // the name told by the class file, or else the one of its position, like arg0, as kotlin-reflect names it
        KmValueParameter(if (parameter.isNamePresent) parameter.name else "arg$position").also { valueParameter ->
            // nullable like the one kotlin-reflect describes, see toKmValueParameter
            val type = parameter.parameterizedType
            val isClassTypeParameter = type is TypeVariable<*> && type.genericDeclaration is Class<*>
            valueParameter.type = type.toKmJavaType(scope, platformAsNullable = !parameter.isVarArgs && !isClassTypeParameter)
            if (parameter.isVarArgs) {
                val elementType = when (type) {
                    is GenericArrayType -> type.genericComponentType
                    is Class<*> -> type.componentType
                    else -> null
                }
                valueParameter.varargElementType = if (elementType != null && !(elementType is Class<*> && elementType.isPrimitive)) {
                    elementType.toKmJavaType(scope).also { it.isNullable = true }
                } else {
                    varargElementType(valueParameter.type)
                }
            }
        }
    }
    function.returnType = genericReturnType.toKmJavaType(scope)
    enhanceJavaNullability(this, function)
    enhanceDefinitelyNotNull(this, function)
    enhanceNullableReturn(this, function)
    return function
}

/**
 * Whether the Java [method] can be called as an operator, as kotlin-reflect tells, see [KFunction.isOperator],
 * by its name and shape, like `get(int)`, `plus(BigInteger)`, or `compareTo(T)` returning `int`,
 * the operator conventions of the compiler, but the ones of the delegated properties,
 * and the ones of the members a Java class overrides of the built-in types, see [overridesBuiltinJavaMethod].
 */
private fun isJavaOperator(method: Method): Boolean {
    val count = method.parameterCount
    val returnType = method.returnType
    val isValueOnly = !method.isVarArgs
    return when (method.name) {
        "invoke" -> true
        "get" -> count >= 1 && isValueOnly
        "set" -> count >= 2 && isValueOnly
        "contains", "hasNext" -> count == (if (method.name == "contains") 1 else 0) && returnType == java.lang.Boolean.TYPE
        "compareTo" -> count == 1 && returnType == Integer.TYPE
        "iterator", "next", "unaryPlus", "unaryMinus", "not" -> count == 0
        "inc", "dec" -> count == 0 && method.declaringClass.isAssignableFrom(returnType)
        "plus", "minus", "times", "div", "rem", "rangeTo", "rangeUntil" -> count == 1 && isValueOnly
        "plusAssign", "minusAssign", "timesAssign", "divAssign", "remAssign" -> count == 1 && isValueOnly && returnType == Void.TYPE
        else -> count == 0 && method.name.startsWith("component") && method.name.removePrefix("component").toIntOrNull()?.let { it > 0 } == true
    }
}

/**
 * The declaration of this Java field in the Kotlin metadata, see [toKmProperty], converted from the Java reflection,
 * like a method, see [toKmJavaFunction], a `var`, unless final.
 */
internal fun Field.toKmJavaProperty(typeParameters: ReflectedTypeParameters): KmProperty {
    val property = KmProperty(name)
    property.visibility = javaVisibility(modifiers)
    property.modality = Modality.FINAL
    if (!Modifier.isFinal(modifiers)) {
        property.isVar = true
        property.setter = KmPropertyAccessorAttributes().also { it.visibility = property.visibility }
    }
    property.returnType = genericType.toKmJavaType(typeParameters)
    return property
}

/**
 * The visibility of a Java member of the [modifiers], as kotlin-reflect tells it, see [reflectedVisibility],
 * which tells no visibility of a package-private one, nor of a protected one, also visible within its package.
 */
private fun javaVisibility(modifiers: Int): Visibility = when {
    Modifier.isPublic(modifiers) -> Visibility.PUBLIC
    Modifier.isPrivate(modifiers) -> Visibility.PRIVATE
    else -> Visibility.LOCAL
}

/**
 * The type of the Kotlin metadata converted from this Java type, as kotlin-reflect converts it, see [KType.toKmType],
 * where the type variables are in the [scope]:
 * a Java class mapped to a Kotlin one, like `kotlin/Int` of `int` or `java.lang.Integer`, or `kotlin/collections/List`
 * of `java.util.List`, the read-only one, but in a contravariant projection, see [isContravariantlyProjected],
 * an array, like `Array<String>`, or `IntArray`, a wildcard, like `? super E`, a projection, like `in E`,
 * and a platform type nullable when [platformAsNullable], unless of a primitive type.
 */
internal fun Type.toKmJavaType(scope: ReflectedTypeParameters, platformAsNullable: Boolean = false): KmType = when (this) {
    is Class<*> if isArray && !componentType.isPrimitive ->
        classType("kotlin/Array", nullable = platformAsNullable).also { type ->
            type.arguments += KmTypeProjection(KmVariance.INVARIANT, componentType.toKmJavaType(scope))
        }
    // a raw type, like the `List<Any?>` of `List`, of the type arguments of nullable `Any`, like kotlin-reflect describes it,
    // but of a bounded type parameter, like of the Enum of `Class<? extends Enum>`, on which kotlin-reflect fails, a star projection,
    // not to convert the bound of a recursive one, like the `E extends Enum<E>` of Enum, see javaClassType
    is Class<*> -> javaClassType(this, typeParameters.map { it.toRawTypeArgument() }).also { it.isNullable = platformAsNullable && !isPrimitive }
    is ParameterizedType -> javaClassType(rawType as Class<*>, actualTypeArguments.map { it.toKmJavaTypeProjection(scope) }).also { type ->
        type.isNullable = platformAsNullable
        // the type arguments of an outer class of an inner class, like `Outer<String>.Inner`, are kept in its outer type
        val owner = ownerType
        if (owner is ParameterizedType && !Modifier.isStatic((rawType as Class<*>).modifiers)) {
            type.outerType = owner.toKmJavaType(scope)
        }
    }
    is GenericArrayType -> classType("kotlin/Array", nullable = platformAsNullable).also { type ->
        type.arguments += KmTypeProjection(KmVariance.INVARIANT, genericComponentType.toKmJavaType(scope))
    }
    is TypeVariable<*> -> typeParameterType(scope.idOf(name)).also { it.isNullable = platformAsNullable }
    // a wildcard is a type argument only, see toKmJavaTypeProjection, as its bound otherwise
    is WildcardType -> (lowerBounds.firstOrNull() ?: upperBounds.firstOrNull() ?: Any::class.java).toKmJavaType(scope, platformAsNullable)
    else -> classType("kotlin/Any", nullable = platformAsNullable)
}

/**
 * The type of the Java [clazz], given the type [projections], see [toKmJavaType].
 */
private fun javaClassType(clazz: Class<*>, projections: List<KmTypeProjection>): KmType {
    val kClass = clazz.kotlin
    val type = classType(if (clazz == Void.TYPE) "kotlin/Unit" else reflectedClassName(kClass))
    val mutableName = (type.classifier as? KmClassifier.Class)?.name?.let { MUTABLE_COLLECTION_TYPES[it] }
    if (mutableName != null && isContravariantlyProjected(kClass, projections)) {
        type.classifier = KmClassifier.Class(mutableName)
    }
    type.arguments += projections
    return type
}

/**
 * The type argument of a raw type for this type variable, see [toKmJavaType]:
 * nullable `Any` of an unbounded one, or else a star projection.
 */
private fun TypeVariable<*>.toRawTypeArgument(): KmTypeProjection =
    if (bounds.all { it == Any::class.java }) KmTypeProjection(KmVariance.INVARIANT, classType("kotlin/Any", nullable = true)) else KmTypeProjection.STAR

/**
 * The projection of the Kotlin metadata converted from this type argument of Java, see [toKmJavaType],
 * like `*` of `?`, `out T` of `? extends T`, or `in T` of `? super T`.
 */
private fun Type.toKmJavaTypeProjection(scope: ReflectedTypeParameters): KmTypeProjection {
    if (this !is WildcardType) return KmTypeProjection(KmVariance.INVARIANT, toKmJavaType(scope))
    lowerBounds.firstOrNull()?.let { return KmTypeProjection(KmVariance.IN, it.toKmJavaType(scope)) }
    val upperBound = upperBounds.firstOrNull()?.takeUnless { it == Any::class.java } ?: return KmTypeProjection.STAR
    return KmTypeProjection(KmVariance.OUT, upperBound.toKmJavaType(scope))
}

/**
 * The nullability annotations of Java, by their descriptors, which the compiler reads, like kotlin-reflect,
 * telling a type nullable, like `@org.jetbrains.annotations.Nullable`, see [javaNullabilityOf]:
 * the `NULLABLE_ANNOTATIONS` of `JvmAnnotationNames` of the compiler, and the ones of JSpecify, which K2 reads as strict,
 * but not `javax.annotation.Nonnull` of JSR-305, which only warns by default.
 */
private val NULLABLE_ANNOTATIONS: Set<String> = setOf(
    "org.jetbrains.annotations.Nullable",
    "androidx.annotation.Nullable",
    "android.support.annotation.Nullable",
    "android.annotation.Nullable",
    "com.android.annotations.Nullable",
    "org.eclipse.jdt.annotation.Nullable",
    "org.checkerframework.checker.nullness.qual.Nullable",
    "org.checkerframework.checker.nullness.compatqual.NullableDecl",
    "org.checkerframework.checker.nullness.compatqual.NullableType",
    "javax.annotation.Nullable",
    "javax.annotation.CheckForNull",
    "edu.umd.cs.findbugs.annotations.CheckForNull",
    "edu.umd.cs.findbugs.annotations.Nullable",
    "edu.umd.cs.findbugs.annotations.PossiblyNull",
    "io.reactivex.annotations.Nullable",
    "io.reactivex.rxjava3.annotations.Nullable",
    "org.jspecify.annotations.Nullable",
    "org.jspecify.nullness.Nullable"
).mapTo(HashSet()) { "L" + it.replace('.', '/') + ";" }

/**
 * The nullability annotations of Java telling a type not nullable, like `@org.jetbrains.annotations.NotNull`,
 * like the [NULLABLE_ANNOTATIONS]: the `NOT_NULL_ANNOTATIONS` of `JvmAnnotationNames` of the compiler, and the ones of JSpecify.
 */
private val NOT_NULL_ANNOTATIONS: Set<String> = setOf(
    "org.jetbrains.annotations.NotNull",
    "edu.umd.cs.findbugs.annotations.NonNull",
    "androidx.annotation.NonNull",
    "android.support.annotation.NonNull",
    "android.annotation.NonNull",
    "com.android.annotations.NonNull",
    "org.eclipse.jdt.annotation.NonNull",
    "org.checkerframework.checker.nullness.qual.NonNull",
    "org.checkerframework.checker.nullness.compatqual.NonNullDecl",
    "org.checkerframework.checker.nullness.compatqual.NonNullType",
    "lombok.NonNull",
    "io.reactivex.annotations.NonNull",
    "io.reactivex.rxjava3.annotations.NonNull",
    "org.jspecify.annotations.NonNull",
    "org.jspecify.nullness.NonNull"
).mapTo(HashSet()) { "L" + it.replace('.', '/') + ";" }

/**
 * The nullability of the types of a Java method told by its annotations, see [NULLABLE_ANNOTATIONS],
 * of its [returnType], and of its [parameters], by their positions, `true` of a nullable type, `false` of a not nullable one,
 * none of a platform type.
 */
internal class JavaNullability(val returnType: Boolean?, val parameters: Map<Int, Boolean>)

/**
 * Marks the types of the [function], converted from the [method], nullable, or not, as its nullability annotations tell,
 * see [javaNullabilityOf], which kotlin-reflect, reading the annotations retained at runtime only, does not tell of most,
 * like of `@org.jetbrains.annotations.NotNull`, retained in the class file only, which the compiler reads.
 */
private fun enhanceJavaNullability(method: Method, function: KmFunction) {
    val nullability = javaNullabilityOf(method) ?: return
    nullability.returnType?.let { function.returnType.isNullable = it && !method.returnType.isPrimitive }
    nullability.parameters.forEach { (position, isNullable) ->
        val parameter = function.valueParameters.getOrNull(position) ?: return@forEach
        if (!method.parameterTypes[position].isPrimitive) parameter.type.isNullable = isNullable
    }
}

/**
 * The nullability of the [method] told by its annotations, see [JavaNullability], or `null` if none is annotated,
 * read from the class file, as the annotations which the compiler reads, like `@org.jetbrains.annotations.NotNull`,
 * are mostly not retained at runtime, the declaration annotations, and the type annotations of the types themselves,
 * like `@org.jspecify.annotations.Nullable String`, not of their type arguments, nor of the elements of an array.
 */
internal fun javaNullabilityOf(method: Method): JavaNullability? =
    javaNullabilities.get(method.declaringClass)[method.name + AsmType.getMethodDescriptor(method)]

/**
 * The nullabilities of the methods of a class, by their names and JVM descriptors, see [javaNullabilityOf],
 * read once per class, none of a JDK class, which is not annotated so.
 */
private val javaNullabilities = object : ClassValue<Map<String, JavaNullability>>() {
    override fun computeValue(type: Class<*>): Map<String, JavaNullability> =
        if (type.isJdkClass) emptyMap() else reflectedValue(default = emptyMap()) { readJavaNullabilities(type) }
}

private fun readJavaNullabilities(type: Class<*>): Map<String, JavaNullability> {
    val bytes = type.getResourceAsStream("/" + AsmType.getInternalName(type) + ".class")?.use { it.readBytes() } ?: return emptyMap()
    val nullabilities = HashMap<String, JavaNullability>()
    fun nullabilityOf(descriptor: String): Boolean? = when (descriptor) {
        in NULLABLE_ANNOTATIONS -> true
        in NOT_NULL_ANNOTATIONS -> false
        else -> null
    }
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
            var returnType: Boolean? = null
            val parameters = HashMap<Int, Boolean>()

            override fun visitAnnotation(annotation: String, visible: Boolean): AnnotationVisitor? {
                nullabilityOf(annotation)?.let { returnType = it }
                return null
            }

            override fun visitParameterAnnotation(parameter: Int, annotation: String, visible: Boolean): AnnotationVisitor? {
                nullabilityOf(annotation)?.let { parameters[parameter] = it }
                return null
            }

            override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, annotation: String, visible: Boolean): AnnotationVisitor? {
                // the annotation of the type itself, not of a type argument, nor of an array element
                val nullability = nullabilityOf(annotation)?.takeIf { typePath == null } ?: return null
                val reference = TypeReference(typeRef)
                when (reference.sort) {
                    TypeReference.METHOD_RETURN -> returnType = nullability
                    TypeReference.METHOD_FORMAL_PARAMETER -> parameters[reference.formalParameterIndex] = nullability
                }
                return null
            }

            override fun visitEnd() {
                if (returnType != null || parameters.isNotEmpty()) nullabilities[name + descriptor] = JavaNullability(returnType, parameters)
            }
        }
    }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    return nullabilities
}
