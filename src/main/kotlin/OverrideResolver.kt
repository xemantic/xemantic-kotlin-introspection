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
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import java.lang.reflect.GenericArrayType
import java.lang.reflect.MalformedParameterizedTypeException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmVariance
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isDefinitelyNonNull
import kotlin.metadata.isInner
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.kind
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.moduleName
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility
import java.lang.reflect.Type as JavaType
import org.objectweb.asm.Type as AsmType

/**
 * Tells whether a member of a Kotlin class overrides a member of its supertypes.
 *
 * The members are compared at the Kotlin level, like the Kotlin compiler does:
 * by name, context parameter, receiver and value parameter types, with the type arguments of the supertypes
 * substituted along the whole supertype hierarchy, and read from the Kotlin metadata
 * (from the `.kotlin_builtins` declarations for the built-in types, like `Any` or `MutableList`).
 * Thanks to that the JVM representation, like the value class name mangling,
 * the `Continuation` parameter of suspend functions, the getter names of properties,
 * or the JVM names of the built-in members, like `charAt` for `CharSequence.get`, are irrelevant.
 *
 * Only Java supertypes are reflected, or read from their class files when they cannot be loaded,
 * and their methods are matched by the erasure of the parameter types,
 * as the nullability and the mutability of their types are not known in Kotlin,
 * except for a primitive type, which is not nullable.
 * A Java type mapped to a Kotlin built-in type, like `java.util.List`, is replaced with the built-in type.
 *
 * The overridable members of the supertypes are collected once per class, and the declarations are read once per class,
 * both kept in bounded caches of the least recently used ones,
 * so that a long-lived resolver over a large classpath does not keep the declarations of every class ever resolved,
 * as long as the classes are looked up through a bounded cache as well.
 * What is computed while a class lookup fails maybe only for now, see [transientFailures], is not cached.
 * This class is thread-safe.
 *
 * @param classLoader the class loader of the introspected classes, telling which classes have their own class files.
 * @param loadClassByMetadataName loads a class by its Kotlin class name, like `java/util/Map.Entry`,
 *   through the [classLoader], unless it cannot be loaded.
 * @param loadKmClassByMetadataName reads the Kotlin metadata of a class by its Kotlin class name, if it is a Kotlin class,
 *   also of one which cannot be loaded, but whose class file can still be read.
 * @param readClassBytesByMetadataName reads the class file of a class by its Kotlin class name, like `com/example/Foo.DefaultImpls`,
 *   through the [classLoader], without loading the class, so also of one which cannot be loaded,
 *   or `null` if there is no such class file.
 * @param unloadableClassBytesByMetadataName the class file of a class which cannot be loaded, by its Kotlin class name,
 *   as read when looking the class up, or `null` if the class can be loaded, or its class file cannot be read.
 * @param transientFailures the failures of the functions above which might be transient, like after a linkage error,
 *   which might not happen again, as marked on the current thread.
 * @param builtinClasses the declarations of the Kotlin built-in types, by their Kotlin class name, like `kotlin/collections/List`.
 * @param builtinTypes the JVM classes of the Kotlin built-in types, by their fully qualified Kotlin name.
 */
@OptIn(ExperimentalContextParameters::class)
internal class OverrideResolver(
    private val classLoader: ClassLoader,
    private val loadClassByMetadataName: (String) -> Class<*>?,
    private val loadKmClassByMetadataName: (String) -> KmClass?,
    private val readClassBytesByMetadataName: (String) -> ByteArray?,
    private val unloadableClassBytesByMetadataName: (String) -> ByteArray?,
    private val transientFailures: TransientFailures,
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
     * @param isRaw whether this is a raw supertype of a Java class, like `Base` in `class Mid extends Base`,
     *   whose type variables, or type parameters of a Kotlin `Base`, are erased to their bounds,
     *   unlike a type with unknown type arguments.
     *   A class file whose generic signatures are stripped, like by R8 or ProGuard without `-keepattributes Signature`,
     *   looks raw as well, so that a type variable of its generic supertype is erased to its bound too,
     *   and a real override of `Base<String>.process(x: String)` through such a shrunk `Mid` is not told,
     *   while matching it through an unknown type would mark any overload of `process` as an override.
     */
    private data class ClassType(
        val name: String,
        val arguments: List<TypeArgument> = emptyList(),
        val nullable: Boolean? = false,
        val outer: ClassType? = null,
        val isJavaPrimitive: Boolean = false,
        val isRaw: Boolean = false
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
     * Whether a member of a supertype is visible to the class whose members are checked, so that it can be overridden.
     *
     * An internal member is visible only within its module, or within a friend module, like the test module of the main one.
     * The module name in the metadata does not tell the module apart, as unrelated modules might share it,
     * like the default `main` of plain kotlinc without `-module-name`, or a Gradle project name, like `core`,
     * so the JVM method of the member, mangled with its module name, like `reset$core`, is the evidence:
     * the overriding class declares it, either as its own override in the same module,
     * or as the bridge to its own override, whose JVM name is mangled with its own module name,
     * or not mangled at all for a public override.
     */
    private sealed interface MemberVisibility {

        /**
         * A member visible wherever it is inherited, like a public or protected one,
         * or an internal one without a mangled JVM method, like a `@PublishedApi` one,
         * of a module of the same name, assumed to be the same module,
         * as two unrelated modules of the same name cannot be told apart then.
         * Such a member of a module of another name, whose override from a friend module cannot be told,
         * as a class of any module, not only of a friend one, declares its JVM method,
         * when it has a member of the same signature, is not overridable at all.
         */
        data object Visible : MemberVisibility

        /**
         * An internal member with a mangled [jvmMethod], visible if the overriding class declares the [jvmMethod]:
         * as its own override in the same module, or as the bridge to its own override in a friend module,
         * which is the only trace of a friend module,
         * so a friend-module override whose bridge is removed, like by a shrinker, is not told.
         *
         * @param jvmMethod the JVM name followed by the descriptor of the member.
         * @param isVisibleWhenUnknown whether the member is visible when the JVM methods of the overriding class
         *   can be neither read nor reflected: for a module of the same name, likely the same module,
         *   but not for a module of another name, as an override is then claimed only on evidence.
         */
        data class Mangled(val jvmMethod: String, val isVisibleWhenUnknown: Boolean) : MemberVisibility
    }

    /**
     * A member of a supertype, which can be overridden.
     *
     * @param isJava whether the member is a Java method, matched by the erasure of its parameter types.
     * @param declaresDefaultValues the indices of the value parameters declaring a default value,
     *   which only a Kotlin function can.
     */
    private class Overridable(
        val contexts: List<MemberType> = emptyList(),
        val receiver: MemberType?,
        val parameters: List<MemberType>,
        val typeParameterCount: Int,
        val isJava: Boolean,
        val visibility: MemberVisibility = MemberVisibility.Visible,
        val declaresDefaultValues: Set<Int> = emptySet()
    )

    /**
     * @param readOwnerJvmMethods reads the JVM methods of the owner class, as names followed by descriptors,
     *   or `null` if they can be neither read nor reflected;
     *   only needed to tell a shrunk member or a friend module, so read lazily, and cached on their own,
     *   as they might fail transiently after the overridables are cached.
     */
    private class Overridables(
        val substitution: Map<Int, MemberType>,
        val functions: Map<String, List<Overridable>>,
        val properties: Map<String, List<Overridable>>,
        private val readOwnerJvmMethods: () -> Set<String>?
    ) {
        val ownerJvmMethods: Set<String>? get() = readOwnerJvmMethods()
    }

    /**
     * A declaration of a supertype: Kotlin, Java, or both for a Kotlin built-in type
     * whose JVM type contributes Java members, like `Throwable.fillInStackTrace`.
     */
    private class Declaration(
        val kotlin: KmClass?,
        val java: Class<*>?,
        val hiddenJavaMethods: Set<String> = emptySet(),
        val javaClassFile: JavaClassFile? = null
    )

    /**
     * A Java class which cannot be loaded, like when its supertype is missing from the classpath,
     * as read from its class file.
     *
     * @param internalName the internal name of the class, like `com/example/Outer${'$'}Nested`.
     * @param signature the generic signature of the class, telling its type variables and generic supertypes.
     * @param superName the internal name of the superclass, like `java/lang/Object`.
     * @param interfaces the internal names of the interfaces.
     */
    private class JavaClassFile(
        val internalName: String,
        val methods: List<DeclaredMethod>,
        val signature: String?,
        val superName: String?,
        val interfaces: List<String>
    ) {
        val packageName: String get() = internalName.substringBeforeLast('/', "").replace('/', '.')
    }

    /**
     * The owner of the overridables, by its name, and whether it is the declaration of a built-in type,
     * which might be incomplete, unlike the declaration of the class file of the same name, like of `KClass`.
     */
    private data class OwnerKey(val name: String, val isBuiltin: Boolean)

    // collecting the overridables never requests other overridables, and finding a declaration never requests
    // another declaration, so that neither computation requests its own key, which the BoundedCache forbids
    private val overridablesCache = BoundedCache<OwnerKey, Overridables>(capacity = 256)

    // the declarations of the supertypes of the recently resolved classes, shared between their hierarchies
    private val declarationCache = BoundedCache<String, Declaration>(capacity = 1024)

    // the JVM methods of the owners of the overridables, see Overridables.ownerJvmMethods
    private val jvmMethodsCache = BoundedCache<String, Set<String>?>(capacity = 256)

    /**
     * The cached value of the [key], computed and cached unless a transient failure happens meanwhile on this thread,
     * like of a lookup of a class the computed value is built from.
     * A value which is not cached is marked as a transient failure,
     * also when received from the computation of another thread,
     * so that the computation requesting it is not cached either.
     */
    private fun <K : Any, V> BoundedCache<K, V>.getOrPutUnlessTransient(key: K, compute: () -> V): V {
        val computed = getOrCompute(key) {
            val failureCount = transientFailures.count()
            val value = compute()
            BoundedCache.Computed(value, isCacheable = transientFailures.count() == failureCount)
        }
        if (!computed.isCacheable) transientFailures.mark()
        return computed.value
    }

    /**
     * Whether the standard library classes the [classLoader] defines have class files served as resources,
     * unlike the ones of an in-memory class loader of a compiler or a scripting host.
     */
    private val servesStdlibClassFiles: Boolean by lazy { classLoader.hasClassFile("kotlin.KotlinVersion") }

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

    /**
     * An override of a supertype function.
     *
     * @param inheritedDefaultValues the indices of the value parameters inheriting a default value
     *   from an overridden function, directly or through other overrides, as an override cannot declare its own.
     */
    class FunctionOverride(val inheritedDefaultValues: Set<Int>)

    /**
     * The override of a supertype function by the [function], or `null` if it overrides none.
     * The whole supertype hierarchy is matched, so that the declaration of an inherited default value is found
     * also through intermediate overrides.
     */
    fun override(owner: KmClass, function: KmFunction): FunctionOverride? =
        override(owner, function, withDefaultValues = true)

    /**
     * Whether the [function] overrides a supertype function, like [override], without matching all of them.
     */
    fun isOverride(owner: KmClass, function: KmFunction): Boolean =
        override(owner, function, withDefaultValues = false) != null

    /**
     * @param withDefaultValues whether to match all the overridden functions for their inherited default values,
     *   otherwise the first one tells that the [function] is an override, without default values.
     */
    private fun override(owner: KmClass, function: KmFunction, withDefaultValues: Boolean): FunctionOverride? {
        val overridables = overridables(owner)
        val candidates = overridables.functions[function.name] ?: return null
        val signature = overridables.signature(function)
        val overridden = candidates.asSequence().filter { it.matches(signature) && overridables.isVisible(it) }
        return when {
            !withDefaultValues -> if (overridden.any()) noDefaultValues else null
            else -> overridden.toList().takeIf { it.isNotEmpty() }
                ?.let { FunctionOverride(inheritedDefaultValues(it, signature)) }
        } ?: if (overridables.isShrunk(function.signature)) noDefaultValues else null
    }

    private val noDefaultValues = FunctionOverride(emptySet())

    /**
     * The types of a function checked for overriding, resolved in the context of its class.
     */
    private class FunctionSignature(
        val contexts: List<MemberType>,
        val receiver: MemberType?,
        val parameters: List<MemberType>,
        val typeParameterCount: Int,
        val isSuspend: Boolean
    )

    private fun Overridables.signature(function: KmFunction): FunctionSignature {
        val typeParameters = memberTypeParameters(function.typeParameters)
        return FunctionSignature(
            contexts = function.contextParameters.map { kotlinType(it.type, substitution, typeParameters) },
            receiver = function.receiverParameterType?.let { kotlinType(it, substitution, typeParameters) },
            parameters = function.valueParameters.map { kotlinType(it.type, substitution, typeParameters) },
            typeParameterCount = function.typeParameters.size,
            isSuspend = function.isSuspend
        )
    }

    private fun Overridable.matches(signature: FunctionSignature, onlyKnownTypes: Boolean = false): Boolean = matches(
        signature.contexts, signature.receiver, signature.parameters, signature.typeParameterCount, signature.isSuspend,
        onlyKnownTypes
    )

    /**
     * The indices of the value parameters declaring a default value in the [overridden] functions.
     * As an unknown type, like a type parameter of a supertype whose type argument cannot be reflected, matches any type,
     * a sibling overload, which is not overridden, like `f(x: T, y: Int = 0)` of `f(x: String, y: Int)`, might be matched too,
     * so the default values are taken only from the functions matching on the known types, if there are any.
     * This might drop a real default value inherited through a supertype which cannot be told,
     * like one whose type argument refers to a class missing from the classpath,
     * when another overridden function, like `f(x: String, y: Int)` of an interface, matches on the known types,
     * as omitting a default value is preferred over claiming a false one, which would not compile when omitted.
     */
    private fun inheritedDefaultValues(overridden: List<Overridable>, signature: FunctionSignature): Set<Int> {
        if (overridden.all { it.declaresDefaultValues.isEmpty() }) return emptySet()
        val matchingKnownTypes = if (overridden.size == 1) overridden else overridden.filter {
            !it.isJava && it.matches(signature, onlyKnownTypes = true)
        }
        return matchingKnownTypes.ifEmpty { overridden }.flatMapTo(HashSet()) { it.declaresDefaultValues }
    }

    fun isOverride(owner: KmClass, property: KmProperty): Boolean {
        val overridables = overridables(owner)
        val typeParameters = memberTypeParameters(property.typeParameters)
        val contexts = property.contextParameters.map {
            kotlinType(it.type, overridables.substitution, typeParameters)
        }
        val receiver = property.receiverParameterType?.let {
            kotlinType(it, overridables.substitution, typeParameters)
        }
        val candidates = overridables.properties[property.name].orEmpty()
        val overridesProperty = candidates.any {
            it.matches(contexts, receiver, emptyList(), property.typeParameters.size, isSuspend = false) &&
                overridables.isVisible(it)
        }
        // a property of a Kotlin supertype without metadata is known only by its JVM getter,
        // which a private or a @JvmField property does not have, and which might be renamed or mangled
        val getter = property.getterSignature?.takeIf { property.visibility.isOverridable }
        val overridesGetter = getter != null && overridables.functions[getter.name].orEmpty().any {
            it.isJava && it.matches(contexts, receiver, emptyList(), property.typeParameters.size, isSuspend = false)
        }
        return overridesProperty || overridesGetter ||
            (candidates.isNotEmpty() && overridables.isShrunk(property.getterSignature))
    }

    /**
     * Whether the [overridable] member is visible to the owner, see [MemberVisibility],
     * checked only after matching the signature, as it might read the class file of the owner.
     */
    private fun Overridables.isVisible(overridable: Overridable): Boolean =
        when (val visibility = overridable.visibility) {
            MemberVisibility.Visible -> true
            is MemberVisibility.Mangled ->
                ownerJvmMethods?.contains(visibility.jvmMethod) ?: visibility.isVisibleWhenUnknown
        }

    /**
     * Whether the JVM method of a member, declared in the metadata, has been removed from the class file of its owner,
     * like by R8, so that it cannot be told from an overload of a supertype member,
     * which is then assumed to be overridden, as one with the same name is known.
     * The caller checks that a supertype member of the same name exists.
     * A member whose owner JVM methods can be neither read nor reflected is not considered shrunk.
     */
    private fun Overridables.isShrunk(signature: JvmMethodSignature?): Boolean =
        signature != null && ownerJvmMethods?.let { signature.toString() !in it } == true

    /**
     * @param onlyKnownTypes whether an unknown type matches no type, except for a star projection,
     *   only applying to a Kotlin member, as a Java one is matched by the erasures.
     */
    private fun Overridable.matches(
        contexts: List<MemberType>,
        receiver: MemberType?,
        parameters: List<MemberType>,
        typeParameterCount: Int,
        isSuspend: Boolean,
        onlyKnownTypes: Boolean = false
    ): Boolean {
        if (this.typeParameterCount != typeParameterCount) return false
        if (isJava) {
            // a Java method has no context parameters, so it is not overridden by a member having them,
            // even if they are its parameters on the JVM, which also applies to a Kotlin supertype without metadata,
            // as it cannot be told from a Java one, so that a real override of its member with context parameters
            // is not told, while matching the context parameters as leading JVM parameters would mark a member
            // with a context parameter as an override of a real Java method taking it, like Consumer<Ctx>.accept
            if (contexts.isNotEmpty()) return false
            // on the JVM, the receiver is the first parameter, and a suspend function takes a trailing Continuation
            val jvmParameters = listOfNotNull(receiver) + parameters +
                if (isSuspend) listOf(ClassType("kotlin/coroutines/Continuation")) else emptyList()
            return this.parameters.size == jvmParameters.size &&
                this.parameters.zip(jvmParameters).all { (overridden, overriding) -> erasuresMatch(overridden, overriding) }
        }
        if ((this.receiver == null) != (receiver == null)) return false
        if (this.parameters.size != parameters.size) return false
        if (this.contexts.size != contexts.size) return false
        fun typesMatch(overridden: MemberType, overriding: MemberType) = typesMatch(overridden, overriding, onlyKnownTypes)
        return this.contexts.zip(contexts).all { (overridden, overriding) -> typesMatch(overridden, overriding) } &&
            (this.receiver == null || typesMatch(this.receiver, receiver!!)) &&
            this.parameters.zip(parameters).all { (overridden, overriding) -> typesMatch(overridden, overriding) }
    }

    private fun overridables(owner: KmClass): Overridables =
        overridablesCache.getOrPutUnlessTransient(OwnerKey(owner.name, isBuiltin = builtinClasses()[owner.name] === owner)) {
            collectOverridables(owner)
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
        // a class is never its own supertype, which matters for Any itself
        val visited = mutableSetOf(owner.name)

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
                val isSameModuleName = kmClass.moduleName == owner.moduleName
                // the JVM name of an internal member is mangled with the module name, sanitized like by the compiler,
                // unless it is a @PublishedApi one; the module name is absent only from the built-in declarations,
                // read from .kotlin_builtins, which have no internal members
                val mangledSuffix by lazy { kmClass.moduleName?.let { "$" + it.replace(nonIdentifierCharacters, "_") } }
                // null for a member which cannot be overridden by the owner, see MemberVisibility.Visible
                fun visibility(visibility: Visibility, signature: JvmMethodSignature?): MemberVisibility? {
                    if (visibility != Visibility.INTERNAL) return MemberVisibility.Visible
                    val suffix = mangledSuffix
                    return when {
                        signature != null && suffix != null && signature.name.endsWith(suffix) ->
                            MemberVisibility.Mangled(signature.toString(), isVisibleWhenUnknown = isSameModuleName)
                        isSameModuleName -> MemberVisibility.Visible
                        else -> null
                    }
                }
                kmClass.functions.filter { it.visibility.isOverridable }.forEach { function ->
                    val memberVisibility = visibility(function.visibility, function.signature) ?: return@forEach
                    val typeParameters = memberTypeParameters(function.typeParameters)
                    functions.getOrPut(function.name) { mutableListOf() } += Overridable(
                        contexts = function.contextParameters.map {
                            kotlinType(it.type, substitution, typeParameters)
                        },
                        receiver = function.receiverParameterType?.let {
                            kotlinType(it, substitution, typeParameters)
                        },
                        parameters = function.valueParameters.map {
                            kotlinType(it.type, substitution, typeParameters)
                        },
                        typeParameterCount = function.typeParameters.size,
                        isJava = false,
                        visibility = memberVisibility,
                        declaresDefaultValues = function.valueParameters.indices.filter {
                            function.valueParameters[it].declaresDefaultValue
                        }.toSet()
                    )
                }
                kmClass.properties.filter { it.visibility.isOverridable }.forEach { property ->
                    val memberVisibility = visibility(property.visibility, property.getterSignature) ?: return@forEach
                    val typeParameters = memberTypeParameters(property.typeParameters)
                    properties.getOrPut(property.name) { mutableListOf() } += Overridable(
                        contexts = property.contextParameters.map {
                            kotlinType(it.type, substitution, typeParameters)
                        },
                        receiver = property.receiverParameterType?.let {
                            kotlinType(it, substitution, typeParameters)
                        },
                        parameters = emptyList(),
                        typeParameterCount = property.typeParameters.size,
                        isJava = false,
                        visibility = memberVisibility
                    )
                }
                kmClass.supertypes.forEach { type ->
                    (kotlinType(type, substitution) as? ClassType)?.let { pending += it }
                }
            }

            declaration.java?.let { javaClass ->
                val substitution = javaSubstitution(javaClass, supertype)
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

            declaration.javaClassFile?.let { classFile ->
                val signature = classFile.signature?.let { genericClassSignature(it) }
                val substitution = classFileSubstitution(signature, supertype)
                asmMethods(classFile.methods, classFile.packageName, ownerPackage, substitution).forEach { (name, method) ->
                    functions.getOrPut(name) { mutableListOf() } += method
                }
                pending += classFileSupertypes(classFile, signature, substitution)
            }
        }

        // only the name and the kind are captured, as the overridables are cached
        val ownerName = owner.name
        val isInterface = owner.kind == ClassKind.INTERFACE
        return Overridables(ownerSubstitution, functions, properties) {
            jvmMethodsCache.getOrPutUnlessTransient(ownerName) { jvmMethods(ownerName, isInterface) }
        }
    }

    /**
     * The JVM methods of a Kotlin class, as names followed by descriptors, see [declaredMethods],
     * including the members of an interface relocated to its `DefaultImpls` class,
     * like with `-jvm-default=disable`, as if they were declared in the interface, unless they are unknown,
     * or `null` if the methods of the class itself can be neither read nor reflected.
     */
    private fun jvmMethods(className: String, isInterface: Boolean): Set<String>? {
        // the package is separated with '/', and nested classes with '.'
        val internalName = className.replace('.', '$')
        val classFile = readClassBytesByMetadataName(className)
        val methods = declaredMethods(className, classFile)
            ?.mapTo(mutableSetOf()) { it.name + it.descriptor }
            ?: return null
        if (isInterface) {
            val defaultImplsName = "$className.DefaultImpls"
            val defaultImplsFile = readClassBytesByMetadataName(defaultImplsName)
            // most interfaces have no DefaultImpls, so it is not loaded to tell, unless no class file is served
            val defaultImplsClass = if (defaultImplsFile == null && classFile == null) {
                loadClassByMetadataName(defaultImplsName)
            } else null
            if (defaultImplsFile != null || defaultImplsClass != null) {
                // the static method of DefaultImpls takes the interface instance as the first parameter
                val instanceParameter = "(L$internalName;"
                // the methods of the interface itself are still known when the ones of DefaultImpls are not
                declaredMethods(defaultImplsName, defaultImplsFile, defaultImplsClass)?.forEach { method ->
                    if (method.access and Opcodes.ACC_STATIC != 0 && method.descriptor.startsWith(instanceParameter)) {
                        methods += method.name + "(" + method.descriptor.removePrefix(instanceParameter)
                    }
                }
            }
        }
        return methods
    }

    /**
     * A method of a class.
     *
     * @param access the access flags, whose `ACC_STATIC` is the [Modifier.STATIC] of a reflected method.
     * @param signature the generic signature, if read from a class file declaring it.
     */
    private class DeclaredMethod(
        val access: Int,
        val name: String,
        val descriptor: String,
        val signature: String? = null
    )

    /**
     * The methods declared by a class, read from its [classFile], whether the class can be loaded or not,
     * or reflected, when there is no class file, like when the class loader does not serve it as a resource,
     * or it cannot be parsed, like when it is too new for ASM, but the class can be loaded,
     * or `null` if they can be neither read nor reflected.
     *
     * @param loadedClass the class, if already loaded.
     */
    private fun declaredMethods(
        className: String,
        classFile: ByteArray?,
        loadedClass: Class<*>? = null
    ): List<DeclaredMethod>? {
        classFile?.let { readMethods(it) }?.let { return it }
        val clazz = loadedClass ?: loadClassByMetadataName(className) ?: return null
        return reflectOrNull {
            clazz.declaredMethods.map { DeclaredMethod(it.modifiers, it.name, AsmType.getMethodDescriptor(it)) }
        }
    }

    /**
     * Reads the methods of a class file, with their generic signatures,
     * or `null` if it cannot be parsed, like when it is malformed, or too new for ASM.
     * Only the parsing is guarded, so that a failure of the code processing the methods is not mistaken for it.
     */
    private fun readMethods(bytes: ByteArray): List<DeclaredMethod>? {
        val methods = mutableListOf<DeclaredMethod>()
        try {
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor? {
                    methods += DeclaredMethod(access, name, descriptor, signature)
                    return null
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        } catch (_: Exception) {
            return null
        }
        return methods
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
     * mapped to the type arguments of the [supertype], and of its outer types,
     * or to the erasures of their bounds if the [supertype] is a raw supertype of a Java class,
     * like `Comparable` in `class C implements Comparable`, as [javaSubstitution] does for a Java class.
     */
    private fun supertypeSubstitution(kmClass: KmClass, supertype: ClassType): Map<Int, MemberType> = buildMap {
        val declarations = generateSequence(kmClass) { declaration ->
            if (declaration.isInner) declaration(outerClassName(declaration.name)).kotlin else null
        }.toList()
        // the outer type of a raw inner type is raw as well
        if (supertype.isRaw) {
            val typeParameters = declarations.flatMap { it.typeParameters }.associateBy { it.id }
            typeParameters.values.forEach { typeParameter ->
                put(typeParameter.id, erasedBound(typeParameter, typeParameters)?.copy(nullable = null) ?: UnknownType)
            }
            return@buildMap
        }
        var type: ClassType? = supertype
        declarations.forEach { declaration ->
            declaration.typeParameters.forEachIndexed { index, typeParameter ->
                put(typeParameter.id, type?.arguments?.getOrNull(index)?.type ?: UnknownType)
            }
            type = type?.outer
        }
    }

    private fun outerClassName(name: String): String = name.substringBeforeLast('.')

    private fun erasure(typeParameter: KmTypeParameter, typeParameters: Map<Int, KmTypeParameter>): String? =
        erasedBound(typeParameter, typeParameters)?.let { normalizedName(it.name) }

    /**
     * The raw type a [typeParameter] is erased to: its first bound, or the bound of the type parameter bounding it,
     * with unknown type arguments, or `null` if unknown, like for an array bound.
     *
     * @param typeParameters the type parameters in scope, by their ids, which can bound the [typeParameter].
     */
    private fun erasedBound(typeParameter: KmTypeParameter, typeParameters: Map<Int, KmTypeParameter>): ClassType? {
        val bound = typeParameter.upperBounds.firstOrNull() ?: return ClassType("kotlin/Any")
        return when (val classifier = bound.classifier) {
            is KmClassifier.Class -> if (classifier.name == "kotlin/Array") null else ClassType(
                name = classifier.name,
                arguments = bound.arguments.map { TypeArgument(null, UnknownType) }
            )
            is KmClassifier.TypeParameter -> typeParameters[classifier.id]
                ?.takeIf { it !== typeParameter }
                ?.let { erasedBound(it, typeParameters - typeParameter.id) }
            is KmClassifier.TypeAlias -> null
        }
    }

    /**
     * The type parameters of a member, matched by their position, by their ids,
     * resolved once per member, as the member types might refer to them many times.
     */
    private fun memberTypeParameters(typeParameters: List<KmTypeParameter>): Map<Int, TypeParameterType> {
        if (typeParameters.isEmpty()) return emptyMap()
        val byId = typeParameters.associateBy { it.id }
        return typeParameters.withIndex().associate { (index, typeParameter) ->
            typeParameter.id to TypeParameterType("F$index", false, erasure(typeParameter, byId))
        }
    }

    /**
     * Resolves a Kotlin [type] of a member, substituting the type parameters of its class.
     *
     * @param typeParameters the type parameters of the member itself, as resolved by [memberTypeParameters].
     */
    private fun kotlinType(
        type: KmType,
        substitution: Map<Int, MemberType>,
        typeParameters: Map<Int, TypeParameterType> = emptyMap()
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
        is KmClassifier.TypeParameter -> typeParameters[classifier.id]?.copy(nullable = type.isNullable)
            ?: substitution[classifier.id]?.withNullability(type)
            ?: UnknownType
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
        typeParameters: Map<Int, TypeParameterType>
    ): ClassType? {
        if (!type.isSuspend) return null
        val functionType = type.decodeRuntimeFunctionType() ?: return null
        return ClassType(
            name = "kotlin/SuspendFunction${functionType.parameters.size}",
            arguments = (functionType.parameters + functionType.returnType).map {
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
     *
     * @param onlyKnownTypes whether an unknown type matches no type, except for a star projection, which is known.
     */
    private fun typesMatch(a: MemberType, b: MemberType, onlyKnownTypes: Boolean = false): Boolean = when {
        a == UnknownType || b == UnknownType -> !onlyKnownTypes
        a is ClassType && b is ClassType -> {
            val flexible = a.isFlexible || b.isFlexible
            val namesMatch = if (flexible) normalizedName(a.name) == normalizedName(b.name) else a.name == b.name
            namesMatch &&
                (flexible || a.nullable == b.nullable) &&
                a.arguments.size == b.arguments.size &&
                a.arguments.zip(b.arguments).withIndex().all { (index, arguments) ->
                    val (x, y) = arguments
                    if (x.type == UnknownType || y.type == UnknownType) {
                        !onlyKnownTypes || x.variance == null || y.variance == null
                    } else {
                        (flexible || variancesMatch(a.name, index, x.variance, y.variance)) &&
                            typesMatch(x.type, y.type, onlyKnownTypes)
                    }
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
     * a Java primitive type matching only a non-nullable class type, as a nullable one is boxed on the JVM.
     * A type parameter never matches a primitive type: one with a nullable bound, like `T : Int?`, is boxed,
     * and one with a non-nullable bound, like `T : Int`, would be an accidental override rejected by the compiler.
     */
    private fun erasuresMatch(overridden: MemberType, overriding: MemberType): Boolean {
        if (overridden is ClassType && overridden.isJavaPrimitive &&
            (overriding is TypeParameterType || (overriding is ClassType && overriding.nullable == true))
        ) return false
        val erasureA = erasure(overridden) ?: return true
        val erasureB = erasure(overriding) ?: return true
        return erasureA == erasureB
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
        else -> functionTypeOfJvmInterface(name) ?: name
    }

    private fun declaration(name: String): Declaration =
        declarationCache.getOrPutUnlessTransient(name) { findDeclaration(name) }

    private fun findDeclaration(name: String): Declaration {
        val builtinClass = builtinClasses()[name]
        // a built-in type without its own class file, like List, is not looked up through the class loader,
        // which would only throw a ClassNotFoundException, unless the class loader serves no class files
        // of the standard library, so that the ones it has cannot be told
        if (builtinClass == null || !servesStdlibClassFiles || classLoader.hasClassFile(binaryNameOf(name))) {
            // the built-in declaration of a type having its own class file, like KClass, is incomplete
            loadKmClassByMetadataName(name)?.let { return Declaration(it, null) }
        }
        builtinClass?.let { kmClass ->
            // members of Any are fully described by its Kotlin declaration, unlike the JVM members of Object
            val builtinJavaClass = if (name == "kotlin/Any") null else builtinJavaClasses[name]
            val hiddenJavaMethods = when {
                !name.startsWith("kotlin/collections/") -> emptySet()
                name.substringAfterLast('/').contains("Mutable") -> hiddenCollectionMethods
                else -> hiddenCollectionMethods + mutatingCollectionMethods
            }
            return Declaration(kmClass, builtinJavaClass, hiddenJavaMethods)
        }
        loadClassByMetadataName(name)?.let { return Declaration(null, it) }
        return Declaration(null, null, javaClassFile = unloadableClassBytesByMetadataName(name)?.let { readJavaClassFile(it, name) })
    }

    /**
     * Reads the class file of a Java class of the Kotlin class [name], which cannot be loaded,
     * or returns `null` if it cannot be parsed, or it is of another class, like one of a name differing only in case,
     * or it is a Kotlin class, whose metadata cannot be read, as its JVM methods are not its Kotlin members.
     */
    private fun readJavaClassFile(bytes: ByteArray, name: String): JavaClassFile? {
        var classFile: JavaClassFile? = null
        var isKotlin = false
        val methods = mutableListOf<DeclaredMethod>()
        try {
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?
                ) {
                    classFile = JavaClassFile(name, methods, signature, superName, interfaces?.toList().orEmpty())
                }

                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                    if (descriptor == "Lkotlin/Metadata;") isKotlin = true
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor? {
                    methods += DeclaredMethod(access, name, descriptor, signature)
                    return null
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        } catch (_: Exception) {
            return null
        }
        return classFile?.takeIf { !isKotlin && it.internalName == binaryNameOf(name).replace('.', '/') }
    }

    /**
     * The type arguments substituted for the type variables of a Java class, and of its outer classes if it is an inner class,
     * bound per declaring class, from the innermost one.
     *
     * @param scopes the declaring classes with the type arguments of their type variables, by their names,
     *   where the class is `null` for a class known only from its class file, see [classFileSubstitution].
     */
    private class JavaSubstitution(private val scopes: List<Pair<Class<*>?, Map<String, Lazy<MemberType>>>>) {

        /**
         * The type argument of a reflected type variable of a class, or `null` if it is not declared by one of the scopes.
         */
        operator fun get(typeVariable: TypeVariable<*>): MemberType? =
            scopes.firstOrNull { (declaration, _) -> declaration != null && declaration == typeVariable.genericDeclaration }
                ?.second?.get(typeVariable.name)?.value

        /**
         * The type argument of a type variable of a class known only by its [name], like in a generic signature,
         * declared by the innermost scope declaring this name, as it shadows the outer ones.
         */
        operator fun get(name: String): MemberType? =
            scopes.firstNotNullOfOrNull { (_, typeArguments) -> typeArguments[name] }?.value

    }

    /**
     * The type variables of the [javaClass], and of its outer classes if it is an inner class,
     * mapped to the type arguments of the [supertype], and of its outer types,
     * or to the erasures of their bounds if the [supertype] is raw, reflected only when a member refers to them.
     */
    private fun javaSubstitution(javaClass: Class<*>, supertype: ClassType): JavaSubstitution {
        val scopes = mutableListOf<Pair<Class<*>?, Map<String, Lazy<MemberType>>>>()
        var declaration: Class<*>? = javaClass
        var type: ClassType? = supertype
        // the outer type of a raw inner type is raw as well
        val isRaw = supertype.isRaw
        while (declaration != null) {
            val typeArguments = type?.arguments
            // a malformed generic signature
            val typeParameters = reflectOrNull { declaration.typeParameters } ?: break
            scopes += declaration to typeParameters.withIndex().associate { (index, typeVariable) ->
                typeVariable.name to if (isRaw) {
                    lazy(LazyThreadSafetyMode.NONE) { boundErasure(typeVariable) }
                } else {
                    lazyOf(typeArguments?.getOrNull(index)?.type ?: UnknownType)
                }
            }
            // a static nested class cannot refer to the type variables of its outer class
            if (Modifier.isStatic(declaration.modifiers)) break
            declaration = reflectOrNull { declaration.declaringClass }
            type = type?.outer
        }
        return JavaSubstitution(scopes)
    }

    /**
     * The type a [typeVariable] of a raw type is erased to: the erasure of its first bound,
     * or an unknown type if the bound refers to a class missing from the classpath.
     */
    private fun boundErasure(typeVariable: TypeVariable<*>): MemberType = reflectOrNull {
        var bound: JavaType? = typeVariable
        // javac rejects cyclic bounds, but a malformed class file might declare them, which reflection does not check
        val visited = mutableSetOf<TypeVariable<*>>()
        while (bound is TypeVariable<*> && visited.add(bound)) {
            bound = bound.bounds.firstOrNull()
        }
        when (bound) {
            is Class<*> -> javaClassType(bound)
            is ParameterizedType -> javaClassType(bound.rawType as Class<*>)
            else -> UnknownType
        }
    } ?: UnknownType

    /**
     * Runs a reflective [block], returning `null` if it throws because a reflected type refers to a class
     * missing from the classpath, or a generic signature is malformed, as the generic types are resolved lazily.
     */
    private inline fun <T : Any> reflectOrNull(block: () -> T?): T? = try {
        block()
    } catch (_: TypeNotPresentException) {
        null
    } catch (_: MalformedParameterizedTypeException) {
        null
    } catch (_: LinkageError) {
        null
    }

    /**
     * The overridable methods of a Java class, by their names.
     */
    private fun javaMethods(
        javaClass: Class<*>,
        ownerPackage: String,
        substitution: JavaSubstitution
    ): List<Pair<String, Overridable>> {
        // a method signature might refer to a class missing from the classpath
        val methods = reflectOrNull { javaClass.declaredMethods }
            ?: return asmMethods(javaClass, ownerPackage, substitution)
        val overridables = methods
            .filter { method ->
                val modifiers = method.modifiers
                !method.isSynthetic && !method.isBridge &&
                    !Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers) && !Modifier.isPrivate(modifiers) &&
                    (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers) ||
                        javaClass.packageName == ownerPackage)
            }
        // a generic type, like a wildcard or a type variable bound, might refer to a class missing from the classpath
        return reflectOrNull { overridables.map { method -> method.name to javaOverridable(method, substitution) } }
            ?: asmMethods(javaClass, ownerPackage, substitution)
    }

    /**
     * The generic types are resolved lazily, so this might throw when they refer to a class missing from the classpath.
     */
    private fun javaOverridable(method: Method, substitution: JavaSubstitution): Overridable =
        Overridable(
            receiver = null,
            parameters = method.genericParameterTypes.map { javaType(it, substitution, method) },
            typeParameterCount = method.typeParameters.size,
            isJava = true
        )

    private fun javaSupertypes(javaClass: Class<*>, substitution: JavaSubstitution): List<ClassType> {
        val rawSupertypes = listOfNotNull(javaClass.superclass) + javaClass.interfaces
        val supertypes = reflectOrNull {
            (listOfNotNull(javaClass.genericSuperclass) + javaClass.genericInterfaces).map { type ->
                // a generic supertype given as a class is raw
                if (type is Class<*>) {
                    javaClassType(type).copy(isRaw = true)
                } else {
                    javaType(type, substitution, method = null)
                }
            }
        } ?: rawSupertypes.map { javaClassType(it) }
        return supertypes.filterIsInstance<ClassType>()
    }

    /**
     * Resolves a Java [type], substituting the type variables of its class.
     *
     * @param method the method declaring the [type], whose type variables are erased.
     */
    private fun javaType(type: JavaType, substitution: JavaSubstitution, method: Method?): MemberType =
        when (type) {
            is Class<*> -> javaClassType(type)
            is ParameterizedType -> javaClassType(type.rawType as Class<*>).copy(
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
            is GenericArrayType -> arrayType(javaType(type.genericComponentType, substitution, method))
            is TypeVariable<*> -> {
                val declaration = type.genericDeclaration
                if (declaration is Class<*>) {
                    substitution[type] ?: UnknownType
                } else {
                    // a type variable of the method, known only by its erasure
                    val erasure = javaErasure(type, substitution)
                    val index = (declaration as? Method)?.typeParameters?.indexOfFirst { it.name == type.name } ?: -1
                    TypeParameterType("F$index", false, erasure)
                }
            }
            else -> UnknownType
        }

    /**
     * The name of the class a Java [type] is erased to, or `null` if unknown:
     * the erasure of the first bound for a type variable of a method,
     * and of the substituted type argument for a type variable of a class.
     */
    private fun javaErasure(type: JavaType, substitution: JavaSubstitution): String? = when (type) {
        is Class<*> -> erasure(javaClassType(type))
        is ParameterizedType -> javaErasure(type.rawType, substitution)
        is TypeVariable<*> -> if (type.genericDeclaration is Class<*>) {
            substitution[type]?.let { erasure(it) }
        } else {
            type.bounds.firstOrNull()?.let { javaErasure(it, substitution) }
        }
        else -> null
    }

    private fun javaClassType(javaClass: Class<*>): ClassType = when {
        javaClass.isArray -> {
            val component = javaClass.componentType
            if (component.isPrimitive) primitiveArrayType(component.name) else arrayType(javaClassType(component))
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
     * A Java array type of the [component] type, like `Array<String>`.
     */
    private fun arrayType(component: MemberType): ClassType = ClassType(
        name = "kotlin/Array",
        arguments = listOf(TypeArgument(KmVariance.INVARIANT, component)),
        nullable = null
    )

    /**
     * A Java array type of a primitive type, like `IntArray` for the [primitiveName] `int`.
     */
    private fun primitiveArrayType(primitiveName: String): ClassType =
        ClassType("kotlin/${primitiveNames.getValue(primitiveName).substringAfter('/')}Array", nullable = null)

    /**
     * The overridable methods of a Java class, which cannot be reflected,
     * read from the class file, matched by the erasure of the parameter types.
     */
    private fun asmMethods(
        javaClass: Class<*>,
        ownerPackage: String,
        substitution: JavaSubstitution
    ): List<Pair<String, Overridable>> {
        val bytes = readClassBytesByMetadataName(kotlinClassName(javaClass)) ?: return emptyList()
        // the methods of a class file which cannot be parsed are unknown
        val methods = readMethods(bytes) ?: return emptyList()
        return asmMethods(methods, javaClass.packageName, ownerPackage, substitution)
    }

    /**
     * The overridable [methods] of a Java class of the [packageName], read from its class file,
     * matched by the erasure of the parameter types.
     */
    private fun asmMethods(
        methods: List<DeclaredMethod>,
        packageName: String,
        ownerPackage: String,
        substitution: JavaSubstitution
    ): List<Pair<String, Overridable>> {
        return methods.mapNotNull { method ->
            val access = method.access
            val isOverridable = access and (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL or Opcodes.ACC_PRIVATE or
                Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) == 0 &&
                (access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 || packageName == ownerPackage) &&
                !method.name.startsWith("<")
            if (!isOverridable) return@mapNotNull null
            // the method of a malformed descriptor is unknown
            val parameterTypes = argumentTypes(method.descriptor) ?: return@mapNotNull null
            val generic = method.signature?.let { genericMethodSignature(it) }
            val parameters = parameterTypes.mapIndexed { index, parameterType ->
                val parameterSignature = generic?.parameterTypes?.getOrNull(index)
                if (parameterSignature == null) asmType(parameterType) else {
                    asmType(parameterType, parameterSignature, generic, substitution)
                }
            }
            method.name to Overridable(
                receiver = null,
                parameters = parameters,
                typeParameterCount = generic?.typeParameters?.size ?: 0,
                isJava = true
            )
        }
    }

    /**
     * The parameter types of a method [descriptor], or `null` if it is malformed,
     * as ASM does not validate the descriptors when reading a class file.
     */
    private fun argumentTypes(descriptor: String): List<AsmType>? = try {
        // a `(` within the parameters is read as a method type, also as an array component
        AsmType.getArgumentTypes(descriptor).toList().takeIf { types ->
            types.none { type ->
                type.sort == AsmType.METHOD || (type.sort == AsmType.ARRAY && type.elementType.sort == AsmType.METHOD)
            }
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /**
     * Parses the generic [signature] of a method, or returns `null` if it is malformed,
     * so that the method is matched by its descriptor only, like a method without a generic signature.
     */
    private fun genericMethodSignature(signature: String): GenericMethodSignature? = try {
        GenericMethodSignature(signature)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /**
     * Resolves the type of a method parameter read from the class file,
     * its type variables, also as array components, resolved from the [generic] signature of the method:
     * the ones of the method, shadowing the ones of the class, are known only by their erasure,
     * and the ones of the class, or of its outer classes, are substituted.
     *
     * @param type the erased type, from the method descriptor.
     * @param signature the generic type, from the method signature.
     */
    private fun asmType(
        type: AsmType,
        signature: SignatureType,
        generic: GenericMethodSignature,
        substitution: JavaSubstitution
    ): MemberType = when (signature) {
        is SignatureType.Variable -> {
            val index = generic.typeParameters.indexOf(signature.name)
            if (index >= 0) {
                TypeParameterType(
                    "F$index",
                    false,
                    // the descriptor erases a type variable bounded by one of the class to the bound of the latter
                    generic.classTypeVariableBound(signature.name).let { classTypeVariable ->
                        if (classTypeVariable != null) {
                            substitution[classTypeVariable]?.let { erasure(it) }
                        } else {
                            erasure(asmType(type))
                        }
                    }
                )
            } else {
                substitution[signature.name] ?: UnknownType
            }
        }
        // an array of primitives or of classes is fully described by the descriptor,
        // like a type of a malformed class file whose signature is an array, but the descriptor is not
        is SignatureType.ArrayOf -> if (type.sort != AsmType.ARRAY || signature.component == SignatureType.Other) {
            asmType(type)
        } else {
            arrayType(asmType(AsmType.getType(type.descriptor.substring(1)), signature.component, generic, substitution))
        }
        SignatureType.Other -> asmType(type)
    }

    private fun asmType(type: AsmType): MemberType = when (type.sort) {
        AsmType.ARRAY -> if (type.dimensions == 1 && type.elementType.sort != AsmType.OBJECT) {
            primitiveArrayType(type.elementType.className)
        } else {
            arrayType(asmType(AsmType.getType(type.descriptor.substring(1))))
        }
        AsmType.OBJECT -> {
            val binaryName = type.className
            val simpleBinaryName = binaryName.substringAfterLast('.')
            // a class missing from the classpath is assumed to be nested at its `$` separators, see [assumedNestedClassName],
            // which is also a metadata name the class of this binary name is loaded by
            val assumedName = kotlinClassName(
                binaryName.substringBeforeLast('.', ""),
                assumedNestedClassName(simpleBinaryName)
            )
            ClassType(
                javaToKotlinNames[binaryName]
                    // the nesting is known from the class, as `$` can be part of a class name,
                    // so only a class whose binary name has one is loaded to tell
                    ?: (if ('$' in simpleBinaryName) loadClassByMetadataName(assumedName) else null)
                        ?.let { kotlinClassName(it) }
                    ?: assumedName,
                nullable = null
            )
        }
        else -> ClassType(primitiveNames.getValue(type.className), nullable = false, isJavaPrimitive = true)
    }

    /**
     * Parses the generic [signature] of a class, or returns `null` if it is malformed,
     * so that the class is read like one without a generic signature.
     */
    private fun genericClassSignature(signature: String): GenericClassSignature? = try {
        GenericClassSignature(signature)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /**
     * The type variables of a class known only from its class file, see [Declaration.javaClassFile],
     * mapped to the type arguments of the [supertype], or to the erasures of their bounds if the [supertype] is raw,
     * like [javaSubstitution] does for a loaded class, except for the type variables of the outer classes, unknown here.
     */
    private fun classFileSubstitution(signature: GenericClassSignature?, supertype: ClassType): JavaSubstitution {
        if (signature == null) return JavaSubstitution(emptyList())
        val typeArguments = signature.typeParameters.withIndex().associate { (index, typeVariable) ->
            typeVariable to if (supertype.isRaw) {
                lazy(LazyThreadSafetyMode.NONE) { signature.erasure(typeVariable)?.let { objectType(it) } ?: UnknownType }
            } else {
                lazyOf(supertype.arguments.getOrNull(index)?.type ?: UnknownType)
            }
        }
        return JavaSubstitution(listOf(null to typeArguments))
    }

    /**
     * The supertypes of a class known only from its class file, with the type variables of the class substituted,
     * as declared by its generic [signature], or else raw, like the reflected ones, see [javaSupertypes].
     */
    private fun classFileSupertypes(
        classFile: JavaClassFile,
        signature: GenericClassSignature?,
        substitution: JavaSubstitution
    ): List<ClassType> {
        if (signature != null) {
            val supertypes = mutableListOf<ClassType>()
            val isRead = try {
                SignatureReader(signature.signature).accept(object : SignatureVisitor(Opcodes.ASM9) {
                    override fun visitClassBound(): SignatureVisitor = ignoringSignatureVisitor
                    override fun visitInterfaceBound(): SignatureVisitor = ignoringSignatureVisitor
                    override fun visitSuperclass(): SignatureVisitor = supertypeBuilder()
                    override fun visitInterface(): SignatureVisitor = supertypeBuilder()
                    private fun supertypeBuilder() = SignatureTypeBuilder(substitution, isSupertype = true) { type ->
                        if (type is ClassType) supertypes += type
                    }
                })
                true
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: IndexOutOfBoundsException) {
                false
            }
            if (isRead) return supertypes
        }
        return (listOfNotNull(classFile.superName) + classFile.interfaces).map { objectType(it).copy(isRaw = true) }
    }

    /**
     * The type of a class of the [internalName], like `java/util/Map${'$'}Entry`, as a Java type, see [asmType].
     */
    private fun objectType(internalName: String): ClassType = asmType(AsmType.getObjectType(internalName)) as ClassType

    /**
     * The type variables of a generic class signature, with their first bounds telling their erasures.
     */
    private class GenericClassSignature(val signature: String) {

        val typeParameters = mutableListOf<String>()

        /**
         * The first bounds of the type variables, by their names:
         * the internal names of the classes, or the names of other type variables, as told by [isTypeVariable].
         */
        private val firstBounds = mutableMapOf<String, Pair<String, Boolean>>()

        /**
         * The internal name of the class the [typeVariable] is erased to,
         * or `null` if unknown, like for cyclic bounds of a malformed class file.
         */
        fun erasure(typeVariable: String): String? {
            val visited = mutableSetOf<String>()
            var current = typeVariable
            while (visited.add(current)) {
                val (name, isTypeVariable) = firstBounds[current] ?: return "java/lang/Object"
                if (!isTypeVariable) return name
                current = name
            }
            return null
        }

        init {
            SignatureReader(signature).accept(object : SignatureVisitor(Opcodes.ASM9) {

                private fun boundCollector(): SignatureVisitor {
                    val typeParameter = typeParameters.last()
                    return object : SignatureVisitor(Opcodes.ASM9) {
                        private var className = ""
                        override fun visitTypeVariable(name: String) {
                            firstBounds.putIfAbsent(typeParameter, name to true)
                        }
                        override fun visitClassType(name: String) {
                            className = name
                        }
                        override fun visitInnerClassType(name: String) {
                            className += "$$name"
                        }
                        override fun visitTypeArgument(wildcard: Char): SignatureVisitor = ignoringSignatureVisitor
                        override fun visitEnd() {
                            firstBounds.putIfAbsent(typeParameter, className to false)
                        }
                    }
                }

                override fun visitFormalTypeParameter(name: String) {
                    typeParameters += name
                }

                override fun visitClassBound(): SignatureVisitor = boundCollector()

                override fun visitInterfaceBound(): SignatureVisitor = boundCollector()

                override fun visitSuperclass(): SignatureVisitor = ignoringSignatureVisitor

                override fun visitInterface(): SignatureVisitor = ignoringSignatureVisitor

            })
        }

    }

    /**
     * Builds a type of a generic signature, passing it to [onType] when visited,
     * with the type variables substituted, matched by the erasure of their classes, like the reflected types,
     * see [javaType].
     *
     * @param isSupertype whether this is a supertype of a class, which is raw, when generic, without type arguments.
     */
    private inner class SignatureTypeBuilder(
        private val substitution: JavaSubstitution,
        private val isSupertype: Boolean = false,
        private val onType: (MemberType) -> Unit
    ) : SignatureVisitor(Opcodes.ASM9) {

        private var internalName = ""

        private var outer: ClassType? = null

        private val arguments = mutableListOf<TypeArgument>()

        override fun visitBaseType(descriptor: Char) = onType(asmType(AsmType.getType(descriptor.toString())))

        override fun visitTypeVariable(name: String) = onType(substitution[name] ?: UnknownType)

        override fun visitArrayType(): SignatureVisitor = SignatureTypeBuilder(substitution) { component ->
            onType(
                if (component is ClassType && component.isJavaPrimitive) {
                    ClassType("kotlin/${component.name.substringAfter('/')}Array", nullable = null)
                } else {
                    arrayType(component)
                }
            )
        }

        override fun visitClassType(name: String) {
            internalName = name
        }

        override fun visitInnerClassType(name: String) {
            outer = classType()
            internalName += "$$name"
            arguments.clear()
        }

        override fun visitTypeArgument() {
            arguments += TypeArgument(null, UnknownType)
        }

        override fun visitTypeArgument(wildcard: Char): SignatureVisitor = SignatureTypeBuilder(substitution) { type ->
            arguments += when {
                wildcard == EXTENDS && type is ClassType && type.name == "kotlin/Any" -> TypeArgument(null, UnknownType)
                wildcard == EXTENDS -> TypeArgument(KmVariance.OUT, type)
                wildcard == SUPER -> TypeArgument(KmVariance.IN, type)
                else -> TypeArgument(KmVariance.INVARIANT, type)
            }
        }

        override fun visitEnd() {
            val type = classType()
            onType(if (isSupertype && arguments.isEmpty() && outer == null) type.copy(isRaw = true) else type)
        }

        private fun classType(): ClassType = objectType(internalName).copy(arguments = arguments.toList(), outer = outer)

    }

    /**
     * A type of a generic signature, as far as it is needed to resolve the type variables,
     * which can be the types of parameters, or array components.
     */
    private sealed interface SignatureType {

        data class Variable(val name: String) : SignatureType

        data class ArrayOf(val component: SignatureType) : SignatureType

        /**
         * A class or a primitive type, fully described by the descriptor, as only its erasure is matched.
         */
        data object Other : SignatureType

    }

    /**
     * Collects a type of a generic signature, passing it to [onType] when visited.
     */
    private class SignatureTypeCollector(private val onType: (SignatureType) -> Unit) : SignatureVisitor(Opcodes.ASM9) {

        override fun visitTypeVariable(name: String) = onType(SignatureType.Variable(name))

        override fun visitArrayType(): SignatureVisitor = SignatureTypeCollector { onType(SignatureType.ArrayOf(it)) }

        override fun visitBaseType(descriptor: Char) = onType(SignatureType.Other)

        override fun visitClassType(name: String) = onType(SignatureType.Other)

        // the type arguments of a class type are visited by this visitor otherwise
        override fun visitTypeArgument(wildcard: Char): SignatureVisitor = ignoringSignatureVisitor

    }

    /**
     * The type parameters of a generic method signature, the generic types of its parameters,
     * and the type variables bounding the type parameters, for the type parameters whose first bound is a type variable.
     */
    private class GenericMethodSignature(signature: String) {

        val typeParameters = mutableListOf<String>()

        val parameterTypes = mutableListOf<SignatureType>()

        private val firstBounds = mutableMapOf<String, SignatureType>()

        /**
         * The type variable of the class bounding the [typeVariable] of the method, directly or through other ones,
         * or `null` if it is bounded by a class.
         */
        fun classTypeVariableBound(typeVariable: String): String? {
            val visited = mutableSetOf<String>()
            var current = typeVariable
            while (current in typeParameters) {
                if (!visited.add(current)) return null
                current = (firstBounds[current] as? SignatureType.Variable)?.name ?: return null
            }
            return current
        }

        init {
            SignatureReader(signature).accept(object : SignatureVisitor(Opcodes.ASM9) {

                private fun boundCollector(): SignatureVisitor {
                    val typeParameter = typeParameters.last()
                    return SignatureTypeCollector { firstBounds.putIfAbsent(typeParameter, it) }
                }

                override fun visitFormalTypeParameter(name: String) {
                    typeParameters += name
                }

                override fun visitClassBound(): SignatureVisitor = boundCollector()

                override fun visitInterfaceBound(): SignatureVisitor = boundCollector()

                override fun visitParameterType(): SignatureVisitor = SignatureTypeCollector { parameterTypes += it }

                override fun visitReturnType(): SignatureVisitor = ignoringSignatureVisitor

                override fun visitExceptionType(): SignatureVisitor = ignoringSignatureVisitor

            })
        }

    }

    private companion object {

        /**
         * The characters of a module name replaced with `_` in the mangled JVM names of its internal members.
         */
        val nonIdentifierCharacters = Regex("[^\\p{L}\\p{Digit}]")

        /**
         * Visits a part of a generic signature which is not needed, its default methods doing nothing.
         */
        val ignoringSignatureVisitor = object : SignatureVisitor(Opcodes.ASM9) {}

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

    }

}

/**
 * The name of the class within its package, like `Map.Entry`, where the nested classes are separated with `.`,
 * resolved from the declaring classes, as `$` of the binary name can also be part of a class name.
 */
internal fun Class<*>.nestedClassName(): String {
    val declaringClass = try {
        declaringClass
    } catch (_: LinkageError) {
        // the outer class is missing from the classpath, like in a shaded or shrunk jar
        return nestedClassNameWithoutDeclaringClass()
    }
    return declaringClass?.let { "${it.nestedClassName()}.$simpleName" } ?: name.substringAfterLast('.')
}

/**
 * The [nestedClassName] of a class whose declaring class cannot be loaded, neither its simple name reflected,
 * read from the `InnerClasses` attribute of its class file, which lists the enclosing classes as well,
 * or, if it cannot be read, by the [assumedNestedClassName].
 */
private fun Class<*>.nestedClassNameWithoutDeclaringClass(): String {
    val internalName = name.replace('.', '/')
    // the internal names of the nested classes mapped to their outer class and simple name
    val innerClasses = mutableMapOf<String, Pair<String, String>>()
    (classLoader ?: ClassLoader.getSystemClassLoader()).readClassBytes(name)?.let { bytes ->
        try {
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                    if (outerName != null && innerName != null) innerClasses[name] = outerName to innerName
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        } catch (_: Exception) {
            // judged by the name only
        }
    }
    if (internalName !in innerClasses) return assumedNestedClassName(name.substringAfterLast('.'))
    fun nestedName(name: String): String = innerClasses[name]
        ?.let { (outerName, innerName) -> "${nestedName(outerName)}.$innerName" }
        ?: name.substringAfterLast('/')
    return nestedName(internalName)
}

/**
 * The name of a class within its package, like `Outer.Nested`, assumed from its [simpleBinaryName],
 * like `Outer${'$'}Nested`, when the nesting cannot be told from the class or its class file:
 * every `$` following another character of the name separates the nested classes,
 * while the one starting a name, like of `Outer${'$'}${'$'}serializer`, is a part of it.
 */
private fun assumedNestedClassName(simpleBinaryName: String): String =
    simpleBinaryName.replace(nestedClassSeparator, ".")

/**
 * A `$` of a binary name assumed to separate nested classes: one following another character than `$`,
 * and followed by any character.
 */
private val nestedClassSeparator = Regex("(?<=[^$])\\$(?=.)")

/**
 * The Kotlin class name, like `kotlin/collections/Map.Entry`, of a fully qualified name, like of a built-in type,
 * whose package segments start with a lowercase letter, unlike the names of the classes.
 */
internal fun kotlinClassName(qualifiedName: String): String {
    val segments = qualifiedName.split('.')
    val packageSize = segments.indexOfFirst { it.first().isUpperCase() }.coerceAtLeast(0)
    return kotlinClassName(
        segments.take(packageSize).joinToString("."),
        segments.drop(packageSize).joinToString(".")
    )
}

internal fun kotlinClassName(javaClass: Class<*>): String =
    kotlinClassName(javaClass.packageName, javaClass.nestedClassName())

/**
 * The Kotlin class name, like `java/util/Map.Entry`, of the [packageName] and the [name] of a class within it,
 * where the nested classes are separated with `.`.
 */
internal fun kotlinClassName(packageName: String, name: String): String =
    if (packageName.isEmpty()) name else "${packageName.replace('.', '/')}/$name"
