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

// A member inherited through a Java superclass which tests make unloadable, leaving the class file of Absent out

interface AbsentReachedGreeter {
    fun greet(): String
}

// the Java superclass cannot be loaded, so that the interface it implements is told only by its class file
abstract class AbsentKotlinInterfaceJavaLeaf : AbsentKotlinInterfaceJavaMid()

// A marker interface whose class file tests leave out, so that the class implementing it cannot be loaded
interface AbsentGreetingMarker

// cannot be loaded, while its public Java superclass, inheriting the members of a package-private one, can
class UnloadableGreeterLeaf : PublicGreeterJavaMid(), AbsentGreetingMarker

// An abstract internal member, which a concrete subclass has to implement, of a class which cannot be loaded,
// and of its subclass, which cannot be loaded either, told by the metadata (#390)
abstract class UnloadableInternalAbstractBase : AbsentGreetingMarker {
    internal abstract fun ia(): String
    internal abstract fun implemented(): String
    abstract fun visible(): Int
}

abstract class UnloadableInternalAbstractSub : UnloadableInternalAbstractBase() {
    override fun implemented(): String = ""
}

// a member of a body of a class which can be loaded, declared abstract again by a class which cannot be loaded,
// so that a concrete subclass of another module "inherits invisible abstract member", as kotlinc 2.4.21 tells
abstract class LoadableInternalOpenBase {
    internal open fun ia(): String = ""
}

abstract class UnloadableInternalReabstracting : LoadableInternalOpenBase(), AbsentGreetingMarker {
    internal abstract override fun ia(): String
}

abstract class UnloadableInternalReabstractingSub : UnloadableInternalReabstracting()

// and the other way round, an abstract member given a body by a class which cannot be loaded, implemented then
abstract class LoadableInternalAbstractBase {
    internal abstract fun ia(): String
}

abstract class UnloadableInternalImplementing : LoadableInternalAbstractBase(), AbsentGreetingMarker {
    internal override fun ia(): String = ""
}

abstract class UnloadableInternalImplementingSub : UnloadableInternalImplementing()

// and so a generic one, given a type argument, implemented of another JVM signature, `ia(String)` of `ia(Object)`
abstract class LoadableInternalGenericAbstractBase<T> {
    internal abstract fun ia(t: T): String
}

abstract class UnloadableInternalGenericImplementing : LoadableInternalGenericAbstractBase<String>(), AbsentGreetingMarker {
    internal override fun ia(t: String): String = ""
}

abstract class UnloadableInternalGenericImplementingSub : UnloadableInternalGenericImplementing()

// a hidden member of a body of a loadable superclass, which implements the abstract one of an interface which cannot be loaded,
// so that kotlinc compiles a concrete subclass, and the other way round, the default of such an interface next to an abstract one
// of a loadable superclass, which kotlinc demands, "does not implement abstract base class member: fun h(): String"
abstract class LoadableHiddenOpenBase {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    open fun h(): String = ""
}

interface UnloadableHiddenAbstractFace : AbsentGreetingMarker {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    fun h(): String
}

abstract class UnloadableHiddenImplementedJoin : LoadableHiddenOpenBase(), UnloadableHiddenAbstractFace

abstract class LoadableHiddenAbstractBase {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    abstract fun h(): String
}

interface UnloadableHiddenDefaultFace : AbsentGreetingMarker {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    fun h(): String = ""
}

abstract class UnloadableHiddenAbstractJoin : LoadableHiddenAbstractBase(), UnloadableHiddenDefaultFace

// and of a class exposing an internal interface, which cannot be loaded
internal interface UnloadableExposedFace {
    fun f(): Int
}

@Suppress("EXPOSED_SUPER_INTERFACE")
abstract class UnloadableInternalExposing : UnloadableExposedFace, AbsentGreetingMarker
