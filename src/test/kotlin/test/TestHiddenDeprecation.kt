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

// an override of a var stays a var, as a val cannot override it, even when its setter is hidden
open class HiddenSetterOverrideBase {
    open var value: Int = 0
}

class HiddenSetterOverride : HiddenSetterOverrideBase() {
    override var value: Int = 0
        @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
        set
}

// the same for a protected one, whose setter has the visibility of the property
open class ProtectedHiddenSetterOverrideBase {
    protected open var value: Int = 0
}

open class ProtectedHiddenSetterOverride : ProtectedHiddenSetterOverrideBase() {
    override var value: Int = 0
        @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
        set
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

// an override in a final class, whose protected setter can be called only by the subclasses of the base class,
// so that holder.overridden can be neither read, nor assigned
open class HiddenGetterOverrideBase {
    open var overridden: Int = 0
        protected set
}

class HiddenGetterOverrideHolder : HiddenGetterOverrideBase() {
    @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override var overridden: Int = 1
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

// an override hidden in a subclass hides the member it overrides too,
// so that holder.member(), or holder.property, of a HiddenOverride does not compile
open class HiddenOverrideBase {
    open fun member(): Int = 0
    open val property: Int = 0
}

open class HiddenOverride : HiddenOverrideBase() {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun member(): Int = 1
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override val property: Int = 1
}

// inheriting the hidden overrides, so that neither can be called on it either
class HiddenOverrideSub : HiddenOverride()

// the members of the same signature merged from several supertypes, one of them hidden:
// the compiler picks the one of the most specific return type, or else the one of the first direct supertype,
// in the order of declaration, and none can be called when the picked one is hidden
interface HiddenMergedRoot {
    fun f(): Int
}

interface HiddenMergedLeft : HiddenMergedRoot {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun f(): Int
}

interface HiddenMergedRight : HiddenMergedRoot {
    override fun f(): Int
}

// join.f() does not compile
interface HiddenMergedJoin : HiddenMergedLeft, HiddenMergedRight

interface HiddenMergedJoinReversed : HiddenMergedRight, HiddenMergedLeft

// a visible override of the hidden one, which the compiler still does not prefer over the hidden one of the first supertype
interface HiddenMergedRestored : HiddenMergedLeft {
    override fun f(): Int
}

interface HiddenMergedRestoredSub : HiddenMergedRestored

// join.f() does not compile
interface HiddenMergedJoinRestored : HiddenMergedLeft, HiddenMergedRestored

// join.f() does not compile
interface HiddenMergedJoinRestoredSub : HiddenMergedLeft, HiddenMergedRestoredSub

interface HiddenMergedJoinRestoredSubReversed : HiddenMergedRestoredSub, HiddenMergedLeft

// the visible override of a more specific return type wins in either order
interface HiddenMergedAnyRoot {
    fun h(): Any
}

interface HiddenMergedAnyLeft : HiddenMergedAnyRoot {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun h(): Any
}

interface HiddenMergedStringRight : HiddenMergedAnyRoot {
    override fun h(): String
}

interface HiddenMergedSpecificJoin : HiddenMergedAnyLeft, HiddenMergedStringRight

interface HiddenMergedSpecificJoinReversed : HiddenMergedStringRight, HiddenMergedAnyLeft

// unrelated to the hidden override of the superclass, holder.member() does not compile
interface HiddenMergedMember {
    fun member(): Int
}

class HiddenMergedOverride : HiddenOverride(), HiddenMergedMember

// with the type arguments of the supertypes substituted, join.g("") does not compile
interface HiddenMergedGeneric<T> {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    fun g(x: T)
}

interface HiddenMergedString {
    fun g(x: String)
}

interface HiddenMergedGenericJoin : HiddenMergedGeneric<String>, HiddenMergedString

// holder.o("") does not compile
open class HiddenMergedGenericClass<T> {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    open fun o(x: T) {}
}

interface HiddenMergedStringParameter {
    fun o(x: String)
}

class HiddenMergedGenericClassJoin : HiddenMergedGenericClass<String>(), HiddenMergedStringParameter

class HiddenMergedGenericClassJoinReversed : HiddenMergedStringParameter, HiddenMergedGenericClass<String>()

// the same signature as written, but not with the type arguments substituted, so holder.f(1) compiles,
// the bound telling apart the JVM signatures, so that the class does not override the final method on the JVM
abstract class HiddenMergedArgumentsA<T> {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    fun f(@Suppress("unused") x: T) {}
}

interface HiddenMergedArgumentsB<T : Number> {
    fun f(@Suppress("unused") x: T) {}
}

class HiddenMergedArguments : HiddenMergedArgumentsA<String>(), HiddenMergedArgumentsB<Int>

// the hidden member contributed through an intermediate supertype, which picks it as the first of its own supertypes,
// so that nested.f() does not compile, neither on its subtypes
interface HiddenMergedVia : HiddenMergedLeft

interface HiddenMergedNested : HiddenMergedVia, HiddenMergedRight

interface HiddenMergedNestedSub : HiddenMergedNested

interface HiddenMergedOther

interface HiddenMergedNestedJoin : HiddenMergedNested, HiddenMergedOther

// the same through a superclass, holder.f() does not compile
abstract class HiddenMergedLeftClass : HiddenMergedLeft

abstract class HiddenMergedClassJoin : HiddenMergedLeftClass(), HiddenMergedRight

abstract class HiddenMergedClassJoinSub : HiddenMergedClassJoin()

// a property, join.p does not compile
interface HiddenMergedPropertyRoot {
    val p: Int
}

interface HiddenMergedPropertyLeft : HiddenMergedPropertyRoot {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override val p: Int
}

interface HiddenMergedPropertyRight : HiddenMergedPropertyRoot {
    override val p: Int
}

interface HiddenMergedPropertyJoin : HiddenMergedPropertyLeft, HiddenMergedPropertyRight

// the hidden member of the more specific return type wins over the visible one of the first supertype,
// so that join.h() does not compile
interface HiddenMergedHiddenStringLeft : HiddenMergedAnyRoot {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun h(): String
}

interface HiddenMergedAnyRight : HiddenMergedAnyRoot {
    override fun h(): Any
}

interface HiddenMergedHiddenSpecificJoin : HiddenMergedAnyRight, HiddenMergedHiddenStringLeft

// the extension is called, as the member picked is hidden
interface HiddenMergedExtended : HiddenMergedLeft, HiddenMergedRight

fun HiddenMergedExtended.f(): String = ""

// the same for an extension on a type parameter pinned to the final class, which an inherited member would shadow
interface HiddenMergedSelf<T>

class HiddenMergedPinned : HiddenOverride(), HiddenMergedMember, HiddenMergedSelf<HiddenMergedPinned>

fun <T : HiddenMergedSelf<T>> T.member(): String = ""

// compiles, as the merged members picked are not hidden
@Suppress("unused")
private fun callMergedMembers(
    joinReversed: HiddenMergedJoinReversed,
    restoredSubReversed: HiddenMergedJoinRestoredSubReversed,
    specific: HiddenMergedSpecificJoin,
    specificReversed: HiddenMergedSpecificJoinReversed,
    genericClassReversed: HiddenMergedGenericClassJoinReversed,
    arguments: HiddenMergedArguments,
    extended: HiddenMergedExtended,
    pinned: HiddenMergedPinned
) {
    joinReversed.f()
    restoredSubReversed.f()
    specific.h()
    specificReversed.h()
    genericClassReversed.o("")
    arguments.f(1)
    extended.f().length
    pinned.member().length
}
