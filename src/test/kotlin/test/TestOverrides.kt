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

// An overload of an open function of the superclass is not an override
open class OverloadedBase {
    open fun foo() {}
}

class OverloadingSubclass : OverloadedBase() {
    fun foo(x: Int) {}
}

// Final methods of java.lang.Object are not overridable,
// so functions sharing their names are not overrides
open class NonAnyBase

class ObjectMethodNamesSubclass : NonAnyBase() {
    fun notify(msg: String) {}
    fun wait(ms: Int) {}
}

// Protected members can be overridden as well
abstract class ProtectedAbstractBase {
    protected abstract fun bar()
}

class ProtectedOverrideSubclass : ProtectedAbstractBase() {
    override fun bar() {}
}

// An override of a generic function, next to its overload
interface Processor<T> {
    fun process(item: T)
}

abstract class StringProcessor : Processor<String> {
    override fun process(item: String) {}
    fun process(item: Int) {}
}

class IntProcessor : Processor<Int> {
    override fun process(item: Int) {}
    fun process(item: String) {}
}

// Overrides of functions declared further up the hierarchy
open class IntermediateProcessor : StringProcessor()

class DeepProcessor : IntermediateProcessor() {
    override fun process(item: String) {}
    override fun toString(): String = "DeepProcessor"
}
