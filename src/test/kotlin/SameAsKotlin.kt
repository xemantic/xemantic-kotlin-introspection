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

import com.xemantic.kotlin.test.sameAs
import org.intellij.lang.annotations.Language

/**
 * Asserts that this string is the same as the [expected] Kotlin string.
 * Delegates to [sameAs] while providing IDE language injection for Kotlin,
 * enabling syntax highlighting and completion in the [expected] parameter.
 *
 * @param expected the expected Kotlin string.
 * @throws AssertionError if the strings are not equal, with unified diff output.
 */
infix fun String?.sameAsKotlin(
    @Language("kotlin") expected: String
) {
    sameAs(expected)
}
