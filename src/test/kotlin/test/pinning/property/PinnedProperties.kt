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

package com.xemantic.kotlin.introspection.test.pinning.property

// in a package of its own, like the functions of the pinning package
val <T : Comparable<T>> T.rank: Int get() = 0

// a final class comparable only to itself pins T : Comparable<T> to itself,
// so that its more specific extension property is read instead, like PinnedExtensionProperty().rank
class PinnedExtensionProperty : Comparable<PinnedExtensionProperty> {
    override fun compareTo(other: PinnedExtensionProperty): Int = 0
}

val PinnedExtensionProperty.rank: Int get() = 1

// and so does an enum class
enum class PinnedExtensionPropertyEnum { LOW, HIGH }

val PinnedExtensionPropertyEnum.rank: Int get() = 1

// a member property is always read instead
class PinnedMemberProperty : Comparable<PinnedMemberProperty> {
    val rank: Int = 2
    override fun compareTo(other: PinnedMemberProperty): Int = 0
}

// an extension property on the class itself with a context parameter is read only within its context,
// so that PinnedContextualExtensionProperty().rank reads the generic one elsewhere
class PinnedPropertyContext

class PinnedContextualExtensionProperty : Comparable<PinnedContextualExtensionProperty> {
    override fun compareTo(other: PinnedContextualExtensionProperty): Int = 0
}

context(context: PinnedPropertyContext)
val PinnedContextualExtensionProperty.rank: Int get() = 1

// without a more specific one, the generic one is read
class UnpinnedProperty : Comparable<UnpinnedProperty> {
    override fun compareTo(other: UnpinnedProperty): Int = 0
}
