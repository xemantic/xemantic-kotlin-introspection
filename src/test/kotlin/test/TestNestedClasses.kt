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

class ExtensionTarget

fun ExtensionTarget.topLevelExtension(): String = "top-level"

// the member extensions of objects can be imported, like the top-level ones
object ExtensionTargetUtils {
    fun ExtensionTarget.fromObject(): String = "object"
    private fun ExtensionTarget.fromObjectPrivate(): String = "object private"
}

internal object InternalExtensionObject {
    fun ExtensionTarget.fromInternalObject(): String = "internal object"
}

// the member extensions of classes can only be called within the scope of their dispatch receiver,
// so they are not extensions of the receiver type for the outside world, unlike the ones of the companion object
class MemberExtensionHolder {
    fun ExtensionTarget.fromMember(): String = "member"
    val ExtensionTarget.fromMemberProperty: String get() = "member"
    companion object {
        fun ExtensionTarget.fromCompanion(): String = "companion"
    }
    private class Hidden {
        fun ExtensionTarget.fromPrivateNested(): String = "private nested"
    }
    private object HiddenObject {
        fun ExtensionTarget.fromPrivateObject(): String = "private object"
    }
}

fun localClassHolder(): Any {
    class Local {
        fun ExtensionTarget.fromLocal(): String = "local"
    }
    return Local()
}

enum class NestedClassEnum {
    RED,
    GREEN;
    class Palette
}

class GenericNestedHolder {
    class Entry<K, V>(override val key: K, override val value: V) : Map.Entry<K, V>
}

private class PrivateTopLevelHolder

internal class InternalNestedHolder {
    class NestedInInternalClass
    object NestedObject {
        fun ExtensionTarget.fromObjectInInternalClass(): String = "object in internal class"
    }
}

class TypeParametersNestedHolder<K> {
    inner class Cursor : Iterator<K> {
        override fun hasNext(): Boolean = false
        override fun next(): K = throw NoSuchElementException()
    }
    class Sorted<T> where T : Comparable<T>, T : CharSequence
}

class InnerOfInnerHolder<A> {
    inner class Mid<B> {
        inner class Leaf(override val key: A, override val value: B) : Map.Entry<A, B> {
            fun pair(): Pair<A, B> = key to value
        }
    }
}
