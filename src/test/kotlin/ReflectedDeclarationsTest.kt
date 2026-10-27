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
import com.xemantic.kotlin.introspection.test.other.JavaLabelBase
import com.xemantic.kotlin.introspection.test.other.JavaUnnamedParameters
import com.xemantic.kotlin.introspection.test.other.Upper.JavaUpperCasePackage
import com.xemantic.kotlin.test.assert
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmVariance
import kotlin.metadata.isDefinitelyNonNull
import kotlin.metadata.isNullable
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
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
    fun `should read Java class type parameter bound of platform type as nullable`() {
        // given - the implicit bound of the K and V of java.util.HashMap is Object, seen in Kotlin as Any!, admitting null
        val kClass = java.util.HashMap::class

        // when
        val kmClass = kClass.toKmJavaClassHeader("java/util/HashMap") { kotlinClassName(it) }

        // then - so that its V used as definitely not nullable, like the V & Any of merge, is rendered so
        assert(kmClass.typeParameters.flatMap { it.upperBounds }.all { it.isNullable })
        assert(notNullableTypeParameters(kmClass.typeParameters).isEmpty())
    }

    @Test
    fun `should render Java parameter of class type parameter as not nullable`() {
        // given - T! of a JavaLabelBase<String> accepts no null, unlike Any! of Object, as the receiver fixes T
        val getLabel = JavaLabelBase::class.memberFunctions.single { it.name == "getLabel" }
        val typeParameters = ReflectedTypeParameters.classTypeParameters(listOf(KmTypeParameter("T", 0, KmVariance.INVARIANT)))

        // when
        val type = getLabel.toKmFunction(typeParameters).valueParameters.single().type

        // then
        assert(!type.isNullable && (type.classifier as? KmClassifier.TypeParameter)?.id == 0)
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

}
