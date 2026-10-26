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
 * @property level the level in effect, see [deprecationOf], of this one only, not of its [accessors],
 *   like of a `val` deprecated itself, which might be lower than the one of its getter.
 * @property target what is deprecated: the declaration itself,
 *   or only an accessor of a `var`, rendered as `@get:Deprecated`, or `@set:Deprecated`, see [varDeprecationOf].
 * @property accessors the deprecations of the accessors of a `var`, deprecated apart from this one, each targeting its accessor,
 *   so that all of them are rendered, like `@Deprecated("…") @set:Deprecated("…", level = DeprecationLevel.ERROR) var a: Int`,
 *   or the one of the getter of a `val`, deprecated itself too, see [ofReading].
 */
internal class Deprecation(
    val message: String,
    val replaceWith: String?,
    val level: DeprecationLevel,
    val target: Target = Target.DECLARATION,
    val accessors: List<Deprecation> = emptyList()
) {

    enum class Target { DECLARATION, GETTER, SETTER }

    /**
     * Whether the declaration cannot be used in the source code at all, and therefore is not rendered,
     * never when only an accessor of a `var` is hidden, see [hidesGetter] and [hidesSetter].
     */
    val isHidden: Boolean get() = level == DeprecationLevel.HIDDEN && target == Target.DECLARATION

    /**
     * Whether the getter of a `var` is hidden, while the property itself is not,
     * so that it is part of the API only when its setter is, as it can still be assigned, but not read.
     */
    val hidesGetter: Boolean get() = hides(Target.GETTER)

    /**
     * Whether the setter of a `var` is hidden, so that it can still be read, but not assigned, rendered as a `val`.
     */
    val hidesSetter: Boolean get() = hides(Target.SETTER)

    private fun hides(accessor: Target): Boolean =
        (target == accessor && level == DeprecationLevel.HIDDEN) || accessors.any { it.hides(accessor) }

    /**
     * The deprecation of reading the property, rendered as a `val`, as its setter cannot be called, or as it is one:
     * the one of the property itself, followed by the one of its getter, like
     * `@Deprecated("Use z", ReplaceWith("z")) @get:Deprecated("Read y", level = DeprecationLevel.ERROR) val q: Int`,
     * so that neither message is lost, or the one of its getter alone, targeting the declaration,
     * or the hidden one of its getter, targeting the declaration, as a property which cannot be read is hidden as a whole,
     * or `null`, when only its setter is deprecated.
     */
    fun ofReading(): Deprecation? {
        if (target == Target.DECLARATION && accessors.isEmpty()) return this
        val getter = (listOf(this) + accessors).firstOrNull { it.target == Target.GETTER }
        return when {
            target != Target.DECLARATION || getter?.level == DeprecationLevel.HIDDEN -> getter?.targeting(Target.DECLARATION)
            getter == null -> targeting(Target.DECLARATION)
            else -> targeting(Target.DECLARATION, accessors = listOf(getter))
        }
    }

    /**
     * This deprecation of the [target], with the [accessors].
     */
    fun targeting(target: Target, accessors: List<Deprecation> = emptyList()): Deprecation =
        Deprecation(message, replaceWith, level, target, accessors)

}

/**
 * The deprecation of a `var` property, as reading and assigning it are deprecated apart:
 * its own one, the [propertyDeprecation], followed by the ones of its accessors,
 * the [getterDeprecation] and the [setterDeprecation], each targeting its accessor, see [Deprecation.accessors],
 * the first one present telling the others, which is the one of an accessor, when the property itself is not deprecated.
 * A hidden property is hidden as a whole, whatever the deprecations of its accessors.
 */
internal fun varDeprecationOf(
    propertyDeprecation: Deprecation?,
    getterDeprecation: Deprecation?,
    setterDeprecation: Deprecation?
): Deprecation? {
    if (propertyDeprecation?.isHidden == true) return propertyDeprecation
    val deprecations = listOfNotNull(
        propertyDeprecation,
        getterDeprecation?.targeting(Deprecation.Target.GETTER),
        setterDeprecation?.targeting(Deprecation.Target.SETTER)
    )
    val first = deprecations.firstOrNull() ?: return null
    return if (deprecations.size == 1) first else first.targeting(first.target, accessors = deprecations.drop(1))
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
