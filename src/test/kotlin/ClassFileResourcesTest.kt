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
import kotlin.test.Test

class ClassFileResourcesTest {

    @Test
    fun `should locate metadata name in package of relocated binary name`() {
        // when
        val name = locatedNameOf("shaded.com.example.Outer\$Nested", "com/example/Outer.Nested")

        // then
        assert(name == "shaded/com/example/Outer.Nested")
    }

    @Test
    fun `should keep leading dot of located local class name`() {
        // given
        // the metadata name of a local class starts with '.', telling it is not part of the API, see ApiClasses
        val metadataName = ".com/example/Outer\$f\$Local"

        // when
        val name = locatedNameOf("shaded.com.example.Outer\$f\$Local", metadataName)

        // then
        assert(name == ".shaded/com/example/Outer\$f\$Local")
    }

    @Test
    fun `should keep single leading dot of local class of root package relocated to a package`() {
        // when
        val name = locatedNameOf("shaded.Outer\$f\$Local", ".Outer\$f\$Local")

        // then
        assert(name == ".shaded/Outer\$f\$Local")
    }

}
