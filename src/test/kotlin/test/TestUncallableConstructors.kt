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

// Classes without any constructor callable from the user code, which can neither be instantiated, nor subclassed

class InternalSecondaryConstructorOnly {
    val x: Int
    internal constructor(x: Int) {
        this.x = x
    }
}

abstract class InternalConstructorAbstractClass internal constructor() {
    abstract fun f()
}

open class PrivateConstructorOpenClass private constructor() {
    companion object {
        val instance: PrivateConstructorOpenClass = PrivateConstructorOpenClass()
    }
}

@Suppress("ProtectedInFinal")
class ProtectedConstructorFinalClass protected constructor()

class InnerPrivateConstructorOuter {
    inner class Inner private constructor()
}

// a protected constructor without parameters can be called by a subclass, unlike the implicit public one by anyone
open class ProtectedEmptyConstructorOpenClass protected constructor()

abstract class ProtectedEmptyConstructorAbstractClass protected constructor()
