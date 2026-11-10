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
    formatContext(parameters.map { (name, type) -> "${escapeContextParameterName(name)}: $type" })

/**
 * Formats the context list of a declaration, like `context(log: Logger) `, given its rendered [elements],
 * the context parameters, or the legacy context receivers of a class, or an empty string if there are none.
 */
internal fun formatContext(elements: List<String>): String =
    if (elements.isEmpty()) "" else elements.joinToString(", ", prefix = "context(", postfix = ") ")

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

/**
 * The name of the synthetic property which Kotlin reads a Java getter named [getterName] as, like `name` of `getName()`,
 * or `null` if it is not named like a getter: `get`, or `is`, followed by a character which is neither a lowercase ASCII letter,
 * like of `getaB()`, nor a digit, like of `get1()`, as verified with kotlinc 2.4.21.
 * The one of an `is` getter is named like the getter, like `isActive`, of any return type,
 * the one of a `get` getter without the prefix, its leading ASCII uppercase letters lowercased,
 * but the last one of several followed by a lowercase letter, like `url` of `getURL()`, or `foObar` of `getFOObar()`,
 * while any other letter stays, like `Äx` of `getÄx()`.
 */
internal fun syntheticPropertyName(getterName: String): String? {
    val prefix = when {
        getterName.startsWith("get") -> "get"
        getterName.startsWith("is") -> "is"
        else -> return null
    }
    val first = getterName.getOrNull(prefix.length) ?: return null
    if (first in 'a'..'z' || first.isDigit()) return null
    if (prefix == "is") return getterName
    val name = getterName.substring(prefix.length)
    val uppercase = name.indexOfFirst { it !in 'A'..'Z' }.let { if (it == -1) name.length else it }
    return when (uppercase) {
        0 -> name
        name.length -> name.lowercase()
        1 -> name.first().lowercaseChar() + name.substring(1)
        else -> name.substring(0, uppercase - 1).lowercase() + name.substring(uppercase - 1)
    }
}

/**
 * The name of the setter of the synthetic property of a getter named [getterName], see [syntheticPropertyName],
 * like `setName` of `getName`, or `setActive` of `isActive`.
 */
internal fun syntheticPropertySetterName(getterName: String): String =
    "set" + getterName.removePrefix(if (getterName.startsWith("get")) "get" else "is")
