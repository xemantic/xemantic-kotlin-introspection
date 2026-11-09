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

// called as ProtectedSupertypeCompanionHolder.onSupertype() only within its subclasses
open class ProtectedSupertypeCompanionHolder {
    protected companion object : CompanionSupertype
}

// called as ProtectedOuterSupertypeCompanionHolder.Inner.onSupertype() only within its subclasses
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
