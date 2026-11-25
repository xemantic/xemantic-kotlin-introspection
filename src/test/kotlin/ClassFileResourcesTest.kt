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
import org.objectweb.asm.Opcodes

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
    fun `should not take class file flagged as module of another name for one of no superclass`() {
        // given
        // a class whose superclass index is replaced by 0, flagged as a module descriptor, which is named module-info
        val stringClassFile = ClassLoader.getSystemResourceAsStream("java/lang/String.class")!!.use { it.readBytes() }
        val flaggedAsModule = stringClassFile.copyOf().apply {
            // the access flags are followed by this class, and the superclass
            val header = classReader(this).header
            this[header] = (Opcodes.ACC_MODULE shr 8).toByte()
            this[header + 1] = 0
            this[header + 4] = 0
            this[header + 5] = 0
        }

        // when
        val namesSupertypes = classReader(flaggedAsModule).namesSupertypesByClassEntries()

        // then
        assert(!namesSupertypes)
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

    @Test
    fun `should tell classes of signature naming inner class of generic outer class by binary name`() {
        // given
        // fun <T : Comparable<T>, A : o.ext.Event> Map<T, T>.f(x: Outer<String>.Inner, y: Outer<List<String>>.Inner, z: Array<Outer<String>.Inner>), see #386
        val signature = "<T::Ljava/lang/Comparable<TT;>;A:Lo/ext/Event;>(Ljava/util/Map<TT;TT;>;" +
            "Lp/Outer<Ljava/lang/String;>.Inner;Lp/Outer<Ljava/util/List<Ljava/lang/String;>;>.Inner;[Lp/Outer<Ljava/lang/String;>.Inner;)V"

        // when
        val classNames = signatureClassNames(signature)

        // then - the type arguments of the outer class do not take the place of its name
        assert(
            classNames == setOf(
                "java/lang/Comparable", "o/ext/Event", "java/util/Map", "p/Outer", "java/lang/String", "p/Outer\$Inner", "java/util/List"
            )
        )
    }

    @Test
    fun `should tell classes of signature naming inner class of generic outer class in type argument`() {
        // given
        // a Map<Outer<String>.Inner, Outer<String>.Inner.Deeper>
        val signature = "(Ljava/util/Map<Lp/Outer<Ljava/lang/String;>.Inner;Lp/Outer<Ljava/lang/String;>.Inner.Deeper;>;)V"

        // when
        val classNames = signatureClassNames(signature)

        // then
        assert(
            classNames == setOf("java/util/Map", "p/Outer", "java/lang/String", "p/Outer\$Inner", "p/Outer\$Inner\$Deeper")
        )
    }

}
