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

@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.xemantic.kotlin.introspection.test

// Declarations deprecated with the WARNING and ERROR levels, which can still be referred to from the source code

@Deprecated("Use DeprecationTarget instead", ReplaceWith("DeprecationTarget"))
class DeprecatedClass {
    @Deprecated("Nested in deprecated")
    class Nested
}

class DeprecationTarget @Deprecated("Use create()") constructor(val value: Int) {

    @Deprecated("Use the Int constructor", level = DeprecationLevel.ERROR)
    constructor(value: String) : this(value.length)

    @Deprecated("No \"replacement\" for \$value")
    val deprecatedProperty: Int get() = value

    @get:Deprecated("Getter")
    val deprecatedGetter: Int get() = value

    @Deprecated("Use newMember()", ReplaceWith("newMember()"))
    fun warningMember(): Int = value

    @Deprecated("Use newMember()", ReplaceWith("newMember()"), level = DeprecationLevel.ERROR)
    fun errorMember(): Int = value

    fun newMember(): Int = value

    companion object {
        @Deprecated("Use the constructor")
        fun create(): DeprecationTarget = DeprecationTarget(0)
    }

}

// only the first line of a message is rendered
class MultilineDeprecationTarget {
    @Deprecated("First line.\nSecond line.")
    fun f() {}
    @Deprecated("\nUse f() instead.")
    fun g() {}
}

@Deprecated("Use newMember()", ReplaceWith("newMember()"))
fun DeprecationTarget.deprecatedExtension(): Int = value

@Deprecated("Gone", level = DeprecationLevel.ERROR)
val DeprecationTarget.deprecatedExtensionProperty: Int get() = value

@Deprecated("Use deprecationTopLevel(value)", ReplaceWith("deprecationTopLevel(0)"))
fun deprecationTopLevel(): Int = 42

fun deprecationTopLevel(value: Int): Int = value

interface DeprecationInterface {
    @Deprecated("Abstract")
    val abstractProperty: Int
    @Deprecated("With getter")
    val propertyWithGetter: Int get() = 1
}

// a hidden getter hides the property, whatever the deprecation of the property itself
class HiddenGetterHolder {
    @Deprecated("Old")
    val hiddenGetter: Int
        @Deprecated("Replaced", level = DeprecationLevel.HIDDEN) get() = 1
    val visible: Int = 0
}

// a hidden companion object cannot be referred to, neither its members
class HiddenCompanionHolder {
    fun f() {}
    @Deprecated("Replaced", level = DeprecationLevel.HIDDEN)
    companion object {
        fun create(): HiddenCompanionHolder = HiddenCompanionHolder()
    }
}

// the same for a named companion object, and a nested class, in a class which is not hidden itself
class HiddenNamedCompanionHolder {
    fun f() {}
    @Deprecated("Replaced", level = DeprecationLevel.HIDDEN)
    class HiddenNested {
        fun g() {}
    }
    @Deprecated("Replaced", level = DeprecationLevel.HIDDEN)
    companion object Factory {
        fun create(): HiddenNamedCompanionHolder = HiddenNamedCompanionHolder()
    }
}

// a deprecated companion object, declared with each of its members found by a member lookup on the outer class
class DeprecatedCompanionHolder {
    @Deprecated("Use the constructor")
    companion object {
        fun create(): DeprecatedCompanionHolder = DeprecatedCompanionHolder()
    }
}

// classes enclosing a declaration, whose deprecations Kotlin reports on each of their names the caller writes,
// like DeprecatedEnclosingClass.Mid.Inner.create(), also of an outer class only, like ShallowDeprecatedEnclosingClass.Mid.Inner
@Deprecated("Use DeprecationTarget")
class DeprecatedEnclosingClass {
    @Deprecated("Use DeprecationTarget.create()", level = DeprecationLevel.ERROR)
    class Mid {
        class Inner {
            fun member(): Int = 0
            companion object {
                fun create(): Inner = Inner()
            }
        }
    }
}

@Deprecated("Use DeprecationTarget")
class ShallowDeprecatedEnclosingClass {
    class Mid {
        class Inner
    }
}

@Deprecated("Use DeprecationTarget.create()")
object DeprecatedObject {
    fun member(): Int = 0
}

// called on an instance, like deprecatedClass.instanceExtension(), for which Kotlin reports no deprecation of the class
fun DeprecatedClass.instanceExtension(): Int = 0

// called on the name of the object, like DeprecatedObject.objectExtension(), for which Kotlin reports its deprecation
fun DeprecatedObject.objectExtension(): Int = 0

// usually called on an entry, like DeprecatedEnum.A.enumExtension(), for which Kotlin reports the deprecation of the enum class
@Deprecated("Use DeprecationTarget")
enum class DeprecatedEnum { A }

fun DeprecatedEnum.enumExtension(): Int = 0

// a deprecated class which is protected, and a deprecated class within a protected one
open class DeprecatedProtectedHolder {
    @Deprecated("Use DeprecationTarget")
    protected class DeprecatedProtected {
        companion object {
            fun create(): Int = 0
        }
    }
    protected class ProtectedOuter {
        @Deprecated("Use DeprecationTarget")
        class Inner {
            fun member(): Int = 0
        }
    }
}

// the constructor of an enum class is always private, called only by its entries
enum class DeprecatedConstructorEnum @Deprecated("Old") constructor(val v: Int) {
    A(1)
}

class DeprecatedConstructorProperty(@Deprecated("Use baz", level = DeprecationLevel.ERROR) val bar: Int) {
    val baz: Int get() = bar
}

// a data class, or a value class, cannot be declared without a property for each parameter, also a hidden one
data class HiddenPropertyDataClass(@property:Deprecated("Replaced", level = DeprecationLevel.HIDDEN) val a: Int, val b: Int)

@JvmInline
value class HiddenPropertyValueClass(@property:Deprecated("Replaced", level = DeprecationLevel.HIDDEN) val raw: Int)

// the accessors of a var deprecated apart from the property, so that only its reading, or only its assigning, is deprecated
class AccessorDeprecationHolder(@get:Deprecated("Read y", level = DeprecationLevel.ERROR) var inConstructor: Int) {
    @get:Deprecated("Read y", level = DeprecationLevel.ERROR)
    var errorGetter: Int = 0
    @get:Deprecated("Read y")
    var warningGetter: Int = 0
    @set:Deprecated("Assign y")
    var warningSetter: Int = 0
    @set:Deprecated("Assign y", level = DeprecationLevel.ERROR)
    var errorSetter: Int = 0
    @get:Deprecated("Read y")
    @set:Deprecated("Assign y", level = DeprecationLevel.ERROR)
    var bothAccessors: Int = 0
    @Deprecated("Use y")
    @set:Deprecated("Assign y", level = DeprecationLevel.ERROR)
    var deprecatedWithErrorSetter: Int = 0
    // cannot be assigned from the outside, so that only its reading is deprecated
    @get:Deprecated("Read y", level = DeprecationLevel.ERROR)
    @set:Deprecated("Assign y")
    var withPrivateSetter: Int = 0
        private set
    // deprecated itself too, telling the replacement, next to the deprecation of its reading
    @Deprecated("Use z", ReplaceWith("z"))
    @get:Deprecated("Read y", level = DeprecationLevel.ERROR)
    @set:Deprecated("Assign y")
    var deprecatedWithPrivateSetter: Int = 0
        private set
    // the same for a val
    @Deprecated("Use z", ReplaceWith("z"))
    @get:Deprecated("Read y", level = DeprecationLevel.ERROR)
    val deprecatedVal: Int = 0
}

@set:Deprecated("Assign y", level = DeprecationLevel.ERROR)
var AccessorDeprecationHolder.errorSetterExtension: Int
    get() = errorSetter
    set(@Suppress("unused") value) {}

// the accessor deprecations of the vars inherited by a final subclass, which cannot call the protected setter
open class AccessorDeprecationBase {
    @set:Deprecated("Assign y", level = DeprecationLevel.ERROR)
    var inheritedErrorSetter: Int = 0
    @get:Deprecated("Read y", level = DeprecationLevel.ERROR)
    @set:Deprecated("Assign y")
    var inheritedProtectedSetter: Int = 0
        protected set
}

class AccessorDeprecationSub : AccessorDeprecationBase()
