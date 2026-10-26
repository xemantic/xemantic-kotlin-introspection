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

// Visible only to a dedicated class loader, never on the test classpath
class IsolatedTestClass(val value: Int)

fun IsolatedTestClass.isolatedExtension(): Int = value

// tests relocate it, like shading, so that its outer class is found only by its relocated binary name
internal class RelocatedInternalOuter {
    class PublicNested
}

// tests relocate it, like shading, together with its inner class
class RelocatedGenericOuter<K> {
    inner class Cursor : Comparable<K> {
        override fun compareTo(other: K): Int = 0
    }
}

// tests relocate it, like shading, so that its member extensions are imported from its relocated name
class RelocatedExtensionTarget

object RelocatedExtensions {
    fun RelocatedExtensionTarget.relocatedExtension(): Int = 0
}

// tests relocate it, like shading, together with its nested classes and its companion object
class RelocatedHolder {
    class Nested
    companion object {
        fun create(): RelocatedHolder = RelocatedHolder()
    }
}

// tests relocate it, like shading, together with its subclasses, one of them open, so that the protected declarations are listed
sealed class RelocatedSealed {
    protected class ProtectedNested
    protected fun hook() {}
    class Leaf : RelocatedSealed()
}

open class RelocatedOpenSubclass : RelocatedSealed()

// tests relocate it, like shading, together with its inner class overloading the overridden function
// with the bounded type parameter of the outer class, erased to its bound
class RelocatedBoundedOuter<K : Number> {
    inner class Comparing : Comparator<String> {
        override fun compare(a: String, b: String): Int = 0
        fun compare(a: K, b: K): Int = 0
    }
}

// tests relocate it, like shading, together with its subclass, so that the overridden members are found by its relocated name
abstract class RelocatedBase<T> {
    abstract fun accept(value: T)
}

class RelocatedDerived : RelocatedBase<String>() {
    override fun accept(value: String) {}
    fun accept(value: Int) {}
}

// tests relocate them, like shading, together with the Java interface taking a RelocatedEvent
class RelocatedEvent

class RelocatedOther

class RelocatedListenerImpl : RelocatedListener {
    override fun on(event: RelocatedEvent) {}
    fun on(event: RelocatedOther) {}
}

// tests relocate it, like shading, together with the Java interface, but not the parameter types
class RelocatedPrefixListenerImpl : RelocatedPrefixListener {
    override fun on(value: IsolatedTestClass) {}
    fun on(value: RelocatedHolder) {}
}
