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

// while the compiler reports no hidden deprecation of an override of a member of kotlin.Any,
// so that anyOverride.toString() and anyOverrideSub.toString() compile
interface HiddenAnyOverride {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun toString(): String
}

abstract class HiddenAnyOverrideSub : HiddenAnyOverride

// but it does of an override of equals, which kotlinc 2.4 rejects equalsOverride.equals(null) of, unchecked by the build
interface HiddenAnyEqualsOverride {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun equals(other: Any?): Boolean
}

abstract class HiddenAnyEqualsOverrideSub : HiddenAnyEqualsOverride

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

// the visible member of the first direct supertype, which the hidden one of the second overrides, is still picked,
// so that join.f() compiles, while reversed.f() does not
interface HiddenMergedOverriddenJoin : HiddenMergedRoot, HiddenMergedLeft

interface HiddenMergedOverriddenJoinReversed : HiddenMergedLeft, HiddenMergedRoot

// the same with the hidden one contributed through an intermediate supertype, also on a subtype, and through a superclass
interface HiddenMergedOverriddenVia : HiddenMergedRoot, HiddenMergedVia

interface HiddenMergedOverriddenViaSub : HiddenMergedOverriddenVia

abstract class HiddenMergedOverriddenClassJoin : HiddenMergedRoot, HiddenMergedLeftClass()

abstract class HiddenMergedOverriddenClassJoinReversed : HiddenMergedLeftClass(), HiddenMergedRoot

// the hidden override declared by a supertype which is not part of the API still hides the member it overrides,
// so that neither hiddenClass.f(), nor internal.f() compiles
@Deprecated("replaced", level = DeprecationLevel.HIDDEN)
interface HiddenMergedHiddenClassLeft : HiddenMergedRoot {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun f(): Int
}

@Suppress("DEPRECATION_ERROR")
interface HiddenMergedHiddenClassSub : HiddenMergedHiddenClassLeft

internal interface HiddenMergedInternalLeft : HiddenMergedRoot {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun f(): Int
}

@Suppress("EXPOSED_SUPER_INTERFACE")
interface HiddenMergedInternalSub : HiddenMergedInternalLeft

// while a visible override declared by a supertype which is not part of the API stops the hidden one it overrides,
// the member it overrides standing for it, so that restored.f(), restoredClass.f() and restoredLeft.f() compile
internal interface HiddenMergedInternalRestored : HiddenMergedInternalLeft {
    override fun f(): Int
}

@Suppress("EXPOSED_SUPER_INTERFACE")
interface HiddenMergedInternalRestoredSub : HiddenMergedInternalRestored

@Deprecated("replaced", level = DeprecationLevel.HIDDEN)
abstract class HiddenMergedHiddenLeftClass : HiddenMergedRoot {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    abstract override fun f(): Int
}

@Suppress("DEPRECATION_ERROR")
@Deprecated("replaced", level = DeprecationLevel.HIDDEN)
abstract class HiddenMergedHiddenRestoredClass : HiddenMergedHiddenLeftClass() {
    override fun f(): Int = 1
}

@Suppress("DEPRECATION_ERROR")
abstract class HiddenMergedHiddenRestoredClassSub : HiddenMergedHiddenRestoredClass()

internal interface HiddenMergedInternalRestoredLeft : HiddenMergedLeft {
    override fun f(): Int
}

@Suppress("EXPOSED_SUPER_INTERFACE")
interface HiddenMergedInternalRestoredLeftSub : HiddenMergedInternalRestoredLeft

// the same for the override of a Java method, standing for it, so that internalThread.run() compiles
internal abstract class HiddenMergedInternalThread : Thread() {
    override fun run() {}
}

@Suppress("EXPOSED_SUPER_CLASS")
abstract class HiddenMergedInternalThreadSub : HiddenMergedInternalThread()

// of the modality of the override, the final one of HiddenMergedInternalFinalThread, which kotlinc 2.4 does not let
// a subclass override, unchecked by the build, or the open one of HiddenMergedInternalCallable, overridden below
internal open class HiddenMergedInternalFinalThread : Thread() {
    final override fun run() {}
}

@Suppress("EXPOSED_SUPER_CLASS")
open class HiddenMergedInternalFinalThreadSub : HiddenMergedInternalFinalThread()

internal abstract class HiddenMergedInternalCallable : java.util.concurrent.Callable<String> {
    override fun call(): String = ""
}

@Suppress("EXPOSED_SUPER_CLASS")
abstract class HiddenMergedInternalCallableSub : HiddenMergedInternalCallable()

abstract class HiddenMergedInternalCallableOverride : HiddenMergedInternalCallableSub() {
    override fun call(): String = "overridden"
}

// the hidden member of the same JVM signature as the Java method of the first direct supertype does not hide it,
// so that join.run() compiles, while reversed.run() calls the run scope function instead
interface HiddenMergedRun {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    fun run()
}

abstract class HiddenMergedRunJoin : Runnable, HiddenMergedRun

abstract class HiddenMergedRunJoinReversed : HiddenMergedRun, Runnable

abstract class HiddenMergedRunThread : Thread(), HiddenMergedRun

// the same when the hidden member overrides the Java method, which is still picked of the first direct supertype
interface HiddenMergedRunOverride : Runnable {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun run()
}

abstract class HiddenMergedRunOverrideJoin : Runnable, HiddenMergedRunOverride

abstract class HiddenMergedRunOverrideJoinReversed : HiddenMergedRunOverride, Runnable

// of a platform return type, like the V! of call(), or the Thread! of newThread(r), the Java member loses to the hidden one,
// equally specific, though declared first, so that neither join.call(), nor join.newThread(r) compiles
interface HiddenMergedCall : java.util.concurrent.Callable<String> {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun call(): String
}

abstract class HiddenMergedCallJoin : java.util.concurrent.Callable<String>, HiddenMergedCall

interface HiddenMergedNullableCall : java.util.concurrent.Callable<String> {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun call(): String?
}

abstract class HiddenMergedNullableCallJoin : java.util.concurrent.Callable<String>, HiddenMergedNullableCall

interface HiddenMergedThreadFactory : java.util.concurrent.ThreadFactory {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun newThread(r: Runnable): Thread
}

abstract class HiddenMergedThreadFactoryJoin : java.util.concurrent.ThreadFactory, HiddenMergedThreadFactory

// while the V! of call() of Callable<String?> is String?, no platform type, as of a nullable type argument,
// so that the Java member, declared first, is picked, and join.call() compiles
interface HiddenMergedNullableArgumentCall : java.util.concurrent.Callable<String?> {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun call(): String?
}

abstract class HiddenMergedNullableArgumentCallJoin :
    java.util.concurrent.Callable<String?>, HiddenMergedNullableArgumentCall

// while the get() of Supplier, which Kotlin enhances not to return a platform type, is still picked, so that join.get() compiles
interface HiddenMergedSupplier : java.util.function.Supplier<String> {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun get(): String
}

abstract class HiddenMergedSupplierJoin : java.util.function.Supplier<String>, HiddenMergedSupplier

// a Java method overriding the enhanced one does not inherit the enhancement, and neither does an overload,
// so that it returns a platform type, losing to the hidden member, and join.get(), join.apply(""), nor reversed.apply("") compile
abstract class HiddenMergedJavaSupplierJoin :
    com.xemantic.kotlin.introspection.test.other.JavaHiddenMergedSupplier, HiddenMergedSupplier

// also a Java method overriding the enhanced one by the same JVM descriptor, so that join.get() does not compile
abstract class HiddenMergedJavaRedeclaredSupplierJoin :
    com.xemantic.kotlin.introspection.test.other.JavaRedeclaredSupplier<String>, HiddenMergedSupplier

// also a JDK method inherited through a Java class not declaring it again, so that join.get() does not compile
abstract class HiddenMergedJavaInheritedSupplierJoin :
    com.xemantic.kotlin.introspection.test.other.JavaInheritingSupplier<String>(), HiddenMergedSupplier

// unless the JDK method is inherited directly too, whatever the order of the supertypes, so that join.get() compiles
abstract class HiddenMergedJavaInheritedDirectSupplierJoin :
    com.xemantic.kotlin.introspection.test.other.JavaInheritingSupplier<String>(), java.util.function.Supplier<String>,
    HiddenMergedSupplier

// a suspend function type merged with a hidden invoke of another supertype, picked as the first one, so that join(1) compiles
interface HiddenMergedSuspendInvoke : suspend (Int) -> String {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override suspend fun invoke(p1: Int): String
}

abstract class HiddenMergedSuspendInvokeJoin : suspend (Int) -> String, HiddenMergedSuspendInvoke

// the computeIfAbsent of java.util.HashMap keeps the enhancement of the one of java.util.Map, so that join.computeIfAbsent(…) compiles,
// while the one of a Java override does not, losing to the hidden member, so that javaJoin.computeIfAbsent(…) does not,
// nor the one of java.util.HashMap inherited through a Java class not declaring it again
interface HiddenMergedComputeIfAbsent : MutableMap<String, String> {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun computeIfAbsent(key: String, mappingFunction: java.util.function.Function<in String, out String>): String
}

abstract class HiddenMergedComputeIfAbsentJoin : java.util.HashMap<String, String>(), HiddenMergedComputeIfAbsent

abstract class HiddenMergedJavaComputeIfAbsentJoin :
    com.xemantic.kotlin.introspection.test.other.JavaComputingMap<String, String>(), HiddenMergedComputeIfAbsent

abstract class HiddenMergedJavaInheritedComputeIfAbsentJoin :
    com.xemantic.kotlin.introspection.test.other.JavaInheritingMap<String, String>(), HiddenMergedComputeIfAbsent

interface HiddenMergedFunction : java.util.function.Function<String, String> {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun apply(t: String): String
}

abstract class HiddenMergedJavaFunctionJoin :
    com.xemantic.kotlin.introspection.test.other.JavaHiddenMergedFunction, HiddenMergedFunction

abstract class HiddenMergedJavaFunctionJoinReversed :
    HiddenMergedFunction, com.xemantic.kotlin.introspection.test.other.JavaHiddenMergedFunction

interface HiddenMergedFunctionOverload : com.xemantic.kotlin.introspection.test.other.JavaHiddenMergedFunctionOverload {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun apply(s: CharSequence): String
}

abstract class HiddenMergedJavaFunctionOverloadJoin :
    com.xemantic.kotlin.introspection.test.other.JavaHiddenMergedFunctionOverload, HiddenMergedFunctionOverload

// the visible member of the first direct supertype is still picked when the hidden one of the second overrides
// a visible override of it in turn, which stands for it only on the second supertype, so that join.f() compiles,
// also through a superclass, and the run() of Runnable next to a superclass of java.lang.Thread,
// while kotlinc 2.4 rejects reversed.f(), unchecked by the build
interface HiddenMergedMid : HiddenMergedRoot {
    override fun f(): Int
}

interface HiddenMergedMidLeft : HiddenMergedMid {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun f(): Int
}

interface HiddenMergedMidJoin : HiddenMergedRoot, HiddenMergedMidLeft

interface HiddenMergedMidJoinReversed : HiddenMergedMidLeft, HiddenMergedRoot

abstract class HiddenMergedMidClass : HiddenMergedRoot {
    override fun f(): Int = 1
}

abstract class HiddenMergedMidLeftClass : HiddenMergedMidClass() {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun f(): Int = 2
}

abstract class HiddenMergedMidClassJoin : HiddenMergedRoot, HiddenMergedMidLeftClass()

abstract class HiddenMergedHiddenRunThread : Thread() {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    override fun run() {}
}

abstract class HiddenMergedHiddenRunThreadJoin : Runnable, HiddenMergedHiddenRunThread()

// compiles, as the merged members picked are not hidden
@Suppress("unused")
private fun callMergedMembers(
    overriddenJoin: HiddenMergedOverriddenJoin,
    overriddenViaSub: HiddenMergedOverriddenViaSub,
    overriddenClassJoin: HiddenMergedOverriddenClassJoin,
    restored: HiddenMergedInternalRestoredSub,
    @Suppress("DEPRECATION_ERROR") restoredClass: HiddenMergedHiddenRestoredClassSub,
    restoredLeft: HiddenMergedInternalRestoredLeftSub,
    runOverrideJoin: HiddenMergedRunOverrideJoin,
    supplierJoin: HiddenMergedSupplierJoin,
    runJoin: HiddenMergedRunJoin,
    runThread: HiddenMergedRunThread,
    joinReversed: HiddenMergedJoinReversed,
    restoredSubReversed: HiddenMergedJoinRestoredSubReversed,
    specific: HiddenMergedSpecificJoin,
    specificReversed: HiddenMergedSpecificJoinReversed,
    genericClassReversed: HiddenMergedGenericClassJoinReversed,
    arguments: HiddenMergedArguments,
    extended: HiddenMergedExtended,
    pinned: HiddenMergedPinned,
    midJoin: HiddenMergedMidJoin,
    midClassJoin: HiddenMergedMidClassJoin,
    hiddenRunThreadJoin: HiddenMergedHiddenRunThreadJoin,
    internalThread: HiddenMergedInternalThreadSub,
    nullableArgumentCallJoin: HiddenMergedNullableArgumentCallJoin,
    anyOverride: HiddenAnyOverride,
    anyOverrideSub: HiddenAnyOverrideSub,
    computeIfAbsentJoin: HiddenMergedComputeIfAbsentJoin,
    inheritedDirectSupplierJoin: HiddenMergedJavaInheritedDirectSupplierJoin
) {
    overriddenJoin.f()
    overriddenViaSub.f()
    overriddenClassJoin.f()
    restored.f()
    restoredClass.f()
    restoredLeft.f()
    runOverrideJoin.run()
    supplierJoin.get()
    runJoin.run()
    runThread.run()
    joinReversed.f()
    restoredSubReversed.f()
    specific.h()
    specificReversed.h()
    genericClassReversed.o("")
    arguments.f(1)
    extended.f().length
    pinned.member().length
    midJoin.f()
    midClassJoin.f()
    hiddenRunThreadJoin.run()
    internalThread.run()
    nullableArgumentCallJoin.call()
    anyOverride.toString()
    anyOverrideSub.toString()
    computeIfAbsentJoin.computeIfAbsent("") { it }
    inheritedDirectSupplierJoin.get()
}

// compiles, as the suspend invoke picked is not hidden
@Suppress("unused")
private suspend fun callMergedSuspendInvoke(join: HiddenMergedSuspendInvokeJoin) {
    join(1)
}
