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
 * Escapes the [name] of a context parameter like [escapeIdentifier],
 * except for the anonymous one, rendered as `_`, as it is declared:
 * the one declared as `_`, written to the metadata as the special name `<unused var>`,
 * the legacy context receiver, which kotlin-metadata-jvm reads as a context parameter named `_`,
 * and the one with no name, as kotlin-reflect gives it.
 */
internal fun escapeContextParameterName(name: String?): String =
    if (name == null || name == "_" || (name.startsWith('<') && name.endsWith('>'))) "_" else escapeIdentifier(name)

/**
 * Formats the context parameters of a function or property, given as their names and rendered types,
 * like `context(log: Logger, _: Ctx) `, preceding all the other modifiers, or an empty string if there are none.
 * Their names are a part of the signature, as a function body can refer to them.
 */
internal fun formatContextParameters(parameters: List<Pair<String?, String>>): String =
    if (parameters.isEmpty()) "" else parameters.joinToString(", ", prefix = "context(", postfix = ") ") { (name, type) ->
        "${escapeContextParameterName(name)}: $type"
    }

/**
 * Escapes each segment of a [name] qualified with `.`, like `pkg.Outer.Nested`, with [escapeIdentifier],
 * as no segment can contain a `.`, which is not allowed in a JVM name.
 */
internal fun escapeQualifiedName(name: String): String =
    name.split('.').joinToString(".") { escapeIdentifier(it) }

/**
 * Tells if this string is an identifier accepted by the Kotlin compiler:
 * a letter or `_`, followed by letters, `_` and digits, checked by code points,
 * as a letter can be outside of the Basic Multilingual Plane, like `𝑥`.
 * The letters are the ones of [Character.isLetter], without the letter numbers, like `Ⅻ`,
 * which the Kotlin grammar lists, but the compiler does not accept.
 * A name made of underscores only, like `_`, is reserved, unless escaped.
 */
private fun String.isIdentifier(): Boolean {
    val codePoints = codePoints().toArray()
    return codePoints.first().let { it == '_'.code || Character.isLetter(it) } &&
        codePoints.all { it == '_'.code || Character.isLetter(it) || Character.isDigit(it) } &&
        any { it != '_' }
}

/**
 * Removes the backticks escaping any segment of a [name] qualified with `.`, like ``Outer.`in` ``,
 * the reverse of [escapeQualifiedName], so that the names can be given as they are rendered.
 * No escaped segment can contain a `.`, which is not allowed in a JVM name, even in backticks.
 */
internal fun unescapeQualifiedName(name: String): String =
    name.split('.').joinToString(".") { it.removeSurrounding("`") }
