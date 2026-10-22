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

// Class extended with extensions of every visibility a top-level declaration can have
class ExtensionVisibilityTarget(val value: Int)

fun ExtensionVisibilityTarget.helper(): Int = value

private fun ExtensionVisibilityTarget.helper(factor: Int): Int = value * factor

internal fun ExtensionVisibilityTarget.helper(factor: Long): Long = value * factor

@PublishedApi
internal fun ExtensionVisibilityTarget.publishedHelper(): Int = value

val ExtensionVisibilityTarget.publicCache: Int get() = value

private val ExtensionVisibilityTarget.privateCache: Int get() = value

internal val ExtensionVisibilityTarget.internalCache: Int get() = value

// non-public extensions of a built-in type
private fun String.privateStringHelper(): String = this

internal fun String.internalStringHelper(): String = this

// uses the private extensions, so that the compiler does not report them as unused
internal fun useVisibilityTestExtensions(): Int =
    ExtensionVisibilityTarget(1).run { helper(2) + privateCache } + "".privateStringHelper().length
