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

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * The built-in types resolved when the `.kotlin_builtins` resources of the standard library cannot be read,
 * like when it is a named module, whose resources are encapsulated, or a jar stripped of them.
 */
class BuiltinsWithoutDeclarationsTest {

    // given
    private val introspector = DefaultKotlinIntrospector(scanEntireClasspath = false, readBuiltinClasses = { emptyMap() })

    @Test
    fun `should resolve built-in class described by kotlin-reflect`() {
        // when
        val listing = introspector.resolve("kotlin.String", pageSize = 10000)

        // then
        listing should {
            have(lines()[1].startsWith("class String : "))
            have("Comparable<String>" in lines()[1] && "CharSequence" in lines()[1])
            have(lines().any { it.endsWith("val length: Int") })
            have(lines().any { it.endsWith("fun chars(): java.util.stream.IntStream") })
        }
    }

    @Test
    fun `should resolve built-in interface described by kotlin-reflect`() {
        // when
        val listing = introspector.resolve("kotlin.collections.List", pageSize = 10000)

        // then
        listing should {
            have(lines()[1] == "interface List<out E> : Collection<E> {")
            have(lines().any { it.endsWith("operator fun get(index: Int): E") })
        }
    }

    @Test
    fun `should resolve deprecation of built-in member described by kotlin-reflect`() {
        // when
        val signatures = introspector.resolve("kotlin.Number.toChar")

        // then
        signatures should {
            have(lines().drop(1).single().startsWith("@Deprecated(\"Direct conversion to Char is deprecated."))
        }
    }

    @Test
    fun `should resolve Java-only member described by kotlin-reflect with synthetic parameter names`() {
        // given - the JDK is compiled without parameter names
        assumeNoParameterNames(Throwable::class.java.getMethod("initCause", Throwable::class.java))

        // when
        val signatures = introspector.resolve("kotlin.Throwable.initCause")

        // then
        signatures should {
            have(lines().drop(1).single().endsWith(" fun initCause(arg0: Throwable?): Throwable"))
        }
    }

    @Test
    fun `should not resolve JVM-only member described by kotlin-reflect which the compiler cannot call`() {
        // when - String.indent is told "not fully supported by Kotlin compiler", which K2 does not resolve
        val signatures = introspector.resolve("kotlin.String.indent")

        // then
        assert(signatures == null)
    }

    @Test
    fun `should not resolve mutable collection type which kotlin-reflect cannot tell apart from the read-only one`() {
        // when
        val listing = introspector.resolve("kotlin.collections.MutableList")

        // then
        assert(listing == null)
    }

}
