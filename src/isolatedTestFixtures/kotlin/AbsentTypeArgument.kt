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

// Tests leave the class file of Absent out of the class loader,
// so that the members and the generic supertypes referring to it cannot be reflected
class Absent

class AbsentTypeArgumentComparable : Comparable<Absent> {
    override fun compareTo(other: Absent): Int = 0
}

// only the generic supertype refers to Absent, not the members
abstract class AbsentTypeArgumentHolder : Comparable<Absent> {
    fun hold() {}
}

interface AbsentArrayProcessor<T, A> {
    fun process(items: Array<T>)
    fun absent(item: A)
}

abstract class StringAbsentArrayProcessor : AbsentArrayProcessor<String, Absent> {
    override fun process(items: Array<String>) {}
    fun process(items: Array<Absent>) {}
}

// the Java superclass declares a method taking Absent
class AbsentJavaSubclass : AbsentJavaBase<String>() {
    override fun process(item: String) {}
    fun process(item: Int) {}
    override fun run() {}
    fun run(times: Int) {}
    fun prim(x: Int?) {}
}

// the Java superclass refers to Absent only in the generic types of its methods
class AbsentBoundJavaSubclass : AbsentBoundJavaBase() {
    override fun run() {}
    override fun <T : Comparable<Absent>> compare(item: T) {}
    fun <T : CharSequence> compare(item: T) {}
    fun compare(item: Int) {}
}
