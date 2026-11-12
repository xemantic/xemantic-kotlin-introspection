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

import com.xemantic.kotlin.introspection.test.ContextParametersSample
import com.xemantic.kotlin.introspection.test.GenericInnerTypes
import com.xemantic.kotlin.introspection.test.KeywordMembersSample
import com.xemantic.kotlin.introspection.test.NonGenericInnerTypes
import com.xemantic.kotlin.introspection.test.ReflectedValueParametersSample
import com.xemantic.kotlin.introspection.test.other.JavaBoxedTriplePut
import com.xemantic.kotlin.introspection.test.other.JavaConvertedShapes
import com.xemantic.kotlin.introspection.test.other.JavaGenericPutImplementation
import com.xemantic.kotlin.introspection.test.other.JavaLabelBase
import com.xemantic.kotlin.introspection.test.other.JavaNullableMergeMap
import com.xemantic.kotlin.introspection.test.other.JavaUnnamedParameters
import com.xemantic.kotlin.introspection.test.other.Upper.JavaUpperCasePackage
import com.xemantic.kotlin.test.assert
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmVariance
import kotlin.metadata.isDefinitelyNonNull
import kotlin.metadata.isNullable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.typeOf
import kotlin.test.Test

/**
 * The members of the built-in types described by kotlin-reflect, converted to the Kotlin metadata, and rendered,
 * exercised on a regular class, as no built-in member has such parameters.
 */
class ReflectedDeclarationsTest {

    private val introspector = DefaultKotlinIntrospector(scanEntireClasspath = false)

    private fun function(name: String): KFunction<*> =
        ReflectedValueParametersSample::class.memberFunctions.single { it.name == name }

    @Test
    fun `should not treat real parameter names starting with arg as synthetic`() {
        // given
        val functions = listOf(function("format"), function("call"))

        // when
        val synthetic = functions.map { hasSyntheticParameterNames(it) }

        // then
        assert(synthetic == listOf(false, false))
    }

    @Test
    fun `should treat the parameter names of a Java method compiled without them as synthetic`() {
        // given - a test fixture, not the JDK, which a build could compile with the -parameters option
        val indent = JavaUnnamedParameters::class.memberFunctions.single { it.name == "indent" }

        // when
        val synthetic = hasSyntheticParameterNames(indent)

        // then
        assert(synthetic)
    }

    @Test
    fun `should tell member whose annotations cannot be read unsupported by compiler`() {
        // given - like a member which kotlin-reflect cannot describe, failing with KotlinReflectionInternalError
        val toString: KFunction<String> = Any::toString
        val unreadable = object : KFunction<String> by toString {
            override val annotations: List<Annotation> get() = throw NoClassDefFoundError("Unreadable")
        }

        // when
        val unsupported = isUnsupportedByCompiler(unreadable)

        // then
        assert(unsupported)
    }

    @Test
    fun `should tell JVM member of built-in type which the compiler hides unsupported by compiler`() {
        // given - kotlin-reflect lists Enum.getDeclaringClass() without telling that Kotlin hides it
        val getDeclaringClass = Enum::class.memberFunctions.single { it.name == "getDeclaringClass" }

        // when
        val unsupported = isUnsupportedByCompiler(getDeclaringClass)

        // then
        assert(unsupported)
    }

    @Test
    fun `should hide the JDK methods of the built-in types which the compiler hides`() {
        // given - the hidden JDK methods of kotlin-reflect, the same as of the compiler, of the classes it does not leave out
        val signatures = Class.forName("kotlin.reflect.jvm.internal.impl.builtins.jvm.JvmBuiltInsSignatures")
        val instance = signatures.getField("INSTANCE").get(null)
        val hidden = signatures.getMethod("getHIDDEN_METHOD_SIGNATURES").invoke(instance) as Set<*>

        // when
        val notFinal = hidden.filterIsInstance<String>().filter { signature ->
            val className = signature.substringBefore('.').replace('/', '.')
            !java.lang.reflect.Modifier.isFinal(Class.forName(className).modifiers)
        }

        // then
        assert(notFinal.toSet() == HIDDEN_JDK_METHODS)
    }

    @Test
    fun `should enhance the types of the JDK methods which the compiler enhances as definitely not nullable`() {
        // given - the enhanced JDK signatures of kotlin-reflect, the same as of the compiler, of the classes which are not final
        val info = Class.forName("kotlin.reflect.jvm.internal.impl.load.java.typeEnhancement.PredefinedEnhancementInfoKt")
        val bySignature = info.getDeclaredField("PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE")
            .apply { isAccessible = true }.get(null) as Map<*, *>
        fun Any.property(name: String): Any? = javaClass.getMethod(name).invoke(this)
        fun definitelyNotNull(typeInfo: Any?): Set<Int> = (typeInfo?.property("getMap") as Map<*, *>?).orEmpty()
            .filterValues { it?.property("getDefinitelyNotNull") == true }.keys.mapTo(HashSet()) { it as Int }

        // when
        val enhanced = bySignature.mapKeys { it.key as String }.filterKeys { signature ->
            val className = signature.substringBefore('.').replace('/', '.')
            !java.lang.reflect.Modifier.isFinal(Class.forName(className).modifiers)
        }.mapValues { (_, functionInfo) ->
            val parameters = functionInfo!!.property("getParametersInfo") as List<*>
            // the return type at -1
            (parameters.withIndex().associate { (position, typeInfo) -> position to definitelyNotNull(typeInfo) } +
                (-1 to definitelyNotNull(functionInfo.property("getReturnTypeInfo")))).filterValues { it.isNotEmpty() }
        }.filterValues { it.isNotEmpty() }

        // then
        assert(enhanced == DEFINITELY_NOT_NULL_JDK_TYPES)
    }

    @Test
    fun `should tell the JDK methods whose return types the compiler enhances`() {
        // given - the enhanced JDK signatures of kotlin-reflect, the same as of the compiler, of the classes which are not final
        val info = Class.forName("kotlin.reflect.jvm.internal.impl.load.java.typeEnhancement.PredefinedEnhancementInfoKt")
        val bySignature = info.getDeclaredField("PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE")
            .apply { isAccessible = true }.get(null) as Map<*, *>
        fun Any.property(name: String): Any? = javaClass.getMethod(name).invoke(this)

        // when - the nullability of the return type itself, at 0, enhanced either way
        val enhanced = bySignature.filter { (signature, functionInfo) ->
            val className = (signature as String).substringBefore('.').replace('/', '.')
            val returnTypeInfo = functionInfo!!.property("getReturnTypeInfo")
            !java.lang.reflect.Modifier.isFinal(Class.forName(className).modifiers) &&
                (returnTypeInfo?.property("getMap") as Map<*, *>?).orEmpty()[0]?.property("getNullability") != null
        }.keys.mapTo(HashSet()) { it as String }

        // then
        assert(enhanced == ENHANCED_RETURN_JDK_METHODS)
    }

    @Test
    fun `should tell the JDK methods whose return types the compiler enhances as nullable`() {
        // given - the enhanced JDK signatures of kotlin-reflect, the same as of the compiler, of the classes which are not final
        val info = Class.forName("kotlin.reflect.jvm.internal.impl.load.java.typeEnhancement.PredefinedEnhancementInfoKt")
        val bySignature = info.getDeclaredField("PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE")
            .apply { isAccessible = true }.get(null) as Map<*, *>
        fun Any.property(name: String): Any? = javaClass.getMethod(name).invoke(this)

        // when - the return type itself, at 0, enhanced as nullable
        val nullable = bySignature.filter { (signature, functionInfo) ->
            val returnTypeInfo = functionInfo!!.property("getReturnTypeInfo")
            signature in ENHANCED_RETURN_JDK_METHODS &&
                (returnTypeInfo?.property("getMap") as Map<*, *>?).orEmpty()[0]?.property("getNullability")?.toString() == "NULLABLE"
        }.keys.mapTo(HashSet()) { it as String }

        // then
        assert(nullable == NULLABLE_RETURN_JDK_METHODS)
    }

    @Test
    fun `should tell the JDK methods whose value parameters the compiler enhances as not nullable`() {
        // given - the enhanced JDK signatures of kotlin-reflect, the same as of the compiler, of the classes which are not final
        val info = Class.forName("kotlin.reflect.jvm.internal.impl.load.java.typeEnhancement.PredefinedEnhancementInfoKt")
        val bySignature = info.getDeclaredField("PREDEFINED_FUNCTION_ENHANCEMENT_INFO_BY_SIGNATURE")
            .apply { isAccessible = true }.get(null) as Map<*, *>
        fun Any.property(name: String): Any? = javaClass.getMethod(name).invoke(this)

        // when - the value parameters whose types themselves, at 0, are enhanced as not nullable
        val enhanced = bySignature.mapKeys { it.key as String }.filterKeys { signature ->
            val className = signature.substringBefore('.').replace('/', '.')
            !java.lang.reflect.Modifier.isFinal(Class.forName(className).modifiers)
        }.mapValues { (_, functionInfo) ->
            (functionInfo!!.property("getParametersInfo") as List<*>).withIndex().filter { (_, typeInfo) ->
                (typeInfo?.property("getMap") as Map<*, *>?).orEmpty()[0]?.property("getNullability")?.toString() == "NOT_NULL"
            }.mapTo(HashSet()) { it.index }
        }.filterValues { it.isNotEmpty() }

        // then
        assert(enhanced == NOT_NULL_PARAMETER_JDK_METHODS)
    }

    @Test
    fun `should take type parameter of class as platform type in JDK method unless the compiler enhances it`() {
        // given - K2 takes push(null) of a LinkedList<String>, and addElement(null) of a Vector<String>,
        // but neither accept(null) of a Consumer<String>, nor add(null) of a MutableList<String>
        fun method(kClass: KClass<*>, name: String) = kClass.java.methods.single { it.name == name && it.parameterCount == 1 }

        // when
        val notNull = listOf(
            method(java.util.LinkedList::class, "push"),
            method(java.util.Vector::class, "addElement"),
            method(java.util.ArrayDeque::class, "addFirst"),
            method(java.util.function.Consumer::class, "accept"),
            method(java.util.List::class, "add"),
        ).map { isNotNullClassTypeParameter(it, 0) }

        // then
        assert(notNull == listOf(false, false, false, true, true))
    }

    @Test
    fun `should take type parameter of class as platform type in JDK method overriding enhanced one unless purely implementing built-in type`() {
        // given - K2 enhances the key of compute of java.util.Map, and of its override in java.util.HashMap,
        // the pure implementation of MutableMap, but not in java.util.Hashtable, so that compute(null, …) of a Hashtable compiles
        val methods = listOf(java.util.HashMap::class.java, java.util.Hashtable::class.java).map { clazz ->
            clazz.getMethod("compute", Any::class.java, java.util.function.BiFunction::class.java)
        }

        // when
        val notNull = methods.map { isNotNullClassTypeParameter(it, 0) }

        // then
        assert(notNull == listOf(true, false))
    }

    @Test
    fun `should tell return type of JDK method overriding enhanced one as platform type unless purely implementing built-in type`() {
        // given - K2 enhances the return type of computeIfAbsent of java.util.Map, inherited by java.util.HashMap,
        // but not by java.util.Hashtable, returning V!
        val functions = listOf(java.util.HashMap::class, java.util.Hashtable::class).map { kClass ->
            kClass.declaredMemberFunctions.single { it.name == "computeIfAbsent" }
        }

        // when
        val platform = functions.map { it.returnsPlatformType }

        // then
        assert(platform == listOf(false, true))
    }

    @Test
    fun `should take type parameter of class as in Kotlin in converted JDK method overriding member of built-in type purely implemented`() {
        // given - kotlin-reflect tells the E of add of java.util.ArrayList, the pure implementation of MutableList, not a platform type,
        // unlike the converted method, whose class tells it, unlike for the add of java.util.Vector
        val methods = listOf(java.util.ArrayList::class.java, java.util.Vector::class.java).map { clazz ->
            clazz.getMethod("add", Any::class.java)
        }

        // when
        val notNull = methods.map { isNotNullClassTypeParameter(it, 0, isConverted = true) }

        // then
        assert(notNull == listOf(true, false))
    }

    @Test
    fun `should convert return type of JDK method enhanced as nullable to nullable, also of its override`() {
        // given - K2 enhances get() of java.lang.ref.Reference to return T?, also of its override in java.lang.ref.SoftReference
        val typeParameters = ReflectedTypeParameters.classTypeParameters(listOf(KmTypeParameter("T", 0, KmVariance.INVARIANT)))
        val functions = listOf(java.lang.ref.Reference::class, java.lang.ref.SoftReference::class).map { kClass ->
            kClass.declaredMemberFunctions.single { it.name == "get" }
        }

        // when
        val nullable = functions.map { it.toKmFunction(typeParameters).returnType.isNullable }

        // then
        assert(nullable == listOf(true, true))
    }

    @Test
    fun `should tell return type of override of JDK method enhanced by the compiler of the same JVM descriptor not platform type`() {
        // given - get() of java.lang.ref.SoftReference overrides the one of java.lang.ref.Reference, inheriting its T?
        val get = java.lang.ref.SoftReference::class.declaredMemberFunctions.single { it.name == "get" }

        // when
        val platform = get.returnsPlatformType

        // then
        assert(!platform)
    }

    @Test
    fun `should tell platform return type of Java method taking boxed type next to one implementing Kotlin member taking primitive type`() {
        // given - kotlin-reflect takes put(Integer) for an override of the put(key: Int): String of PrimitivePut, returning String,
        // which only the inherited put(int) implements, while the compiler sees a method of its own, returning String!
        val boxed = com.xemantic.kotlin.introspection.test.other.JavaBoxedPutBelow::class.declaredMemberFunctions.single { it.name == "put" }
        val primitive = com.xemantic.kotlin.introspection.test.other.JavaPrimitivePutImplementation::class.declaredMemberFunctions
            .single { it.name == "put" }

        // when
        val platform = listOf(boxed, primitive).map { it.returnsPlatformType }

        // then
        assert(platform == listOf(true, false))
    }

    @Test
    fun `should not convert return type of JDK method enhanced as not nullable to nullable`() {
        // given
        val typeParameters = ReflectedTypeParameters.classTypeParameters(listOf(KmTypeParameter("T", 0, KmVariance.INVARIANT)))
        val get = java.util.function.Supplier::class.memberFunctions.single { it.name == "get" }

        // when
        val nullable = get.toKmFunction(typeParameters).returnType.isNullable

        // then
        assert(!nullable)
    }

    @Test
    fun `should tell platform return type of JDK method not enhanced by the compiler`() {
        // given - kotlin-reflect tells both as T!, while the compiler enhances the one of Supplier as T
        val call = java.util.concurrent.Callable::class.memberFunctions.single { it.name == "call" }
        val get = java.util.function.Supplier::class.memberFunctions.single { it.name == "get" }

        // when
        val platform = listOf(call, get).map { it.returnsPlatformType }

        // then
        assert(platform == listOf(true, false))
    }

    @Test
    fun `should enhance only value parameters of methods of java util Map as definitely not nullable`() {
        // given - the enhancement is applied to the value parameters of the methods of java.util.Map, and of their overrides,
        // so that an entry of another class, or of a return type, at -1, would be dropped, and needs to be implemented first
        val signatures = DEFINITELY_NOT_NULL_JDK_TYPES

        // when
        val owners = signatures.keys.mapTo(HashSet()) { it.substringBefore('.') }
        val positions = signatures.values.flatMapTo(HashSet()) { it.keys }

        // then
        assert(owners == setOf("java/util/Map"))
        assert(positions.all { it >= 0 })
    }

    @Test
    fun `should convert type parameter of JDK method enhanced as not nullable to definitely not nullable`() {
        // given - K2 enhances the signature of java.util.Map.merge, overridden by java.util.AbstractMap, to take the value as V & Any
        val merge = java.util.HashMap::class.memberFunctions.single { it.name == "merge" }
        val typeParameters = ReflectedTypeParameters.classTypeParameters(
            listOf(KmTypeParameter("K", 0, KmVariance.INVARIANT), KmTypeParameter("V", 1, KmVariance.INVARIANT))
        )

        // when
        val types = merge.toKmFunction(typeParameters).valueParameters.map { it.type }

        // then
        assert(types.map { it.isDefinitelyNonNull } == listOf(false, true, false))
        assert(types[2].arguments.map { it.type?.isDefinitelyNonNull } == listOf(true, true, false))
    }

    @Test
    fun `should not convert type parameter of Java override of mapped member to definitely not nullable`() {
        // given - kotlin-reflect gives the E of TreeSet.add as not a platform type, like the one of MutableSet.add it overrides,
        // which K2 does not enhance as definitely not nullable
        val add = java.util.TreeSet::class.memberFunctions.single { it.name == "add" }
        val typeParameters = ReflectedTypeParameters.classTypeParameters(listOf(KmTypeParameter("E", 0, KmVariance.INVARIANT)))

        // when
        val type = add.toKmFunction(typeParameters).valueParameters.single().type

        // then
        assert(!type.isDefinitelyNonNull)
    }

    @Test
    fun `should render element type of Java vararg as nullable like its platform type`() {
        // given - Object... is seen in Kotlin as vararg of Any!, accepting null elements
        val format = JavaUnnamedParameters::class.memberFunctions.single { it.name == "format" }

        // when
        val rendered = introspector.formatReflectedFunction(format)

        // then
        // without the owner, which the user code might subclass, a member is not rendered as open
        assert(rendered == "fun format(vararg arg0: Any?): String")
    }

    @Test
    fun `should render Java parameter of platform type as nullable`() {
        // given - Object is seen in Kotlin as Any!, accepting null
        val describe = JavaUnnamedParameters::class.memberFunctions.single { it.name == "describe" }

        // when
        val rendered = introspector.formatReflectedFunction(describe)

        // then - the return type, which Kotlin lets be used as not nullable, stays so,
        // and without the owner, which the user code might subclass, the member is not rendered as open
        assert(rendered == "fun describe(arg0: Any?): String")
    }

    @Test
    fun `should render Java type parameter bound of platform type as nullable`() {
        // given - the implicit bound of <T> is Object, seen in Kotlin as Any!
        val functions = JavaUnnamedParameters::class.memberFunctions.filter { it.name == "first" || it.name == "listOf" }

        // when
        val rendered = functions.sortedBy { it.name }.map { introspector.formatReflectedFunction(it) }

        // then
        assert(
            rendered == listOf(
                "fun <T> first(arg0: T?): T",
                "fun <T> listOf(vararg arg0: T?): List<T>"
            )
        )
    }

    @Test
    fun `should tell supertypes of JDK interface by its generic signature`() {
        // given - kotlin-reflect describes java.util.List as the built-in List, of the supertypes of that one
        val clazz = java.util.List::class.java

        // when
        val supertypes = clazz.javaSignatureSupertypes { kotlinClassName(it) }

        // then - java.util.SequencedCollection<E> since JDK 21, or java.util.Collection<E>, of its type parameter E
        val supertype = supertypes?.singleOrNull()
        val name = (supertype?.classifier as? KmClassifier.Class)?.name
        assert(name == "java/util/SequencedCollection" || name == "java/util/Collection")
        assert((supertype?.arguments?.singleOrNull()?.type?.classifier as? KmClassifier.TypeParameter)?.id == 0)
    }

    @Test
    fun `should not tell supertypes of JDK class by its generic signature when they take other type arguments than its type parameters`() {
        // given - java.lang.Integer is a Comparable<Integer>
        val clazz = java.lang.Integer::class.java

        // when
        val supertypes = clazz.javaSignatureSupertypes { kotlinClassName(it) }

        // then
        assert(supertypes == null)
    }

    @Test
    fun `should read declaration of Kotlin class from its metadata`() {
        // given
        val clazz = GenericInnerTypes::class.java

        // when
        val kmClass = readKmClass(clazz)

        // then
        assert(kmClass?.name == "com/xemantic/kotlin/introspection/test/GenericInnerTypes")
    }

    @Test
    fun `should read no declaration of Java class without Kotlin metadata`() {
        // when
        val kmClass = readKmClass(java.util.ArrayList::class.java)

        // then
        assert(kmClass == null)
    }

    @Test
    fun `should read Java class type parameter bound of platform type as nullable`() {
        // given - the implicit bound of the K and V of java.util.HashMap is Object, seen in Kotlin as Any!, admitting null
        val kClass = java.util.HashMap::class

        // when
        val kmClass = kClass.toKmJavaClassHeader("java/util/HashMap") { kotlinClassName(it) }

        // then - so that its V used as definitely not nullable, like the V & Any of merge, is rendered so
        assert(kmClass.typeParameters.all { it.upperBounds.isNotEmpty() && it.upperBounds.all { bound -> bound.isNullable } })
        assert(notNullableTypeParameters(kmClass.typeParameters).isEmpty())
    }

    @Test
    fun `should read Java class type parameter bound of parameterized platform type as nullable`() {
        // given - the bound of K extends Enum<K> of java.util.EnumMap is seen in Kotlin as Enum<K!>!, admitting null
        val kClass = java.util.EnumMap::class

        // when
        val kmClass = kClass.toKmJavaClassHeader("java/util/EnumMap") { kotlinClassName(it) }

        // then
        val bound = kmClass.typeParameters.first().upperBounds.single()
        assert((bound.classifier as? KmClassifier.Class)?.name == "kotlin/Enum" && bound.isNullable)
        assert(notNullableTypeParameters(kmClass.typeParameters).isEmpty())
    }

    @Test
    fun `should render JDK parameter of class type parameter as not nullable`() {
        // given - E of add of a java.util.ArrayList<String> accepts no null in K2, as the receiver fixes E
        val add = java.util.ArrayList::class.memberFunctions.single { it.name == "add" && it.parameters.size == 2 }
        val typeParameters = ReflectedTypeParameters.classTypeParameters(listOf(KmTypeParameter("E", 0, KmVariance.INVARIANT)))

        // when
        val type = add.toKmFunction(typeParameters).valueParameters.single().type

        // then
        assert(!type.isNullable && (type.classifier as? KmClassifier.TypeParameter)?.id == 0)
    }

    @Test
    fun `should render Java parameter of class type parameter as nullable`() {
        // given - T! of a JavaLabelBase<String>, not of the JDK, is a platform type in K2, getLabel(null) compiles
        val getLabel = JavaLabelBase::class.memberFunctions.single { it.name == "getLabel" }
        val typeParameters = ReflectedTypeParameters.classTypeParameters(listOf(KmTypeParameter("T", 0, KmVariance.INVARIANT)))

        // when
        val type = getLabel.toKmFunction(typeParameters).valueParameters.single().type

        // then
        assert(type.isNullable && (type.classifier as? KmClassifier.TypeParameter)?.id == 0)
    }

    @Test
    fun `should render all parameters of Java member overriding no Kotlin one it is taken for as nullable`() {
        // given - kotlin-reflect takes put(Integer, String, Integer) for an override of put(a: Int, b: String, c: Int),
        // taking (int, String, int) on the JVM, so that it overrides nothing, and put(null, null, null) compiles
        val put = JavaBoxedTriplePut::class.memberFunctions.single { function ->
            function.name == "put" && function.javaMethod?.parameterTypes?.first() == Int::class.javaObjectType
        }

        // when
        val nullable = put.toKmFunction(ReflectedTypeParameters.classTypeParameters(emptyList())).valueParameters.map { it.type.isNullable }

        // then
        assert(nullable == listOf(true, true, true))
    }

    @Test
    fun `should keep parameters of Java member implementing Kotlin one not nullable`() {
        // given - put(int, String, int) implements put(a: Int, b: String, c: Int), taking String as declared there
        val put = JavaBoxedTriplePut::class.memberFunctions.single { function ->
            function.name == "put" && function.javaMethod?.parameterTypes?.first() == Int::class.javaPrimitiveType
        }

        // when
        val nullable = put.toKmFunction(ReflectedTypeParameters.classTypeParameters(emptyList())).valueParameters.map { it.type.isNullable }

        // then
        assert(nullable == listOf(false, false, false))
    }

    @Test
    fun `should render parameter of Java member implementing Kotlin one of type argument given by Java as nullable`() {
        // given - the Integer of GenericPut<Integer> is Int! in K2, so that put(null) compiles
        val put = JavaGenericPutImplementation::class.memberFunctions.single { it.name == "put" }

        // when
        val type = put.toKmFunction(ReflectedTypeParameters.classTypeParameters(emptyList())).valueParameters.single().type

        // then
        assert(type.isNullable)
    }

    @Test
    fun `should render Java collection of contravariant wildcard as mutable collection`() {
        // given - Collection<? super T> is seen in Kotlin as (Mutable)Collection<in T!>!,
        // while Collection<in T> conflicts with the out variance of the read-only Collection<out E>
        val functions = JavaUnnamedParameters::class.memberFunctions.filter { it.name == "drainTo" || it.name == "addInts" }

        // when
        val rendered = functions.sortedBy { it.name }.map { introspector.formatReflectedFunction(it) }

        // then - Map<K, out V> takes an in projection of its invariant K, but not of its V
        assert(
            rendered == listOf(
                "fun addInts(arg0: MutableList<in Int>?)",
                "fun <T> drainTo(arg0: MutableCollection<in T>?, arg1: MutableMap<String, in T>?): Int"
            )
        )
    }

    @Test
    fun `should describe by kotlin-reflect every member the built-in declarations declare`() {
        // given - the built-in types of the .kotlin_builtins resources, not the unsigned ones, which have class files
        val declarations = readKotlinBuiltinClasses()
        val builtinTypes = DefaultKotlinIntrospector.builtinTypes
            .filterKeys { !it.substringAfterLast('.').startsWith("Mutable") }
            .mapKeys { (qualifiedName, _) -> kotlinClassName(qualifiedName) }
            .filterKeys { it in declarations }

        // when
        val missing = builtinTypes.mapValues { (name, kClass) ->
            val reflected = kClass.toKmBuiltinClass(name)
            declarations.getValue(name).memberSignatures() - reflected.memberSignatures()
        }.filterValues { it.isNotEmpty() }

        // then - like a future JDK overloading a mapped member, like a Java-only List.indexOf(Object, int),
        // moving the built-in indexOf(element: E) to the JVM-only members by its name, see javaOnlyFunctions
        assert(builtinTypes.size > 30)
        assert(missing.isEmpty())
    }

    /**
     * The signatures of the functions and properties of this class, like `indexOf(E)`,
     * naming the type parameters, whose ids differ between the `.kotlin_builtins` resources and kotlin-reflect.
     */
    private fun KmClass.memberSignatures(): Set<String> {
        fun KmType?.signature(typeParameters: List<KmTypeParameter>): String {
            if (this == null) return "*"
            val name = when (val classifier = classifier) {
                is KmClassifier.Class -> classifier.name
                is KmClassifier.TypeAlias -> classifier.name
                // an id out of scope, or shared, is told by its id, showing up in the difference rather than failing
                is KmClassifier.TypeParameter ->
                    typeParameters.filter { it.id == classifier.id }.singleOrNull()?.name ?: "?${classifier.id}"
            }
            val arguments = arguments.joinToString(", ") { it.type.signature(typeParameters) }
            return if (arguments.isEmpty()) name else "$name<$arguments>"
        }
        val functions = functions.map { function ->
            val typeParameters = this.typeParameters + function.typeParameters
            function.receiverParameterType?.let { "${it.signature(typeParameters)}." }.orEmpty() + function.name +
                function.valueParameters.joinToString(", ", "(", ")") { it.type.signature(typeParameters) }
        }
        val properties = properties.map { property ->
            val typeParameters = this.typeParameters + property.typeParameters
            property.receiverParameterType?.let { "${it.signature(typeParameters)}." }.orEmpty() + property.name
        }
        return (functions + properties).toSet()
    }

    @Test
    fun `should leave parameterless Java-only members of built-in type described by kotlin-reflect to the JVM-only ones`() {
        // when - like fillInStackTrace(), getLocalizedMessage(), or printStackTrace(), declared next to printStackTrace(arg0)
        val throwable = Throwable::class.toKmBuiltinClass("kotlin/Throwable")
        val collection = Collection::class.toKmBuiltinClass("kotlin/collections/Collection")

        // then - like stream(), referring to a Java type, which no built-in declaration does
        assert(throwable.functions.map { it.name }.isEmpty())
        assert(collection.functions.map { it.name }.sorted() == listOf("contains", "containsAll", "isEmpty", "iterator"))
    }

    @Test
    fun `should degrade vararg of unknown array type to the array type`() {
        // given - no such vararg type today, but a future standard library could declare one
        val arrayType = KmType().also { it.classifier = KmClassifier.Class("kotlin/FutureArray") }

        // when
        val elementType = varargElementType(arrayType)

        // then
        assert(elementType === arrayType)
    }

    @Test
    fun `should render vararg of nullable type as its element type`() {
        // when
        val rendered = introspector.formatReflectedFunction(function("format"))

        // then
        assert(rendered == "fun format(vararg args: Any?): String")
    }

    @Test
    fun `should render vararg of primitive type as its element type`() {
        // when
        val rendered = introspector.formatReflectedFunction(function("sum"))

        // then
        assert(rendered == "fun sum(vararg ints: Int): Int")
    }

    @Test
    fun `should render vararg of unsigned type as its element type`() {
        // when
        val rendered = introspector.formatReflectedFunction(function("sumUnsigned"))

        // then
        assert(rendered == "fun sumUnsigned(vararg uints: UInt): UInt")
    }

    @Test
    fun `should render optional parameter with default value marker`() {
        // when
        val rendered = introspector.formatReflectedFunction(function("join"))

        // then
        assert(rendered == "fun join(vararg parts: String, separator: String = …): String")
    }

    @Test
    fun `should escape names of function and parameters like hard keywords or not as identifiers`() {
        // when
        val rendered = introspector.formatReflectedFunction(KeywordMembersSample::class.memberFunctions.single { it.name == "fun" })

        // then
        assert(rendered == "fun `fun`(`if`: Int, `when`: (`in`: Int, `my name`: String) -> Unit)")
    }

    @Test
    fun `should escape type parameter named like hard keyword`() {
        // when
        val rendered = introspector.formatReflectedFunction(KeywordMembersSample::class.memberFunctions.single { it.name == "return" })

        // then
        assert(rendered == "fun <`class`> `return`(value: `class`): `class`")
    }

    @Test
    fun `should escape property named like hard keyword`() {
        // when
        val rendered = introspector.formatReflectedProperty(KeywordMembersSample::class.memberProperties.single { it.name == "typeof" })

        // then
        assert(rendered == listOf("val `typeof`: String"))
    }

    @Test
    fun `should escape class name not being an identifier`() {
        // when
        val rendered = introspector.formatReflectedFunction(KeywordMembersSample::class.memberFunctions.single { it.name == "nested" })

        // then
        assert(rendered == "fun nested(): com.xemantic.kotlin.introspection.test.KeywordMembersSample.`interface`")
    }

    @Test
    fun `should render context parameters of function before the declaration`() {
        // given
        val run = ContextParametersSample::class.memberFunctions.filter { it.name == "run" }

        // when
        val rendered = run.map { introspector.formatReflectedFunction(it) }.sorted()

        // then
        assert(rendered == listOf("context(log: com.xemantic.kotlin.introspection.test.Logger) fun run()", "fun run()"))
    }

    @Test
    fun `should render extension receiver of member extension function`() {
        // given
        val decorate = ContextParametersSample::class.declaredMemberExtensionFunctions.single { it.name == "decorate" }

        // when
        val rendered = introspector.formatReflectedFunction(decorate)

        // then
        assert(rendered == "context(log: com.xemantic.kotlin.introspection.test.Logger) fun String.decorate(): String")
    }

    @Test
    fun `should render context parameters and extension receiver of property`() {
        // given - kotlin-reflect lists them among the members, but not among the member properties
        val properties = ContextParametersSample::class.members.filterIsInstance<KProperty<*>>().sortedBy { it.name }

        // when
        val rendered = properties.map { introspector.formatReflectedProperty(it) }

        // then
        assert(
            rendered == listOf(
                listOf("context(log: com.xemantic.kotlin.introspection.test.Logger) val String.decorated: String"),
                listOf("context(log: com.xemantic.kotlin.introspection.test.Logger) val label: String")
            )
        )
    }

    @Test
    fun `should render all context parameters of function`() {
        // when
        val rendered = introspector.formatReflectedFunction(ContextParametersSample::class.memberFunctions.single { it.name == "process" })

        // then
        assert(
            rendered == "context(log: com.xemantic.kotlin.introspection.test.Logger, c: com.xemantic.kotlin.introspection.test.Ctx) " +
                "fun <T> process(item: T): List<T>"
        )
    }

    @Test
    fun `should render anonymous context parameter as underscore and escape keyword context parameter name`() {
        // given
        val functions = listOf("anonymous", "keyword").map { name ->
            ContextParametersSample::class.memberFunctions.single { it.name == name }
        }

        // when
        val rendered = functions.map { introspector.formatReflectedFunction(it) }

        // then
        assert(
            rendered == listOf(
                "context(_: com.xemantic.kotlin.introspection.test.Logger) fun anonymous()",
                "context(`in`: com.xemantic.kotlin.introspection.test.Logger) fun keyword()"
            )
        )
    }

    @Test
    fun `should render inner class type with the type arguments of its outer class`() {
        // given - kotlin-reflect lists the type arguments of the outer class after the ones of the inner class
        val functions = GenericInnerTypes::class.memberFunctions.sortedBy { it.name }

        // when
        val rendered = functions.filter { it.name in setOf("accept", "cursor", "nullableCursor") }
            .map { introspector.formatReflectedFunction(it) }

        // then
        assert(
            rendered == listOf(
                "fun accept(cursor: com.xemantic.kotlin.introspection.test.GenericInnerOuter<Long>.Inner<com.xemantic.kotlin.introspection.test.GenericInnerOuter<Int>.Inner<String>>)",
                "fun cursor(): com.xemantic.kotlin.introspection.test.GenericInnerOuter<String>.Inner<Int>",
                "fun nullableCursor(): com.xemantic.kotlin.introspection.test.GenericInnerOuter<String>.Inner<Int>?"
            )
        )
    }

    @Test
    fun `should render inner class type of non-generic outer class with its own type arguments`() {
        // when
        val rendered = introspector.formatReflectedFunction(NonGenericInnerTypes::class.memberFunctions.single { it.name == "cursor" })

        // then
        assert(rendered == "fun cursor(): com.xemantic.kotlin.introspection.test.NonGenericInnerOuter.In<String>")
    }

    @Test
    fun `should name class of package with uppercase segment by its Java class`() {
        // given
        val types = listOf(typeOf<JavaUpperCasePackage>(), typeOf<JavaUpperCasePackage.Nested>())

        // when
        val names = types.map {
            (it.toKmType(ReflectedTypeParameters.classTypeParameters(emptyList())).classifier as KmClassifier.Class).name
        }

        // then
        assert(
            names == listOf(
                "com/xemantic/kotlin/introspection/test/other/Upper/JavaUpperCasePackage",
                "com/xemantic/kotlin/introspection/test/other/Upper/JavaUpperCasePackage.Nested"
            )
        )
    }

    @Test
    fun `should convert Java methods of JDK classes like kotlin-reflect describes them`() {
        // given - the JDK classes whose members kotlin-reflect describes, of the shapes toKmJavaFunction converts
        val classes = listOf(
            java.util.ArrayList::class, java.util.LinkedList::class, java.util.ArrayDeque::class, java.util.HashMap::class,
            java.util.TreeMap::class, java.util.TreeSet::class, java.util.Hashtable::class, java.util.Properties::class,
            java.util.PriorityQueue::class, java.util.Vector::class, java.util.EnumMap::class, java.util.BitSet::class,
            java.util.Optional::class, java.util.Collections::class, java.util.Arrays::class, java.util.Spliterators::class,
            java.util.concurrent.ConcurrentHashMap::class, java.util.concurrent.CompletableFuture::class,
            java.util.concurrent.ThreadPoolExecutor::class, java.util.concurrent.ConcurrentSkipListMap::class,
            java.util.concurrent.CopyOnWriteArrayList::class, java.util.stream.Stream::class, java.util.stream.Collectors::class,
            java.util.function.Function::class, java.util.function.BiFunction::class, java.util.Comparator::class,
            java.lang.Thread::class, java.lang.ThreadLocal::class, java.lang.ClassLoader::class, java.lang.StringBuilder::class,
            java.lang.ref.WeakReference::class, java.lang.reflect.Method::class, java.lang.invoke.MethodHandles::class,
            java.io.File::class, java.io.PrintStream::class, java.io.InputStream::class, java.io.Writer::class,
            java.nio.file.Files::class, java.nio.ByteBuffer::class, java.text.Collator::class, java.text.SimpleDateFormat::class,
            java.time.LocalDate::class, java.time.Duration::class, java.math.BigInteger::class, java.math.BigDecimal::class,
            java.util.concurrent.atomic.AtomicReference::class, java.util.concurrent.atomic.AtomicInteger::class,
            java.security.Permission::class, java.net.URI::class, java.util.regex.Matcher::class,
            JavaConvertedShapes::class, JavaConvertedShapes.Inner::class
        )

        // when
        val mismatches = classes.flatMap { kClass ->
            kClass.declaredMemberFunctions.mapNotNull { function ->
                // the members overriding the ones of the built-in types are never converted, see javaMembersOf
                val method = function.javaMethod?.takeUnless { overridesBuiltinJavaMethod(it) } ?: return@mapNotNull null
                // kotlin-reflect fails on some, like the raw Enum of Class<? extends Enum>, see toKmJavaType
                val reflected = runCatching { introspector.formatReflectedFunction(function) }.getOrElse { return@mapNotNull null }
                val converted = introspector.formatJavaFunction(method)
                if (reflected == converted) null else "${kClass.qualifiedName}: $reflected <> $converted"
            }
        }

        // then
        assert(mismatches.isEmpty())
    }

    @Test
    fun `should convert value of Java override of enhanced Map method annotated nullable as not definitely not nullable`() {
        // given - the value of merge, which the compiler enhances as V & Any, annotated nullable
        val merge = JavaNullableMergeMap::class.declaredMemberFunctions.single { it.name == "merge" }

        // when
        val value = merge.toKmFunction(ReflectedTypeParameters.classTypeParameters(emptyList())).valueParameters[1].type

        // then
        assert(value.isNullable && !value.isDefinitelyNonNull)
    }

}
