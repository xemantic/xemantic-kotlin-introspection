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

// a package with a segment named like a hard keyword, which has to be escaped wherever the package is rendered,
// like in a fully qualified type, or in the `// from` header of the extensions to import from it
package com.xemantic.kotlin.introspection.test.`in`

import com.xemantic.kotlin.introspection.test.KeywordPackageClient

class KeywordPackageSample(val value: Int) {
    class Nested
    fun nested(): Nested = Nested()
}

sealed interface KeywordPackageSealed

class KeywordPackageSealedImpl : KeywordPackageSealed

fun KeywordPackageSample.extension(): KeywordPackageSample = this

fun KeywordPackageClient.keywordPackageExtension(): KeywordPackageSample = sample()

fun keywordPackageFunction(sample: KeywordPackageSample): Int = sample.value

// top-level properties, whose names cannot be told from the methods of the file facade
const val KEYWORD_PACKAGE_CONSTANT: Int = 42

@JvmField
val keywordPackageField: String = ""

val URL: String get() = ""
