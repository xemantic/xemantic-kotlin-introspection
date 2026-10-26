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
    @Deprecated("Removed", level = DeprecationLevel.ERROR)
    companion object
}

@Suppress("DEPRECATION_ERROR")
fun ErrorCompanionExtensionHolder.Companion.of(): ErrorCompanionExtensionHolder = ErrorCompanionExtensionHolder()
