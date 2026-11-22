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
    fun `should tell class files of no superclass naming their supertypes from one of malformed superclass index`() {
        // given
        // the only class files of the superclass index 0: of java.lang.Object, and of a module descriptor
        val objectClassFile = ClassLoader.getSystemResourceAsStream("java/lang/Object.class")!!.use { it.readBytes() }
        val moduleDescriptor = Any::class.java.module.getResourceAsStream("module-info.class")!!.use { it.readBytes() }
        // and of another class, whose superclass index is replaced by 0, like a malformed one
        val stringClassFile = ClassLoader.getSystemResourceAsStream("java/lang/String.class")!!.use { it.readBytes() }
        val withoutSuperclass = stringClassFile.copyOf().apply {
            // the access flags are followed by this class, and the superclass
            val superclass = classReader(this).header + 4
            this[superclass] = 0
            this[superclass + 1] = 0
        }

        // when
        val namesSupertypes = listOf(objectClassFile, moduleDescriptor, stringClassFile, withoutSuperclass).map {
            classReader(it).namesSupertypesByClassEntries()
        }

        // then
        assert(namesSupertypes == listOf(true, true, true, false))
    }

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
