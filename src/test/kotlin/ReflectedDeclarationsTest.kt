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
import com.xemantic.kotlin.introspection.test.other.JavaUnnamedParameters
import com.xemantic.kotlin.introspection.test.other.Upper.JavaUpperCasePackage
import com.xemantic.kotlin.test.assert
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.reflect.KFunction
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

        // then - the return type, which Kotlin lets be used as not nullable, stays so
        assert(rendered == "open fun describe(arg0: Any?): String")
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
                "open fun <T> first(arg0: T?): T",
                "fun <T> listOf(vararg arg0: T?): List<T>"
            )
        )
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
