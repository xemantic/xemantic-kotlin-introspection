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

// a constructor parameter which is not a property, while the body declares a property of the same name
class ConstructorParameterShadowedByProperty(x: Int) {
    val x: Long = x.toLong()
}

// the same type, but the body property has no backing field, as it is computed by its getter
class ConstructorParameterShadowedByComputedProperty(x: Int) {
    val initial: Int = x
    val x: Int get() = initial + 1
}

// a body property initialized with the constructor parameter is indistinguishable from a constructor property
class ConstructorParameterAssignedToProperty(x: Int) {
    val x: Int = x
}

interface MeasureX {
    val String.measure: Int
}

// a member extension property is never declared in the constructor
class CtorClash(val measure: Int) : MeasureX {
    override val String.measure: Int get() = 0
}
