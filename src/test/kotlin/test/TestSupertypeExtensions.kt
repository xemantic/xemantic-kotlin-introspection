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

package com.xemantic.kotlin.introspection.test

// Type hierarchy where each level is extended,
// names avoid the "TestClass" suffix to keep search result tests stable
interface TestSupertype {
    val id: String
}

open class TestIntermediateType(override val id: String) : TestSupertype

class TestSubtype(id: String) : TestIntermediateType(id) {
    fun label(): String = "subtype $id"
}

fun TestSupertype.describeSupertype(): String = "supertype $id"

fun TestIntermediateType.describeIntermediate(): String = "intermediate $id"

fun TestSubtype.describeSubtype(): String = "subtype $id"
