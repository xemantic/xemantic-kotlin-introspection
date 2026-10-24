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

// Declarations deprecated with the HIDDEN level, which cannot be called from the source code,
// like the overloads kept only for the binary compatibility, next to their replacements
class HiddenDeprecationTarget(val value: Int) {

    fun member(): Int = value

    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    fun member(factor: Int): Int = value * factor

    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    fun hiddenMember(): Int = value

    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    val hiddenProperty: Int get() = value

    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    var hiddenVariable: Int = 0

    // deprecated with a level other than HIDDEN, which can still be called
    @Deprecated("replaced", level = DeprecationLevel.ERROR)
    fun errorMember(): Int = value

    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    constructor(name: String) : this(name.length)

    constructor(value: Long) : this(value.toInt())

    companion object {
        fun create(): HiddenDeprecationTarget = HiddenDeprecationTarget(0)
        @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
        fun create(value: Int): HiddenDeprecationTarget = HiddenDeprecationTarget(value)
        @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
        const val HIDDEN_CONSTANT: Int = 42
        @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
        @JvmField
        val hiddenField: Int = 42
    }

}

// the only constructor which can be called is hidden
class HiddenOnlyConstructor private constructor(val a: Int) {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    constructor(s: String) : this(s.length)
}

// open, but cannot be subclassed, as its only constructor which is not private is hidden,
// so that its protected members are not part of the API
open class HiddenConstructorOpenClass private constructor(val a: Int) {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    constructor(s: String) : this(s.length)
    protected fun protectedMember() {}
}

fun HiddenDeprecationTarget.extension(): Int = value

@Deprecated("replaced", level = DeprecationLevel.HIDDEN)
fun HiddenDeprecationTarget.extension(factor: Int): Int = value * factor

@Deprecated("replaced", level = DeprecationLevel.HIDDEN)
val HiddenDeprecationTarget.hiddenExtensionProperty: Int get() = value

@Deprecated("replaced", level = DeprecationLevel.HIDDEN)
fun hiddenDeprecationTopLevel(): Int = 42

fun hiddenDeprecationTopLevel(value: Int): Int = value

object HiddenDeprecationExtensions {
    fun HiddenDeprecationTarget.objectExtension(): Int = value
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    fun HiddenDeprecationTarget.objectExtension(factor: Int): Int = value * factor
}

// the synthetic methods for the annotations of the interface properties are in its DefaultImpls class
interface HiddenDeprecationInterface {
    val visible: Int
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    val hiddenAbstract: Int
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    val hiddenWithGetter: Int get() = 1
}

// only the setter is hidden, so that the property can be read, but not assigned
class HiddenSetterHolder(@set:Deprecated("replaced", level = DeprecationLevel.HIDDEN) var inConstructor: Int) {
    @set:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    var inBody: Int = 0
}

@set:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
var HiddenSetterHolder.hiddenSetterExtension: Int
    get() = inBody
    set(@Suppress("unused") value) {}

// only the getter is hidden, so that the property can be assigned, but not read
class HiddenGetterVarHolder(@get:Deprecated("replaced", level = DeprecationLevel.HIDDEN) var inConstructor: Int) {
    @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    var inBody: Int = 0
    // neither read, nor assigned from the outside
    @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    var withPrivateSetter: Int = 0
        private set
    @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    @set:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    var withHiddenSetter: Int = 0
    @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    val hiddenGetterVal: Int = 0
    // deprecated itself too, with a level other than HIDDEN
    @Deprecated("deprecated")
    @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    var deprecatedWithHiddenGetter: Int = 0
    // can be referred to, but neither read, nor assigned
    @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    @set:Deprecated("Assign y", level = DeprecationLevel.ERROR)
    var withErrorSetter: Int = 0
}

// the protected setter can be called by the subclasses
open class HiddenGetterVarOpenHolder {
    @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    var withProtectedSetter: Int = 0
        protected set
}

interface HiddenGetterVarInterface {
    @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    var abstractVar: Int
    @Deprecated("deprecated")
    @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    var deprecatedVar: Int
}

@get:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
var HiddenGetterVarHolder.hiddenGetterExtension: Int
    get() = 0
    set(@Suppress("unused") value) {}

// compiles, as the setters can still be called
@Suppress("unused")
private fun assignHiddenGetterVars(holder: HiddenGetterVarHolder) {
    holder.inConstructor = 1
    holder.inBody = 1
    holder.hiddenGetterExtension = 1
    @Suppress("DEPRECATION")
    holder.deprecatedWithHiddenGetter = 1
}

annotation class HiddenDeprecationAnnotation(
    val visible: Int = 0,
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    val hidden: Int = 1
)

// only the property is hidden, the parameter without a default value must still be passed
annotation class HiddenDeprecationAnnotationWithoutDefault(
    val visible: Int,
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    val hidden: Int
)

annotation class HiddenDeprecationAnnotationAllHidden(
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    val hidden: Int = 1
)
