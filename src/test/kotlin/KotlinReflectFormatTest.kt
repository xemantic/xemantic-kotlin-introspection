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

import com.xemantic.kotlin.introspection.test.ReflectedValueParametersSample
import com.xemantic.kotlin.test.assert
import kotlin.reflect.KFunction
import kotlin.reflect.full.memberFunctions
import kotlin.test.Test

/**
 * The kotlin-reflect rendering of the members of the built-in types,
 * exercised on a regular class, as no built-in member has such parameters.
 */
class KotlinReflectFormatTest {

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
        // given
        val indent = String::class.memberFunctions.single { it.name == "indent" }

        // when
        val synthetic = hasSyntheticParameterNames(indent)

        // then
        assert(synthetic)
    }

    @Test
    fun `should render vararg of nullable type as its element type`() {
        // when
        val rendered = formatKFunction(function("format"))

        // then
        assert(rendered == "fun format(vararg args: Any?): String")
    }

    @Test
    fun `should render vararg of primitive type as its element type`() {
        // when
        val rendered = formatKFunction(function("sum"))

        // then
        assert(rendered == "fun sum(vararg ints: Int): Int")
    }

    @Test
    fun `should render vararg of unsigned type as its element type`() {
        // when
        val rendered = formatKFunction(function("sumUnsigned"))

        // then
        assert(rendered == "fun sumUnsigned(vararg uints: UInt): UInt")
    }

    @Test
    fun `should render optional parameter with default value marker`() {
        // when
        val rendered = formatKFunction(function("join"))

        // then
        assert(rendered == "fun join(vararg parts: String, separator: String = …): String")
    }

}
