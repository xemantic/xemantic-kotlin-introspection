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
 * The deprecation of a declaration, in effect for the code compiled against the standard library of the classpath,
 * which the Kotlin metadata does not tell.
 *
 * @property message the message of the `@Deprecated` annotation.
 * @property replaceWith the expression of its `ReplaceWith`, if any, without the imports.
 * @property level the level in effect, see [deprecationOf].
 * @property target what is deprecated: the declaration itself,
 *   or only the getter of a `var`, which can still be assigned, rendered as `@get:Deprecated`, see [hiddenGetter].
 * @property hiddenGetter the hidden deprecation of only the getter of a `var`, whose own deprecation is this one,
 *   when it is not hidden, so that both are rendered, like `@Deprecated("…") @get:Deprecated("…", level = HIDDEN) var a: Int`.
 */
internal class Deprecation(
    val message: String,
    val replaceWith: String?,
    val level: DeprecationLevel,
    val target: Target = Target.DECLARATION,
    val hiddenGetter: Deprecation? = null
) {

    enum class Target { DECLARATION, GETTER }

    /**
     * Whether the declaration cannot be used in the source code at all, and therefore is not rendered,
     * never when only the getter of a `var` is hidden, see [hidesGetter].
     */
    val isHidden: Boolean get() = level == DeprecationLevel.HIDDEN && target == Target.DECLARATION

    /**
     * Whether the getter of a `var` is hidden, while the property itself is not,
     * so that it is part of the API only when its setter is, as it can still be assigned, but not read.
     */
    val hidesGetter: Boolean get() = (level == DeprecationLevel.HIDDEN && target == Target.GETTER) || hiddenGetter != null

}

/**
 * The deprecation of a `var` property, whose own one, of the property itself, is the [propertyDeprecation],
 * and the one of its getter is the [getterDeprecation]: when only the getter is hidden, it is kept apart,
 * see [Deprecation.hidesGetter], otherwise the higher of the two, see [highestOf].
 */
internal fun varDeprecationOf(propertyDeprecation: Deprecation?, getterDeprecation: Deprecation?): Deprecation? =
    if (getterDeprecation?.isHidden == true && propertyDeprecation?.isHidden != true) {
        val hiddenGetter = getterDeprecation.run { Deprecation(message, replaceWith, level, Deprecation.Target.GETTER) }
        propertyDeprecation?.run { Deprecation(message, replaceWith, level, target, hiddenGetter) } ?: hiddenGetter
    } else {
        highestOf(propertyDeprecation, getterDeprecation)
    }

/**
 * The attributes of the `@Deprecated` and `@DeprecatedSinceKotlin` annotations of a declaration,
 * as read from a class file, or from kotlin-reflect, each `null` when absent, or when it cannot be read.
 *
 * @property level the name of the [DeprecationLevel] entry, given explicitly to `@Deprecated`.
 */
internal class DeprecationAnnotations {
    var isDeprecated: Boolean = false
    var message: String? = null
    var replaceWith: String? = null
    var level: String? = null
    var isDeprecatedSinceKotlin: Boolean = false
    var warningSince: String? = null
    var errorSince: String? = null
    var hiddenSince: String? = null
}

/**
 * The deprecation in effect, telling by [isSince] whether a Kotlin version of `@DeprecatedSinceKotlin`
 * is not newer than the one the user code is compiled with, or `null`, when not deprecated.
 *
 * The level of `@DeprecatedSinceKotlin`, which the standard library uses to deprecate gradually,
 * is the highest one reached since the version of its `warningSince`, `errorSince`, or `hiddenSince`,
 * and when none is reached, the declaration is not deprecated yet,
 * while `@Deprecated` alone tells its own level, `WARNING` by default.
 */
internal fun DeprecationAnnotations.deprecationOf(isSince: (version: String) -> Boolean): Deprecation? {
    val explicitLevel = level?.let { name -> DeprecationLevel.entries.firstOrNull { it.name == name } }
    val effectiveLevel = if (isDeprecatedSinceKotlin) {
        val sinceKotlinLevel = when {
            hiddenSince?.let(isSince) == true -> DeprecationLevel.HIDDEN
            errorSince?.let(isSince) == true -> DeprecationLevel.ERROR
            warningSince?.let(isSince) == true -> DeprecationLevel.WARNING
            // not deprecated yet, intentionally cancelling an explicit level too, as the compiler does
            else -> return null
        }
        // an explicit level is not expected next to @DeprecatedSinceKotlin, which the compiler reports,
        // while kotlin-reflect tells the default WARNING one of a built-in declaration
        if (explicitLevel != null) maxOf(sinceKotlinLevel, explicitLevel) else sinceKotlinLevel
    } else {
        if (!isDeprecated) return null
        explicitLevel ?: DeprecationLevel.WARNING
    }
    return Deprecation(message.orEmpty(), replaceWith?.takeIf { it.isNotEmpty() }, effectiveLevel)
}

/**
 * The higher of the deprecations of one declaration told by several sources, like its getter and its annotations,
 * so that a hidden one is never overridden by a lower one.
 */
internal fun highestOf(first: Deprecation?, second: Deprecation?): Deprecation? = when {
    first == null -> second
    second == null -> first
    second.level > first.level -> second
    else -> first
}
