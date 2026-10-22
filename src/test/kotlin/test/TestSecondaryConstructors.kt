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

// another way of creating the class, besides the primary constructor
class SecondaryConstructors(val name: String) {
    val tags: MutableList<String> = mutableListOf()
    constructor(name: String, vararg tags: String) : this(name) {
        this.tags += tags
    }
    constructor(id: Int, prefix: String = "id") : this("$prefix$id")
    fun describe(): String = "$name $tags"
}

// the secondary constructor is the only way of creating the class
class SecondaryConstructorOfPrivatePrimary private constructor(val a: Int) {
    constructor(s: String) : this(s.length)
}

class SecondaryConstructorsWithoutPrimary {
    val x: Int
    constructor(x: Int) {
        this.x = x
    }
    constructor(s: String) : this(s.length)
}

// the primary constructor without parameters has to be rendered, as it is not the implicit default one
class SecondaryConstructorOfEmptyPrimary() {
    constructor(x: Int) : this()
}

// only the protected constructor can be called, from a subclass
open class SecondaryConstructorsOfOpenClass(val id: Int) {
    protected constructor(s: String) : this(s.length)
    internal constructor(l: Long) : this(l.toInt())
    private constructor(d: Double) : this(d.toInt())
    fun useDouble(): SecondaryConstructorsOfOpenClass = SecondaryConstructorsOfOpenClass(1.0)
}

// a protected constructor of a final class cannot be called
@Suppress("ProtectedInFinal")
class ProtectedSecondaryConstructorOfFinalClass(val id: Int) {
    protected constructor(s: String) : this(s.length)
}

// the constructors of a sealed class are protected, and no subclass can be declared in the user code
sealed class SecondaryConstructorOfSealedClass(val id: Int) {
    constructor(s: String) : this(s.length)
    class Sub(id: Int) : SecondaryConstructorOfSealedClass(id)
}

// the constructors of an enum class are private
enum class SecondaryConstructorOfEnum(val code: Int) {
    A(1),
    B("b");
    constructor(s: String) : this(s.length)
}

data class SecondaryConstructorOfDataClass(val x: Int, val y: Int) {
    constructor(xy: Int) : this(xy, xy)
}

@JvmInline
value class SecondaryConstructorOfValueClass(val value: Int) {
    constructor(s: String) : this(s.length)
}

class SecondaryConstructorOfGenericClass<T>(val value: T) {
    constructor(values: List<T>) : this(values.first())
}
