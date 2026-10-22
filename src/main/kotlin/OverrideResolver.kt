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

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.util.concurrent.ConcurrentHashMap
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmVariance
import kotlin.metadata.Visibility
import kotlin.metadata.isDefinitelyNonNull
import kotlin.metadata.isInner
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility
import java.lang.reflect.Type as JavaType
import org.objectweb.asm.Type as AsmType

/**
 * Tells whether a member of a Kotlin class overrides a member of its supertypes.
 *
 * The members are compared at the Kotlin level, like the Kotlin compiler does:
 * by name, receiver and value parameter types, with the type arguments of the supertypes
 * substituted along the whole supertype hierarchy, and read from the Kotlin metadata
 * (from the `.kotlin_builtins` declarations for the built-in types, like `Any` or `MutableList`).
 * Thanks to that the JVM representation, like the value class name mangling,
 * the `Continuation` parameter of suspend functions, the getter names of properties,
 * or the JVM names of the built-in members, like `charAt` for `CharSequence.get`, are irrelevant.
 *
 * Only Java supertypes are reflected, and their methods are matched by the erasure of the parameter types,
 * as the nullability and the mutability of their types are not known in Kotlin,
 * except for a primitive type, which is not nullable.
 * A Java type mapped to a Kotlin built-in type, like `java.util.List`, is replaced with the built-in type.
 *
 * The overridable members of the supertypes are collected once per class.
 *
 * @param loadClass loads a class by its fully qualified Kotlin name.
 * @param readKmClass reads the Kotlin metadata of a class, if it is a Kotlin class.
 * @param builtinClasses the declarations of the Kotlin built-in types, by their Kotlin class name, like `kotlin/collections/List`.
 * @param builtinTypes the JVM classes of the Kotlin built-in types, by their fully qualified Kotlin name.
 */
internal class OverrideResolver(
    private val loadClass: (String) -> Class<*>?,
    private val readKmClass: (Class<*>) -> KmClass?,
    private val builtinClasses: () -> Map<String, KmClass>,
    builtinTypes: Map<String, kotlin.reflect.KClass<*>>
) {

    /**
     * A type of a member parameter, resolved in the context of the class whose members are checked.
     */
    private sealed interface MemberType

    /**
     * @param nullable `null` for a type coming from Java, which can be seen in Kotlin as both nullable and not.
     * @param isJavaPrimitive whether this is a Java primitive type, seen in Kotlin as not nullable,
     *   unlike a Kotlin type substituted for a Java type variable, also not nullable, but flexible in Kotlin.
     */
    private data class ClassType(
        val name: String,
        val arguments: List<TypeArgument> = emptyList(),
        val nullable: Boolean? = false,
        val outer: ClassType? = null,
        val isJavaPrimitive: Boolean = false
    ) : MemberType {
        val isFlexible: Boolean get() = nullable == null
    }

    /**
     * A type parameter, of the checked class (`C<id>`), or of the checked function (`F<index>`).
     *
     * @param erasure the name of the class this type parameter is erased to, or `null` if unknown.
     */
    private data class TypeParameterType(
        val key: String,
        val nullable: Boolean,
        val erasure: String?
    ) : MemberType

    /**
     * A type which cannot be resolved, like a type argument of a raw Java type,
     * therefore matching any other type.
     */
    private data object UnknownType : MemberType

    /**
     * @param variance `null` for a star projection.
     */
    private data class TypeArgument(val variance: KmVariance?, val type: MemberType)

    /**
     * A member of a supertype, which can be overridden.
     *
     * @param isJava whether the member is a Java method, matched by the erasure of its parameter types.
     */
    private class Overridable(
        val receiver: MemberType?,
        val parameters: List<MemberType>,
        val typeParameterCount: Int,
        val isJava: Boolean
    )

    /**
     * @param ownerJvmMethods reads the JVM methods of the owner class, as names followed by descriptors,
     *   or `null` if its class files cannot be read; only needed to tell a shrunk member, so read lazily.
     */
    private class Overridables(
        val substitution: Map<Int, MemberType>,
        val functions: Map<String, List<Overridable>>,
        val properties: Map<String, List<Overridable>>,
        ownerJvmMethods: () -> Set<String>?
    ) {
        val ownerJvmMethods: Set<String>? by lazy(ownerJvmMethods)
    }

    /**
     * A declaration of a supertype: Kotlin, Java, or both for a Kotlin built-in type
     * whose JVM type contributes Java members, like `Throwable.fillInStackTrace`.
     */
    private class Declaration(
        val kotlin: KmClass?,
        val java: Class<*>?,
        val hiddenJavaMethods: Set<String> = emptySet()
    )

    private val overridablesCache = ConcurrentHashMap<String, Overridables>()

    private val declarationCache = ConcurrentHashMap<String, Declaration>()

    /**
     * The Kotlin class names of the built-in types, like `kotlin/collections/Map.Entry`,
     * mapped to their JVM classes.
     */
    private val builtinJavaClasses: Map<String, Class<*>> = builtinTypes.entries
        // Nothing is represented by java.lang.Void, which is not Nothing when used in Java
        .filter { (name, kClass) -> !kClass.java.isArray && kClass.java.name != name && name != "kotlin.Nothing" }
        .associate { (name, kClass) -> kotlinClassName(name) to kClass.java }

    /**
     * JVM class names mapped to the Kotlin class names of the built-in types,
     * the mutable collection types are chosen, as Java collections are mutable.
     */
    private val javaToKotlinNames: Map<String, String> = buildMap {
        builtinJavaClasses.entries
            .sortedBy { (name, _) -> name.substringAfterLast('/').startsWith("Mutable") }
            .forEach { (name, javaClass) -> put(javaClass.name, name) }
        primitiveNames.forEach { (javaName, kotlinName) -> put(javaName, kotlinName) }
    }

    fun isOverride(owner: KmClass, function: KmFunction): Boolean {
        val overridables = overridables(owner)
        val candidates = overridables.functions[function.name] ?: return false
        val typeParameters = function.typeParameters
        val receiver = function.receiverParameterType?.let {
            kotlinType(it, overridables.substitution, typeParameters)
        }
        val parameters = function.valueParameters.map {
            kotlinType(it.type, overridables.substitution, typeParameters)
        }
        return candidates.any { it.matches(receiver, parameters, typeParameters.size, function.isSuspend) } ||
            overridables.isShrunk(function.signature)
    }

    fun isOverride(owner: KmClass, property: KmProperty): Boolean {
        val overridables = overridables(owner)
        val typeParameters = property.typeParameters
        val receiver = property.receiverParameterType?.let {
            kotlinType(it, overridables.substitution, typeParameters)
        }
        val overridesProperty = overridables.properties[property.name].orEmpty().any {
            it.matches(receiver, emptyList(), typeParameters.size, isSuspend = false)
        }
        // a property of a Kotlin supertype without metadata is known only by its JVM getter,
        // which a private or a @JvmField property does not have, and which might be renamed or mangled
        val getter = property.getterSignature?.takeIf { property.visibility.isOverridable }
        val overridesGetter = getter != null && overridables.functions[getter.name].orEmpty().any {
            it.isJava && it.matches(receiver, emptyList(), typeParameters.size, isSuspend = false)
        }
        return overridesProperty || overridesGetter ||
            (property.name in overridables.properties && overridables.isShrunk(property.getterSignature))
    }

    /**
     * Whether the JVM method of a member, declared in the metadata, has been removed from the class file of its owner,
     * like by R8, so that it cannot be told from an overload of a supertype member,
     * which is then assumed to be overridden, as one with the same name is known.
     * The caller checks that a supertype member of the same name exists.
     * A member whose owner class files cannot be read is not considered shrunk.
     */
    private fun Overridables.isShrunk(signature: JvmMethodSignature?): Boolean =
        signature != null && ownerJvmMethods?.let { "${signature.name}${signature.descriptor}" !in it } == true

    private fun Overridable.matches(
        receiver: MemberType?,
        parameters: List<MemberType>,
        typeParameterCount: Int,
        isSuspend: Boolean
    ): Boolean {
        if (this.typeParameterCount != typeParameterCount) return false
        if (isJava) {
            // on the JVM, the receiver is the first parameter, and a suspend function takes a trailing Continuation
            val jvmParameters = listOfNotNull(receiver) + parameters +
                if (isSuspend) listOf(ClassType("kotlin/coroutines/Continuation")) else emptyList()
            return this.parameters.size == jvmParameters.size &&
                this.parameters.zip(jvmParameters).all { (overridden, overriding) -> erasuresMatch(overridden, overriding) }
        }
        if ((this.receiver == null) != (receiver == null)) return false
        if (this.parameters.size != parameters.size) return false
        return (this.receiver == null || typesMatch(this.receiver, receiver!!)) &&
            this.parameters.zip(parameters).all { (overridden, overriding) -> typesMatch(overridden, overriding) }
    }

    private fun overridables(owner: KmClass): Overridables =
        overridablesCache[owner.name] ?: collectOverridables(owner).also {
            overridablesCache.putIfAbsent(owner.name, it)
        }

    /**
     * Walks the whole supertype hierarchy of the [owner] once,
     * collecting the overridable members with the type arguments substituted.
     */
    private fun collectOverridables(owner: KmClass): Overridables {
        val ownerSubstitution = ownerSubstitution(owner)
        val ownerPackage = owner.name.substringBeforeLast('/', "").replace('/', '.')
        val functions = mutableMapOf<String, MutableList<Overridable>>()
        val properties = mutableMapOf<String, MutableList<Overridable>>()
        val pending = ArrayDeque(owner.supertypes.mapNotNull { kotlinType(it, ownerSubstitution) as? ClassType })
        // every class and interface is a subtype of Any, also when it is reached only through Java interfaces
        pending += ClassType("kotlin/Any")
        val visited = mutableSetOf<String>()

        while (pending.isNotEmpty()) {
            val supertype = pending.removeFirst()
            if (!visited.add(supertype.name)) continue

            // the function types have no declarations to read
            functionTypeRegex.matchEntire(supertype.name)?.let { match ->
                functions.getOrPut("invoke") { mutableListOf() } += Overridable(
                    receiver = null,
                    parameters = supertype.arguments.dropLast(1).map { it.type },
                    typeParameterCount = 0,
                    isJava = false
                )
                if (match.groupValues[1].isNotEmpty()) {
                    // the members of KFunction
                    pending += ClassType("kotlin/reflect/KFunction", listOfNotNull(supertype.arguments.lastOrNull()))
                }
                continue
            }

            val declaration = declaration(supertype.name)

            declaration.kotlin?.let { kmClass ->
                val substitution = supertypeSubstitution(kmClass, supertype)
                kmClass.functions.filter { it.visibility.isOverridable }.forEach { function ->
                    functions.getOrPut(function.name) { mutableListOf() } += Overridable(
                        receiver = function.receiverParameterType?.let {
                            kotlinType(it, substitution, function.typeParameters)
                        },
                        parameters = function.valueParameters.map {
                            kotlinType(it.type, substitution, function.typeParameters)
                        },
                        typeParameterCount = function.typeParameters.size,
                        isJava = false
                    )
                }
                kmClass.properties.filter { it.visibility.isOverridable }.forEach { property ->
                    properties.getOrPut(property.name) { mutableListOf() } += Overridable(
                        receiver = property.receiverParameterType?.let {
                            kotlinType(it, substitution, property.typeParameters)
                        },
                        parameters = emptyList(),
                        typeParameterCount = property.typeParameters.size,
                        isJava = false
                    )
                }
                kmClass.supertypes.forEach { type ->
                    (kotlinType(type, substitution) as? ClassType)?.let { pending += it }
                }
            }

            declaration.java?.let { javaClass ->
                val substitution = javaClass.typeParameters.withIndex().associate { (index, typeVariable) ->
                    typeVariable.name to (supertype.arguments.getOrNull(index)?.type ?: UnknownType)
                }
                javaMethods(javaClass, ownerPackage, substitution).forEach { (name, method) ->
                    if (name !in declaration.hiddenJavaMethods) {
                        functions.getOrPut(name) { mutableListOf() } += method
                    }
                }
                // the supertypes of a built-in type are known from its Kotlin declaration
                if (declaration.kotlin == null) {
                    pending += javaSupertypes(javaClass, substitution)
                }
            }
        }

        return Overridables(ownerSubstitution, functions, properties) {
            loadClass(owner.name.replace('/', '.'))?.let { jvmMethods(it) }
        }
    }

    /**
     * The JVM methods of a Kotlin class, as names followed by descriptors,
     * including the members of an interface relocated to its `DefaultImpls` class,
     * like with `-jvm-default=disable`, as if they were declared in the interface,
     * or `null` if the class files cannot be read.
     */
    private fun jvmMethods(javaClass: Class<*>): Set<String>? {
        val internalName = javaClass.name.replace('.', '/')
        val methods = mutableSetOf<String>()
        val isRead = readMethods(classBytes(javaClass.classLoader, internalName) ?: return null) { _, name, descriptor, _ ->
            methods += name + descriptor
        }
        if (!isRead) return null
        if (javaClass.isInterface) {
            // the static method of DefaultImpls takes the interface instance as the first parameter
            val instanceParameter = "(L$internalName;"
            val defaultImpls = classBytes(javaClass.classLoader, "$internalName\$DefaultImpls")
            if (defaultImpls != null) {
                val isDefaultImplsRead = readMethods(defaultImpls) { access, name, descriptor, _ ->
                    if (access and Opcodes.ACC_STATIC != 0 && descriptor.startsWith(instanceParameter)) {
                        methods += name + "(" + descriptor.removePrefix(instanceParameter)
                    }
                }
                if (!isDefaultImplsRead) return null
            }
        }
        return methods
    }

    /**
     * Reads the methods of a class file, passing their access flags, names, descriptors and generic signatures
     * to the [visit] function.
     *
     * @return `false` if the class file cannot be parsed, like when it is malformed, or too new for ASM.
     */
    private fun readMethods(
        bytes: ByteArray,
        visit: (access: Int, name: String, descriptor: String, signature: String?) -> Unit
    ): Boolean = try {
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                visit(access, name, descriptor, signature)
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        true
    } catch (_: Exception) {
        false
    }

    private val Visibility.isOverridable: Boolean
        get() = this != Visibility.PRIVATE && this != Visibility.PRIVATE_TO_THIS && this != Visibility.LOCAL

    /**
     * The type parameters of the [owner], and of its outer classes if it is an inner class,
     * which stay type parameters, as the members are checked in the context of the [owner].
     */
    private fun ownerSubstitution(owner: KmClass): Map<Int, MemberType> = buildMap {
        val typeParameters = generateSequence(owner) { kmClass ->
            if (kmClass.isInner) declaration(outerClassName(kmClass.name)).kotlin else null
        }.flatMap { it.typeParameters }.toList()
        val byId = typeParameters.associateBy { it.id }
        typeParameters.forEach { typeParameter ->
            put(typeParameter.id, TypeParameterType("C${typeParameter.id}", false, erasure(typeParameter, byId)))
        }
    }

    /**
     * The type parameters of the [kmClass], and of its outer classes if it is an inner class,
     * mapped to the type arguments of the [supertype].
     */
    private fun supertypeSubstitution(kmClass: KmClass, supertype: ClassType): Map<Int, MemberType> = buildMap {
        var declaration: KmClass? = kmClass
        var type: ClassType? = supertype
        while (declaration != null) {
            declaration.typeParameters.forEachIndexed { index, typeParameter ->
                put(typeParameter.id, type?.arguments?.getOrNull(index)?.type ?: UnknownType)
            }
            if (!declaration.isInner) break
            declaration = declaration(outerClassName(declaration.name)).kotlin
            type = type?.outer
        }
    }

    private fun outerClassName(name: String): String = name.substringBeforeLast('.')

    private fun erasure(typeParameter: KmTypeParameter, typeParameters: Map<Int, KmTypeParameter>): String? {
        val bound = typeParameter.upperBounds.firstOrNull() ?: return "kotlin/Any"
        return when (val classifier = bound.classifier) {
            is KmClassifier.Class -> if (classifier.name == "kotlin/Array") null else normalizedName(classifier.name)
            is KmClassifier.TypeParameter -> typeParameters[classifier.id]
                ?.takeIf { it !== typeParameter }
                ?.let { erasure(it, typeParameters - typeParameter.id) }
            is KmClassifier.TypeAlias -> null
        }
    }

    /**
     * Resolves a Kotlin [type] of a member, substituting the type parameters of its class.
     *
     * @param typeParameters the type parameters of the member itself, matched by their position.
     */
    private fun kotlinType(
        type: KmType,
        substitution: Map<Int, MemberType>,
        typeParameters: List<KmTypeParameter> = emptyList()
    ): MemberType = when (val classifier = type.classifier) {
        is KmClassifier.Class -> suspendFunctionType(type, substitution, typeParameters) ?: ClassType(
            name = classifier.name,
            arguments = type.arguments.map { projection ->
                val argumentType = projection.type
                if (argumentType == null) {
                    TypeArgument(null, UnknownType)
                } else {
                    TypeArgument(projection.variance, kotlinType(argumentType, substitution, typeParameters))
                }
            },
            nullable = type.isNullable,
            outer = type.outerType?.let { kotlinType(it, substitution, typeParameters) as? ClassType }
        )
        is KmClassifier.TypeParameter -> {
            val index = typeParameters.indexOfFirst { it.id == classifier.id }
            if (index >= 0) {
                val byId = typeParameters.associateBy { it.id }
                TypeParameterType("F$index", type.isNullable, erasure(typeParameters[index], byId))
            } else {
                substitution[classifier.id]?.withNullability(type) ?: UnknownType
            }
        }
        // the metadata refers to the expanded type, an alias is not expected here
        is KmClassifier.TypeAlias -> UnknownType
    }

    /**
     * A suspend function type, like `suspend (String) -> Int`, stored in the metadata
     * as `Function2<String, Continuation<Int>, Any?>`, resolved as `SuspendFunction1<String, Int>`,
     * so that its arguments are the parameter types and the return type, like of a function type.
     */
    private fun suspendFunctionType(
        type: KmType,
        substitution: Map<Int, MemberType>,
        typeParameters: List<KmTypeParameter>
    ): ClassType? {
        if (!type.isSuspend) return null
        val name = (type.classifier as? KmClassifier.Class)?.name ?: return null
        val match = functionTypeRegex.matchEntire(name) ?: return null
        val arguments = type.arguments.map { it.type ?: return null }
        if (arguments.size < 2) return null
        val returnType = arguments[arguments.size - 2].arguments.singleOrNull()?.type ?: return null
        return ClassType(
            name = "kotlin/${match.groupValues[1]}SuspendFunction${arguments.size - 2}",
            arguments = (arguments.dropLast(2) + returnType).map {
                TypeArgument(KmVariance.INVARIANT, kotlinType(it, substitution, typeParameters))
            },
            nullable = type.isNullable
        )
    }

    /**
     * A substituted type argument, nullable when it is used as nullable, like `T?`.
     */
    private fun MemberType.withNullability(use: KmType): MemberType = when (this) {
        is ClassType -> when {
            use.isDefinitelyNonNull -> copy(nullable = false)
            use.isNullable -> copy(nullable = true)
            else -> this
        }
        is TypeParameterType -> when {
            use.isDefinitelyNonNull -> copy(nullable = false)
            use.isNullable -> copy(nullable = true)
            else -> this
        }
        UnknownType -> this
    }

    /**
     * Whether the types are the same, an unknown type matching any other one.
     * The nullability, the variance and the mutability of a collection type is not known for a type coming from Java.
     */
    private fun typesMatch(a: MemberType, b: MemberType): Boolean = when {
        a == UnknownType || b == UnknownType -> true
        a is ClassType && b is ClassType -> {
            val flexible = a.isFlexible || b.isFlexible
            val namesMatch = if (flexible) normalizedName(a.name) == normalizedName(b.name) else a.name == b.name
            namesMatch &&
                (flexible || a.nullable == b.nullable) &&
                a.arguments.size == b.arguments.size &&
                a.arguments.zip(b.arguments).withIndex().all { (index, arguments) ->
                    val (x, y) = arguments
                    (x.type == UnknownType || y.type == UnknownType) ||
                        ((flexible || variancesMatch(a.name, index, x.variance, y.variance)) && typesMatch(x.type, y.type))
                }
        }
        a is TypeParameterType && b is TypeParameterType -> a.key == b.key && a.nullable == b.nullable
        else -> false
    }

    /**
     * Whether the use-site variances of a type argument are the same,
     * a redundant one, like `List<out String>`, being the same as none.
     */
    private fun variancesMatch(className: String, index: Int, a: KmVariance?, b: KmVariance?): Boolean {
        if (a == b) return true
        if (a == null || b == null) return false
        val declared = declaration(className).kotlin?.typeParameters?.getOrNull(index)?.variance
        fun effective(variance: KmVariance) = if (variance == declared) KmVariance.INVARIANT else variance
        return effective(a) == effective(b)
    }

    /**
     * Whether the type of a Java method parameter, [overridden], has the same erasure as the [overriding] one,
     * a Java primitive type matching only a non-nullable type, as a nullable one is boxed on the JVM.
     */
    private fun erasuresMatch(overridden: MemberType, overriding: MemberType): Boolean {
        if (overridden is ClassType && overridden.isJavaPrimitive && overriding.isNullable) return false
        val erasureA = erasure(overridden) ?: return true
        val erasureB = erasure(overriding) ?: return true
        return erasureA == erasureB
    }

    private val MemberType.isNullable: Boolean
        get() = when (this) {
            is ClassType -> nullable == true
            is TypeParameterType -> nullable
            UnknownType -> false
        }

    /**
     * The name of the class the [type] is erased to on the JVM, or `null` if unknown.
     */
    private fun erasure(type: MemberType): String? = when (type) {
        is ClassType -> if (type.name == "kotlin/Array") {
            type.arguments.singleOrNull()?.type?.let { erasure(it) }?.let { "[$it" }
        } else {
            functionTypeErasure(type.name) ?: normalizedName(type.name)
        }
        is TypeParameterType -> type.erasure
        UnknownType -> null
    }

    /**
     * The JVM erasure of a function type: `kotlin/reflect/KFunction` for the `KFunctionN` types,
     * and `kotlin/FunctionN+1` for a suspend function type resolved by [suspendFunctionType],
     * as it takes a trailing `Continuation`.
     */
    private fun functionTypeErasure(name: String): String? {
        val match = functionTypeRegex.matchEntire(name) ?: return null
        val (reflect, suspend, arity) = match.destructured
        return when {
            reflect.isNotEmpty() -> "kotlin/reflect/KFunction"
            suspend.isNotEmpty() -> "kotlin/Function${arity.toInt() + 1}"
            else -> null
        }
    }

    /**
     * The name of a read-only collection type for a mutable one, as Java does not tell them apart,
     * and the name of a function type for its JVM interface, like `kotlin/jvm/functions/Function1`.
     */
    private fun normalizedName(name: String): String = when {
        name.startsWith("kotlin/collections/") -> name.replace("/Mutable", "/").replace(".Mutable", ".")
        name.startsWith("kotlin/jvm/functions/Function") -> "kotlin/" + name.removePrefix("kotlin/jvm/functions/")
        else -> name
    }

    private fun declaration(name: String): Declaration =
        declarationCache[name] ?: findDeclaration(name).also { declarationCache.putIfAbsent(name, it) }

    private fun findDeclaration(name: String): Declaration {
        val javaClass = loadClass(name.replace('/', '.'))
        // the built-in declaration of a type having its own class file, like KClass, is incomplete
        javaClass?.let { readKmClass(it) }?.let { return Declaration(it, null) }
        builtinClasses()[name]?.let { kmClass ->
            // members of Any are fully described by its Kotlin declaration, unlike the JVM members of Object
            val builtinJavaClass = if (name == "kotlin/Any") null else builtinJavaClasses[name]
            val hiddenJavaMethods = when {
                !name.startsWith("kotlin/collections/") -> emptySet()
                name.substringAfterLast('/').contains("Mutable") -> hiddenCollectionMethods
                else -> hiddenCollectionMethods + mutatingCollectionMethods
            }
            return Declaration(kmClass, builtinJavaClass, hiddenJavaMethods)
        }
        return Declaration(null, javaClass)
    }

    /**
     * The overridable methods of a Java class, by their names.
     */
    private fun javaMethods(
        javaClass: Class<*>,
        ownerPackage: String,
        substitution: Map<String, MemberType>
    ): List<Pair<String, Overridable>> {
        val methods = try {
            javaClass.declaredMethods
        } catch (_: LinkageError) {
            // a method signature refers to a class missing from the classpath
            return asmMethods(javaClass, ownerPackage, substitution)
        }
        val overridables = methods
            .filter { method ->
                val modifiers = method.modifiers
                !method.isSynthetic && !method.isBridge &&
                    !Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers) && !Modifier.isPrivate(modifiers) &&
                    (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers) ||
                        javaClass.packageName == ownerPackage)
            }
        return try {
            overridables.map { method -> method.name to javaOverridable(method, substitution) }
        } catch (_: TypeNotPresentException) {
            // a generic type, like a wildcard or a type variable bound, refers to a class missing from the classpath
            asmMethods(javaClass, ownerPackage, substitution)
        } catch (_: java.lang.reflect.MalformedParameterizedTypeException) {
            asmMethods(javaClass, ownerPackage, substitution)
        } catch (_: LinkageError) {
            asmMethods(javaClass, ownerPackage, substitution)
        }
    }

    /**
     * The generic types are resolved lazily, so this might throw when they refer to a class missing from the classpath.
     */
    private fun javaOverridable(method: Method, substitution: Map<String, MemberType>): Overridable =
        Overridable(
            receiver = null,
            parameters = method.genericParameterTypes.map { javaType(it, substitution, method) },
            typeParameterCount = method.typeParameters.size,
            isJava = true
        )

    private fun javaSupertypes(javaClass: Class<*>, substitution: Map<String, MemberType>): List<ClassType> {
        val rawSupertypes = listOfNotNull(javaClass.superclass) + javaClass.interfaces
        val supertypes = try {
            (listOfNotNull(javaClass.genericSuperclass) + javaClass.genericInterfaces)
                .map { javaType(it, substitution, method = null) }
        } catch (_: TypeNotPresentException) {
            null
        } catch (_: java.lang.reflect.MalformedParameterizedTypeException) {
            null
        } catch (_: LinkageError) {
            null
        } ?: rawSupertypes.map { javaClassType(it) }
        return supertypes.filterIsInstance<ClassType>()
    }

    /**
     * Resolves a Java [type], substituting the type variables of its class.
     *
     * @param method the method declaring the [type], whose type variables are erased.
     */
    private fun javaType(type: JavaType, substitution: Map<String, MemberType>, method: Method?): MemberType =
        when (type) {
            is Class<*> -> javaClassType(type)
            is ParameterizedType -> {
                val raw = javaClassType(type.rawType as Class<*>)
                if (raw is ClassType) {
                    raw.copy(
                        arguments = type.actualTypeArguments.map { argument ->
                            when (argument) {
                                is WildcardType -> when {
                                    argument.lowerBounds.isNotEmpty() ->
                                        TypeArgument(KmVariance.IN, javaType(argument.lowerBounds[0], substitution, method))
                                    argument.upperBounds.firstOrNull().let { it == null || it == Any::class.java } ->
                                        TypeArgument(null, UnknownType)
                                    else ->
                                        TypeArgument(KmVariance.OUT, javaType(argument.upperBounds[0], substitution, method))
                                }
                                else -> TypeArgument(KmVariance.INVARIANT, javaType(argument, substitution, method))
                            }
                        },
                        outer = (type.ownerType as? ParameterizedType)?.let { javaType(it, substitution, method) as? ClassType }
                    )
                } else raw
            }
            is GenericArrayType -> ClassType(
                name = "kotlin/Array",
                arguments = listOf(TypeArgument(KmVariance.INVARIANT, javaType(type.genericComponentType, substitution, method))),
                nullable = null
            )
            is TypeVariable<*> -> {
                val declaration = type.genericDeclaration
                if (declaration is Class<*>) {
                    substitution[type.name] ?: UnknownType
                } else {
                    // a type variable of the method, known only by its erasure
                    val erasure = javaErasure(type)?.let { (javaClassType(it) as? ClassType)?.let { erasure(it) } }
                    val index = (declaration as? Method)?.typeParameters?.indexOfFirst { it.name == type.name } ?: -1
                    TypeParameterType("F$index", false, erasure)
                }
            }
            else -> UnknownType
        }

    /**
     * The class a Java [type] is erased to, the erasure of the first bound for a type variable.
     */
    private fun javaErasure(type: JavaType): Class<*>? = when (type) {
        is Class<*> -> type
        is ParameterizedType -> type.rawType as? Class<*>
        is TypeVariable<*> -> type.bounds.firstOrNull()?.let { javaErasure(it) }
        else -> null
    }

    private fun javaClassType(javaClass: Class<*>): MemberType = when {
        javaClass.isArray -> {
            val component = javaClass.componentType
            if (component.isPrimitive) {
                ClassType("kotlin/${primitiveNames.getValue(component.name).substringAfter('/')}Array", nullable = null)
            } else {
                ClassType(
                    name = "kotlin/Array",
                    arguments = listOf(TypeArgument(KmVariance.INVARIANT, javaClassType(component))),
                    nullable = null
                )
            }
        }
        else -> ClassType(
            name = javaToKotlinNames[javaClass.name] ?: kotlinClassName(javaClass),
            // the type arguments of a raw type are unknown
            arguments = javaClass.typeParameters.map { TypeArgument(null, UnknownType) },
            nullable = if (javaClass.isPrimitive) false else null,
            isJavaPrimitive = javaClass.isPrimitive
        )
    }

    /**
     * The overridable methods of a Java class, which cannot be reflected,
     * read from the class file, matched by the erasure of the parameter types.
     */
    private fun asmMethods(
        javaClass: Class<*>,
        ownerPackage: String,
        substitution: Map<String, MemberType>
    ): List<Pair<String, Overridable>> {
        val bytes = classBytes(javaClass.classLoader, javaClass.name.replace('.', '/')) ?: return emptyList()
        val methods = mutableListOf<Pair<String, Overridable>>()
        val isRead = readMethods(bytes) { access, name, descriptor, signature ->
            val isOverridable = access and (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_PRIVATE or
                Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) == 0 &&
                (access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 || javaClass.packageName == ownerPackage) &&
                !name.startsWith("<")
            if (!isOverridable) return@readMethods
            val generic = signature?.let { GenericMethodSignature(it) }
            val parameters = AsmType.getArgumentTypes(descriptor).mapIndexed { index, parameterType ->
                val typeVariable = generic?.typeVariableParameters?.get(index)
                when {
                    typeVariable == null -> asmType(parameterType)
                    // a type variable of the method is known only by its erasure
                    typeVariable in generic.typeParameters -> TypeParameterType(
                        "F${generic.typeParameters.indexOf(typeVariable)}",
                        false,
                        erasure(asmType(parameterType))
                    )
                    else -> substitution[typeVariable] ?: UnknownType
                }
            }
            methods += name to Overridable(null, parameters, generic?.typeParameters?.size ?: 0, isJava = true)
        }
        // the methods of a class file which cannot be parsed are unknown
        return if (isRead) methods else emptyList()
    }

    /**
     * @param internalName the JVM internal name of the class, like `java/util/Map$Entry`.
     */
    private fun classBytes(classLoader: ClassLoader?, internalName: String): ByteArray? = try {
        (classLoader ?: ClassLoader.getSystemClassLoader())
            .getResourceAsStream("$internalName.class")
            ?.use { it.readBytes() }
    } catch (_: java.io.IOException) {
        null
    }

    private fun asmType(type: AsmType): MemberType = when (type.sort) {
        AsmType.ARRAY -> if (type.dimensions == 1 && type.elementType.sort != AsmType.OBJECT) {
            ClassType("kotlin/${primitiveNames.getValue(type.elementType.className).substringAfter('/')}Array", nullable = null)
        } else {
            ClassType(
                name = "kotlin/Array",
                arguments = listOf(TypeArgument(KmVariance.INVARIANT, asmType(AsmType.getType(type.descriptor.substring(1))))),
                nullable = null
            )
        }
        AsmType.OBJECT -> {
            val binaryName = type.className
            ClassType(
                javaToKotlinNames[binaryName] ?: kotlinClassName(
                    binaryName.substringBeforeLast('.', ""),
                    binaryName.substringAfterLast('.')
                ),
                nullable = null
            )
        }
        else -> ClassType(primitiveNames.getValue(type.className), nullable = false, isJavaPrimitive = true)
    }

    /**
     * The type parameters of a generic method signature,
     * and the type variables of the parameters by their indices, for the parameters whose type is a type variable.
     */
    private class GenericMethodSignature(signature: String) {

        val typeParameters = mutableListOf<String>()

        val typeVariableParameters = mutableMapOf<Int, String>()

        init {
            SignatureReader(signature).accept(object : SignatureVisitor(Opcodes.ASM9) {

                private var parameterIndex = -1

                private var inParameter = false

                override fun visitFormalTypeParameter(name: String) {
                    typeParameters += name
                }

                override fun visitParameterType(): SignatureVisitor {
                    parameterIndex++
                    inParameter = true
                    return this
                }

                override fun visitReturnType(): SignatureVisitor {
                    inParameter = false
                    return this
                }

                override fun visitTypeVariable(name: String) {
                    if (inParameter) typeVariableParameters[parameterIndex] = name
                    inParameter = false
                }

                override fun visitClassType(name: String) {
                    inParameter = false
                }

                override fun visitBaseType(descriptor: Char) {
                    inParameter = false
                }

                override fun visitArrayType(): SignatureVisitor {
                    inParameter = false
                    return this
                }

            })
        }

    }

    private companion object {

        /**
         * The JVM methods of the collection interfaces not visible in Kotlin:
         * `toArray`, and the members of the JDK 21 sequenced collections,
         * which the standard library, compiled against JDK 8, declares without overriding them.
         */
        val hiddenCollectionMethods = setOf(
            "toArray", "addFirst", "addLast", "getFirst", "getLast", "removeFirst", "removeLast", "reversed"
        )

        /**
         * The JVM methods of the collection interfaces visible only on the mutable Kotlin collection types.
         */
        val mutatingCollectionMethods = setOf(
            "add", "addAll", "remove", "removeAll", "retainAll", "clear", "removeIf", "replaceAll", "sort", "set",
            "put", "putAll", "putIfAbsent", "replace", "compute", "computeIfAbsent", "computeIfPresent", "merge",
            "setValue"
        )

        /**
         * The function types, like `kotlin/Function1`, `kotlin/reflect/KFunction1`,
         * and the suspend function types as resolved by [suspendFunctionType].
         */
        val functionTypeRegex = Regex("kotlin/(?:reflect/)?(K?)(Suspend)?Function(\\d+)")

        val primitiveNames = mapOf(
            "boolean" to "kotlin/Boolean",
            "char" to "kotlin/Char",
            "byte" to "kotlin/Byte",
            "short" to "kotlin/Short",
            "int" to "kotlin/Int",
            "float" to "kotlin/Float",
            "long" to "kotlin/Long",
            "double" to "kotlin/Double",
            "void" to "kotlin/Unit"
        )

        /**
         * The Kotlin class name, like `kotlin/collections/Map.Entry`, of a fully qualified Kotlin name of a built-in type,
         * whose package segments start with a lowercase letter.
         */
        fun kotlinClassName(qualifiedName: String): String {
            val segments = qualifiedName.split('.')
            val packageSize = segments.indexOfFirst { it.first().isUpperCase() }.coerceAtLeast(0)
            return kotlinClassName(
                segments.take(packageSize).joinToString("."),
                segments.drop(packageSize).joinToString(".")
            )
        }

        fun kotlinClassName(javaClass: Class<*>): String =
            kotlinClassName(javaClass.packageName, javaClass.name.removePrefix("${javaClass.packageName}."))

        /**
         * The Kotlin class name, like `java/util/Map.Entry`, of the [packageName] and the binary [name] of a class within it.
         */
        fun kotlinClassName(packageName: String, name: String): String {
            val simpleName = name.replace('$', '.')
            return if (packageName.isEmpty()) simpleName else "${packageName.replace('.', '/')}/$simpleName"
        }

    }

}
