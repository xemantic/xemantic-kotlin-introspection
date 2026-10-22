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

package com.xemantic.kotlin.introspection

/**
 * The hard keywords of Kotlin, which cannot be used as identifiers unless escaped in backticks,
 * unlike the soft and modifier keywords, like `data`, `value`, `open`, `by` or `get`.
 */
private val hardKeywords = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
    "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
    "val", "var", "when", "while"
)

/**
 * Escapes the [name] of a declaration in backticks, like `` `in` ``, when it would not parse as an identifier:
 * when it is a hard keyword, or contains characters an identifier cannot,
 * like a space, or `$`, which is valid in a JVM name, but not in a Kotlin identifier,
 * where it would also be confused with a string template.
 * An empty name, or the one containing a backtick, which cannot be escaped, is returned as it is.
 */
internal fun escapeIdentifier(name: String): String =
    if (name.isEmpty() || '`' in name || (name.isIdentifier() && name !in hardKeywords)) name else "`$name`"

/**
 * Escapes each segment of a [name] qualified with `.`, like `pkg.Outer.Nested`, with [escapeIdentifier],
 * as no segment can contain a `.`, which is not allowed in a JVM name.
 */
internal fun escapeQualifiedName(name: String): String =
    name.split('.').joinToString(".") { escapeIdentifier(it) }

/**
 * Tells if this string is an identifier by the Kotlin grammar:
 * a letter or `_`, followed by letters, `_` and digits.
 */
private fun String.isIdentifier(): Boolean =
    first().isIdentifierLetter() && all { it.isIdentifierLetter() || it.isDigit() }

private fun Char.isIdentifierLetter(): Boolean =
    this == '_' || isLetter() || category == CharCategory.LETTER_NUMBER
