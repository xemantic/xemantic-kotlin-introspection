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

package com.xemantic.kotlin.introspection.test.pinning

// in a package of its own, not to be listed for every comparable class of the test package
fun <T : Comparable<T>> T.between(low: T, high: T): Boolean = this >= low && this <= high

// an enum class pins T : Comparable<T> to itself, also when its entries have bodies,
// so that its more specific overload, a member, or an extension on the enum class itself, is called instead
enum class PinnedMemberEnum {
    LOW { override fun weight(): Int = 1 },
    HIGH;
    open fun weight(): Int = 0
    fun between(low: PinnedMemberEnum, high: PinnedMemberEnum): Boolean = false
}

enum class PinnedExtensionEnum {
    LOW { override fun weight(): Int = 1 },
    HIGH { override fun weight(): Int = 2 };
    abstract fun weight(): Int
}

fun PinnedExtensionEnum.between(low: PinnedExtensionEnum, high: PinnedExtensionEnum): Boolean = false

// without a more specific overload, the generic one is called
enum class UnpinnedEnum {
    LOW { override fun weight(): Int = 1 },
    HIGH;
    open fun weight(): Int = 0
}
