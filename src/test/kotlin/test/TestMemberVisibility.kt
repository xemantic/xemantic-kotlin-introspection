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

import kotlin.coroutines.CoroutineContext

// Names avoid the "TestClass" suffix to keep search result tests stable.

// Open class with members of every visibility, where the protected ones can be used by a subclass
open class OpenVisibilityHolder {
    val publicProperty: String = "public"
    private val privateProperty: String = "private"
    protected val protectedProperty: String = "protected"
    internal val internalProperty: String = "internal"

    fun publicFunction(): String = "public"
    private fun privateFunction(): String = "private"
    protected fun protectedFunction(): String = "protected"
    internal fun internalFunction(): String = "internal"

    protected class NestedProtected
    private class NestedPrivate

    // uses the private members, so that the compiler does not report them as unused
    fun usePrivate(): String = privateProperty + privateFunction() + NestedPrivate()
}

// The constructor parameters are still properties, but the constructor cannot be called
class PrivateConstructorHolder private constructor(val id: Int, name: String) {
    val label: String = name
    companion object {
        fun create(id: Int): PrivateConstructorHolder = PrivateConstructorHolder(id, "name")
    }
}

// The constructor can be called only from a subclass
abstract class ProtectedConstructorHolder protected constructor(val id: Int)

// The constructor of a public class can be internal
class InternalConstructorHolder internal constructor(val id: Int)

class NamedCompanionHolder {
    companion object Factory {
        fun of(): NamedCompanionHolder = NamedCompanionHolder()
    }
}

class PrivateCompanionHolder {
    private companion object {
        fun helper(): Int = 42
    }

    fun answer(): Int = helper()
}

// The protected members of the companion object can be called from a subclass of the outer class
open class ProtectedCompanionMembersHolder {
    companion object {
        protected fun protectedFactory(): Int = 1
        fun publicFactory(): Int = 2
    }
}

// The constructor of a sealed class is protected, but the user code cannot declare a subclass calling it
sealed class SealedShape(val code: Int) {
    class Square : SealedShape(4)
}

// Neither the class, nor its members, nor its nested classes are part of the API
internal class InternalHolder {
    fun member() {}
    class Nested
}

// The companion object used as a value, like in coroutineContext[CompanionKeyElement]
class CompanionKeyElement : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key
    companion object Key : CoroutineContext.Key<CompanionKeyElement>
}

class EmptyCompanionHolder {
    companion object
}

open class ProtectedConstructorPropertyHolder(protected val secret: Int, val open: Int)

// The private nested class is not part of the API, but the extension property of the same name is
class ShadowingHolder {
    private class X
}

val ShadowingHolder.X: Int get() = 42

// Only non-public members in the companion object, which is still a public value
class HiddenCompanionMembersHolder {
    companion object {
        private const val PRIVATE_CONSTANT: String = "private"
        internal fun internalHelper(): String = PRIVATE_CONSTANT
    }
}
