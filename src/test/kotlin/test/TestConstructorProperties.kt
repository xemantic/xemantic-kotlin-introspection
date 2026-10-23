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

// a constructor parameter cannot declare an abstract property
abstract class ConstructorParameterShadowedByAbstractProperty(x: Int, y: String) {
    abstract val x: Int
    abstract var y: String
}

// a property initialized with a constant is never declared in the constructor
class ConstructorParameterShadowedByConstantProperty(x: Int, y: String) {
    val x: Int = 42
    val y: String = "lit"
}

// the accessors renamed for the JVM have no body, and the properties are still declared in the constructor
class ConstructorPropertiesWithJvmNames(@get:JvmName("fetchX") val x: Int, @set:JvmName("storeY") var y: Int)

// a body property with a narrowed setter is never declared in the constructor
class ConstructorParameterShadowedByPrivateSetterProperty(x: Int) {
    var x: Int = x
        private set
}

// a delegated property is never declared in the constructor
class ConstructorParameterShadowedByDelegatedProperty(x: Int) {
    val x: Int by lazy { x }
}

// a lateinit property is never declared in the constructor
class ConstructorParameterShadowedByLateinitProperty(@Suppress("unused") x: String) {
    lateinit var x: String
}

interface ConstructorParameterDelegate {
    val x: Int
}

// a property delegated to the implementation of an interface is never declared in the constructor
class ConstructorParameterShadowedByInterfaceDelegation(
    @Suppress("unused") x: Int,
    delegate: ConstructorParameterDelegate
) : ConstructorParameterDelegate by delegate
