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

package com.xemantic.kotlin.introspection.isolated

// Declarations deprecated with the WARNING level, which a test annotates with @DeprecatedSinceKotlin,
// allowed only in the standard library, hiding them since an older, or a future, Kotlin version.
class HiddenSinceHolder {

    @Deprecated("replaced")
    fun hiddenSincePast(): Int = 1

    @Deprecated("replaced")
    fun hiddenSinceFuture(): Int = 2

    @Deprecated("replaced")
    val hiddenSinceProperty: Int get() = 3

}
