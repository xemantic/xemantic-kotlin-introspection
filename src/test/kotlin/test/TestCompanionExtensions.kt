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

// Extensions on a companion object, called on the name of the outer class, like CompanionExtensionHolder.parse("…")
class CompanionExtensionHolder {
    companion object {
        fun create(): CompanionExtensionHolder = CompanionExtensionHolder()
    }
}

fun CompanionExtensionHolder.Companion.parse(text: String): CompanionExtensionHolder = create().also { text.length }

val CompanionExtensionHolder.Companion.default: CompanionExtensionHolder get() = create()

fun CompanionExtensionHolder.describe(): String = "holder"

class NamedCompanionExtensionHolder {
    companion object Factory
}

fun NamedCompanionExtensionHolder.Factory.of(): NamedCompanionExtensionHolder = NamedCompanionExtensionHolder()

// An extension on a companion object deprecated with the HIDDEN level, like one compiled in another module
// before an upgrade of the library hid the companion object, which Kotlin no longer calls as HiddenCompanionExtensionHolder.of()
class HiddenCompanionExtensionHolder {
    @Deprecated("replaced", level = DeprecationLevel.HIDDEN)
    companion object
}

@Suppress("DEPRECATION_ERROR")
fun HiddenCompanionExtensionHolder.Companion.of(): HiddenCompanionExtensionHolder = HiddenCompanionExtensionHolder()

fun HiddenCompanionExtensionHolder.describe(): String = "holder"

// Extensions on a deprecated companion object, called as DeprecatedCompanionExtensionHolder.of(),
// for which Kotlin reports the deprecation of the companion object, next to the one of the extension itself, if any
class DeprecatedCompanionExtensionHolder {
    @Deprecated("Use the constructor")
    companion object
}

@Suppress("DEPRECATION")
fun DeprecatedCompanionExtensionHolder.Companion.of(): DeprecatedCompanionExtensionHolder = DeprecatedCompanionExtensionHolder()

@Suppress("DEPRECATION")
@Deprecated("Use of()", ReplaceWith("of()"), level = DeprecationLevel.ERROR)
fun DeprecatedCompanionExtensionHolder.Companion.create(): DeprecatedCompanionExtensionHolder = DeprecatedCompanionExtensionHolder()

@Suppress("DEPRECATION")
val DeprecatedCompanionExtensionHolder.Companion.default: DeprecatedCompanionExtensionHolder
    get() = DeprecatedCompanionExtensionHolder()

class ErrorCompanionExtensionHolder {
    @Deprecated("Removed", ReplaceWith("ErrorCompanionExtensionHolder()"), level = DeprecationLevel.ERROR)
    companion object
}

@Suppress("DEPRECATION_ERROR")
fun ErrorCompanionExtensionHolder.Companion.of(): ErrorCompanionExtensionHolder = ErrorCompanionExtensionHolder()

// The deprecation of the companion object is rendered within a comment, which its message must not end
class CommentEndingCompanionExtensionHolder {
    @Deprecated("Use /* the */ constructor")
    companion object
}

@Suppress("DEPRECATION")
fun CommentEndingCompanionExtensionHolder.Companion.of(): CommentEndingCompanionExtensionHolder =
    CommentEndingCompanionExtensionHolder()

// Extensions on the companion object of a deprecated class, called as DeprecatedOuterCompanionExtensionHolder.of(),
// for which Kotlin reports the deprecation of the class on its name, written by the caller, unlike the one of the companion object
@Deprecated("Use CompanionExtensionHolder")
class DeprecatedOuterCompanionExtensionHolder {
    companion object {
        fun create(): Int = 0
    }
}

@Suppress("DEPRECATION")
fun DeprecatedOuterCompanionExtensionHolder.Companion.of(): Int = 0

@Suppress("DEPRECATION")
val DeprecatedOuterCompanionExtensionHolder.Companion.default: Int get() = 0

@Deprecated("Removed", level = DeprecationLevel.ERROR)
class ErrorOuterCompanionExtensionHolder {
    companion object
}

@Suppress("DEPRECATION_ERROR")
fun ErrorOuterCompanionExtensionHolder.Companion.of(): Int = 0

@Deprecated("Removed", level = DeprecationLevel.HIDDEN)
class HiddenOuterCompanionExtensionHolder {
    companion object
}

@Suppress("DEPRECATION_ERROR")
fun HiddenOuterCompanionExtensionHolder.Companion.of(): Int = 0

@Deprecated("Use CompanionExtensionHolder")
class DeprecatedOuterOfNestedCompanionExtensionHolder {
    class Nested {
        companion object
    }
}

@Suppress("DEPRECATION")
fun DeprecatedOuterOfNestedCompanionExtensionHolder.Nested.Companion.of(): Int = 0

@Deprecated("Use CompanionExtensionHolder")
class DeprecatedOuterAndCompanionExtensionHolder {
    @Deprecated("Use the constructor")
    companion object
}

@Suppress("DEPRECATION")
fun DeprecatedOuterAndCompanionExtensionHolder.Companion.of(): Int = 0

// Extensions on a supertype of a companion object, called on the name of the class, like SupertypeCompanionHolder.onSupertype(),
// for which Kotlin reports the deprecation of the companion object, like for an extension on the companion object itself
interface CompanionSupertype

class SupertypeCompanionHolder {
    companion object : CompanionSupertype
}

class DeprecatedSupertypeCompanionHolder {
    @Deprecated("Use the constructor")
    companion object : CompanionSupertype
}

fun CompanionSupertype.onSupertype(): Int = 0

fun <T : CompanionSupertype> T.onBoundedSupertype(): Int = 0

val CompanionSupertype.onSupertypeProperty: Int get() = 0

// the supertype of both the class and its companion object, called both as SharedSupertypeCompanionHolder.onSupertype(),
// and as holder.onSupertype(), the first one reporting the deprecation of the companion object, if any
class SharedSupertypeCompanionHolder : CompanionSupertype {
    companion object : CompanionSupertype
}

class DeprecatedSharedSupertypeCompanionHolder : CompanionSupertype {
    @Deprecated("Use the constructor")
    companion object : CompanionSupertype
}

// called only on the name of the class, DeprecatedSharedSupertypeCompanionHolder.onCompanion(), reporting the deprecation
fun DeprecatedSharedSupertypeCompanionHolder.Companion.onCompanion(): Int = 0

// the supertype of both a deprecated class and its companion object, whose extension is called both as
// DeprecatedClassSharedSupertypeCompanionHolder.onSupertype(), reporting the deprecation of the class, and as holder.onSupertype()
@Deprecated("Use CompanionExtensionHolder")
class DeprecatedClassSharedSupertypeCompanionHolder : CompanionSupertype {
    companion object : CompanionSupertype
}

// both the class and its companion object deprecated, each reported for
// DeprecatedClassAndSharedSupertypeCompanionHolder.onSupertype(), and neither for holder.onSupertype()
@Deprecated("Use CompanionExtensionHolder")
class DeprecatedClassAndSharedSupertypeCompanionHolder : CompanionSupertype {
    @Deprecated("Use the constructor")
    companion object : CompanionSupertype
}

// called only on the name of the class, DeprecatedClassSharedSupertypeCompanionHolder.onDeprecatedClassCompanion()
@Suppress("DEPRECATION")
fun DeprecatedClassSharedSupertypeCompanionHolder.Companion.onDeprecatedClassCompanion(): Int = 0

// called as ProtectedSupertypeCompanionHolder.onSupertype() only within its subclasses, as elsewhere,
// while kotlinc 2.4.21 compiles it, it fails with an IllegalAccessError of the protected companion object field
open class ProtectedSupertypeCompanionHolder {
    protected companion object : CompanionSupertype
}

// called as ProtectedOuterSupertypeCompanionHolder.Inner.onSupertype() only within its subclasses, which kotlinc checks
open class ProtectedOuterSupertypeCompanionHolder {
    protected class Inner {
        companion object : CompanionSupertype
    }
}

// overloads on type parameters of the same index, one called on an instance, holder.describeBounded(),
// for which Kotlin reports no deprecation of the class, the other on its name, DeprecatedMarkedCompanionHolder.describeBounded()
interface InstanceMarker

@Deprecated("Use CompanionExtensionHolder")
class DeprecatedMarkedCompanionHolder : InstanceMarker {
    companion object : CompanionSupertype
}

fun <T : InstanceMarker> T.describeBounded(): Int = 0

fun <T : CompanionSupertype> T.describeBounded(): Int = 1

// members of the companion object, called instead of the extensions of the same signature on its supertype,
// like ShadowingSupertypeCompanionHolder.shadowed(), while an overload of another signature is still called
interface ShadowedCompanionSupertype

class ShadowingSupertypeCompanionHolder {
    companion object : ShadowedCompanionSupertype {
        fun shadowed(): Int = 0
        val shadowedProperty: Int = 0
    }
}

fun ShadowedCompanionSupertype.shadowed(): Int = 1

fun ShadowedCompanionSupertype.shadowed(value: Int): Int = value

val ShadowedCompanionSupertype.shadowedProperty: Int get() = 1

// and of the extension on the companion object itself, which kotlinc reports as shadowed by the member
class ShadowingCompanionHolder {
    companion object {
        fun shadowed(): Int = 0
    }
}

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
fun ShadowingCompanionHolder.Companion.shadowed(): Int = 1

fun ShadowingCompanionHolder.Companion.shadowed(value: Int): Int = value

// members of a companion object, or of an object, next to the extensions of the same name on their supertype,
// which kotlinc still calls, as the member cannot be called the same way: by the names of the parameters of the extension,
// like UnshadowingCompanionHolder.named(y = 1), without the default argument, outside the context of the member,
// as an operator, or infix, with an argument the bound of the type parameter of the member does not accept,
// or with a named argument of a vararg parameter, like UnshadowingCompanionHolder.varargMember(x = 1),
// or when the member is deprecated as hidden
interface UnshadowedSupertype

class UnshadowingCompanionHolder {
    companion object : UnshadowedSupertype {
        fun named(x: Int): Int = x
        fun defaulted(x: Int): Int = x
        context(text: String) fun contextual(): Int = text.length
        fun plus(other: Int): Int = other
        fun inf(other: Int): Int = other
        fun <T : Number> bounded(value: T): Int = value.toInt()
        fun varargMember(vararg x: Int): Int = x.size
        @Deprecated("Gone", level = DeprecationLevel.HIDDEN)
        fun hiddenMember(): Int = 0
        fun nullableReceiver(): Int = 0
    }
}

fun UnshadowedSupertype.named(y: Int): Int = y

fun UnshadowedSupertype.defaulted(x: Int = 0): Int = x

fun UnshadowedSupertype.contextual(): Int = 1

operator fun UnshadowedSupertype.plus(other: Int): Int = other

infix fun UnshadowedSupertype.inf(other: Int): Int = other

fun <T> UnshadowedSupertype.bounded(value: T): Int = 1

fun UnshadowedSupertype.varargMember(x: Int): Int = x

fun UnshadowedSupertype.hiddenMember(): Int = 1

// called as UnshadowingCompanionHolder.nullableReceiver(), where the member wins, as the name of an object is never null
fun UnshadowedSupertype?.nullableReceiver(): Int = 1

// UnshadowingObject() calls the operator extension, not the member, and UnshadowingObject.named(y = 1) the extension
object UnshadowingObject : UnshadowedSupertype {
    fun invoke(): Int = 0
    fun named(x: Int): Int = x
}

operator fun UnshadowedSupertype.invoke(): Int = 1

fun UnshadowingObject.named(y: Int): Int = y

// an extension on a type parameter taking it as a parameter, not pinned to the object, which can be inferred as the supertype,
// like UnpinnedUnshadowingObject.unpinned(UnpinnedOther), calling the extension,
// while UnpinnedUnshadowingObject.unpinned(UnpinnedUnshadowingObject) calls the member,
// and pinned to it, like by SelfBounded<PinnedShadowingObject>, where the member is always called
interface UnpinnedSupertype

object UnpinnedOther : UnpinnedSupertype

object UnpinnedUnshadowingObject : UnpinnedSupertype {
    fun unpinned(x: UnpinnedUnshadowingObject): Int = 0
}

class UnpinnedUnshadowingCompanionHolder {
    companion object : UnpinnedSupertype {
        fun unpinned(x: UnpinnedUnshadowingCompanionHolder.Companion): Int = 0
    }
}

fun <T : UnpinnedSupertype> T.unpinned(x: T): Int = 1

interface SelfBounded<T>

object PinnedShadowingObject : SelfBounded<PinnedShadowingObject> {
    fun pinned(x: PinnedShadowingObject): Int = 0
}

fun <T : SelfBounded<T>> T.pinned(x: T): Int = 1

// a JVM-only method taking parameters inherited by an object, never called with named arguments,
// like JvmOnlyUnshadowingObject.sort(c = naturalOrder()), which calls the extension
object JvmOnlyUnshadowingObject : AbstractList<String>() {
    override val size: Int get() = 0
    override fun get(index: Int): String = throw IndexOutOfBoundsException()
}

fun JvmOnlyUnshadowingObject.sort(c: Comparator<in String>): Int = 1

// an inherited member of an object, called instead of the extension on the object, like ShadowingInheritingObject.inherited()
open class ShadowingObjectBase {
    fun inherited(): Int = 0
}

object ShadowingInheritingObject : ShadowingObjectBase()

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
fun ShadowingInheritingObject.inherited(): Int = 1

// members of an object accepting every argument of the extension of the same name on its supertype, always called instead:
// a generic one, like WideningShadowingObject.generic(1), also when its type parameter cannot be inferred,
// like WideningShadowingObject.uninferred(1), which does not compile, one taking a nullable parameter, a supertype of the parameter,
// or the bound of its type parameter, like WideningShadowingObject.bounded(other), a property of a function type,
// like WideningShadowingObject.functionProperty(), and one taking the type argument of the supertype of the object,
// which the type parameter of the receiver of the extension stands for, like WideningShadowingObject.substituted("")
interface WideningSupertype

interface WideningBase<T>

interface WideningConsumer<in T>

interface WideningProducer<out T>

object WideningShadowingObject : WideningSupertype, WideningBase<String>, WideningConsumer<String>, WideningProducer<String> {
    val functionProperty: () -> Int = { 0 }
    fun <T> generic(x: T): Int = 0
    fun <T> uninferred(x: Int): T? = null
    fun nullable(x: Int?): Int = 0
    fun supertype(x: CharSequence): Int = 0
    fun bounded(x: WideningSupertype): Int = 0
    fun substituted(x: String): Int = 0
    fun consumed(x: String): Int = 0
}

fun WideningSupertype.functionProperty(): Int = 1

fun WideningSupertype.generic(x: Int): Int = 1

fun WideningSupertype.uninferred(x: Int): Int = x

fun WideningSupertype.nullable(x: Int): Int = 1

fun WideningSupertype.supertype(x: String): Int = 1

fun <T : WideningSupertype> T.bounded(x: T): Int = 1

fun <T> WideningBase<T>.substituted(x: T): Int = 1

fun <T> WideningConsumer<T>.consumed(x: T): Int = 1

// and the ones which do not accept every argument, so that the extension is still called:
// with null, like NarrowingUnshadowingObject.notNull(null), or NarrowingUnshadowingObject.boundedGeneric(null),
// with a named argument, which a function type does not take, like NarrowingUnshadowingObject.functionProperty(x = 1),
// when the property might be null, or its function type has a receiver, with a subtype of the parameter,
// or with any argument, like NarrowingUnshadowingObject.produced(1), where T of the covariant supertype is inferred as Any
object NarrowingUnshadowingObject : WideningSupertype, WideningProducer<String> {
    val functionProperty: (Int) -> Int = { it }
    val nullableFunctionProperty: (() -> Int)? = null
    val receiverFunctionProperty: WideningSupertype.() -> Int = { 0 }
    fun notNull(x: Any): Int = 0
    fun <T : Any> boundedGeneric(x: T): Int = 0
    fun subtype(x: String): Int = 0
    fun produced(x: String): Int = 0
}

fun WideningSupertype.functionProperty(x: Int): Int = x

fun WideningSupertype.nullableFunctionProperty(): Int = 1

fun WideningSupertype.receiverFunctionProperty(): Int = 1

fun <T> WideningSupertype.notNull(x: T): Int = 1

fun WideningSupertype.boundedGeneric(x: Int?): Int = 1

fun WideningSupertype.subtype(x: CharSequence): Int = 1

fun <T> WideningProducer<T>.produced(x: T): Int = 1

// members of a class called instead of the extensions of the same name on the class, or on its supertype,
// like shadowing.shadowed(), or shadowing.inherited(), by the rule of the members of an object,
// unless the extension can be called on a nullable receiver, like nullableShadowing.onNullable(),
// with other arguments, like shadowing.shadowed(1), or the member cannot be called, like a protected one
interface InstanceShadowedSupertype

open class InstanceShadowingBase {
    fun inherited(): Int = 0
    protected fun protectedMember(): Int = 0
}

open class InstanceShadowing : InstanceShadowingBase(), InstanceShadowedSupertype {
    val shadowedProperty: Int = 0
    fun shadowed(): Int = 0
    fun onNullable(): Int = 0
    fun onNullableBound(): Int = 0
    fun named(x: Int): Int = x
    fun <T> generic(x: T): Int = 0
}

fun InstanceShadowedSupertype.shadowed(): Int = 1

fun InstanceShadowedSupertype.shadowed(x: Int): Int = x

val InstanceShadowedSupertype.shadowedProperty: Int get() = 1

fun InstanceShadowedSupertype?.onNullable(): Int = 1

fun <T : InstanceShadowedSupertype?> T.onNullableBound(): Int = 1

fun InstanceShadowedSupertype.named(y: Int): Int = y

fun InstanceShadowedSupertype.generic(x: String): Int = 1

fun InstanceShadowedSupertype.inherited(): Int = 1

fun InstanceShadowedSupertype.protectedMember(): Int = 1

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
fun InstanceShadowing.shadowed(): Int = 2

// a member taking the type parameter of its class, which might not accept the argument of the extension,
// like GenericInstanceShadowing<Int>().typed(""), next to one which always does, like GenericInstanceShadowing<Int>().shadowed()
class GenericInstanceShadowing<T> : InstanceShadowedSupertype {
    fun shadowed(): Int = 0
    fun typed(x: T): Int = 0
}

fun InstanceShadowedSupertype.typed(x: String): Int = 1
