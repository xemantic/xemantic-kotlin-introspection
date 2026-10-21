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

package com.xemantic.kotlin.introspection.test.ext

import com.xemantic.kotlin.introspection.test.NoMembersTestClass
import com.xemantic.kotlin.introspection.test.TestClass

/**
 * Extension function from a different package.
 */
fun TestClass.describe(): String = "TestClass(name=$name)"

/**
 * Extension property from a different package.
 */
val TestClass.uppercaseName: String
    get() = name.uppercase()

/**
 * Extension function of a class without body members.
 */
fun NoMembersTestClass.doubled(): Int = value * 2
