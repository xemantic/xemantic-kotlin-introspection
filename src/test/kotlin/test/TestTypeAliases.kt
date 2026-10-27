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

class TypeAliasTarget {
    fun greet(): String = ""
}

typealias AliasOfClass = TypeAliasTarget

typealias GenericAlias<T> = Map<String, List<T>>

typealias FunctionAlias = (Int) -> String

@Deprecated("Use TypeAliasTarget", ReplaceWith("TypeAliasTarget"))
typealias DeprecatedAlias = TypeAliasTarget

@Deprecated("Gone", level = DeprecationLevel.HIDDEN)
typealias HiddenAlias = TypeAliasTarget

internal typealias InternalAlias = TypeAliasTarget

// a typealias of a typealias of another package, qualified not to read as of the same package
typealias AliasOfOtherPackageAlias = com.xemantic.kotlin.introspection.test.other.OtherPackageAlias

@Deprecated("Use TypeAliasTarget", level = DeprecationLevel.ERROR)
typealias ErrorDeprecatedAlias = TypeAliasTarget

// an interface member with a body, declared abstract again by a Java interface
interface DefaultedMember {
    fun foo(): Int = 1
}

abstract class InheritedJavaReabstractedDefault : com.xemantic.kotlin.introspection.test.other.JavaReabstractedDefault

// interface members implemented by final methods of a Java class
interface FinallyImplemented {
    fun f(): Int
    val name: String
}

abstract class InheritedJavaFinalImplementation : com.xemantic.kotlin.introspection.test.other.JavaFinalImplementation()
