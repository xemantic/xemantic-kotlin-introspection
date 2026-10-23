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

package com.xemantic.kotlin.introspection.isolated

// A top-level class named like a class nested in the Java class JavaOuter,
// which only the class file of JavaOuter tells apart from the nested ones.
class `JavaOuter$Impostor`

// The entry with a body is compiled to a class nested in the enum class, having the class metadata,
// which is declared as an enum entry, not as a nested class, in the metadata of the enum class.
enum class EntryWithBody {
    PLAIN,
    WITH_BODY {
        override fun toString(): String = "body"
    }
}

// The when expression over the enum entries is compiled with the nested $WhenMappings class,
// which is not declared as a nested class in the metadata.
class WhenMappingsHolder {
    fun describe(entry: EntryWithBody): Int = when (entry) {
        EntryWithBody.PLAIN -> 1
        EntryWithBody.WITH_BODY -> 2
    }
}

// A Kotlin class with nested classes, whose class file the tests rewrite, like a compiler plugin or a bytecode tool might.
class RewrittenOuter {
    class Nested {
        class Deep
    }
}

// The entries of a Java enum are compiled with the nested $EntriesMappings class,
// which is not declared as a nested class in the metadata.
class EntriesMappingsHolder {
    fun units(): List<java.util.concurrent.TimeUnit> = java.util.concurrent.TimeUnit.entries
}
