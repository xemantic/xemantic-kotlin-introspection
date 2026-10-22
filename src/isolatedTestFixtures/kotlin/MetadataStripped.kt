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

// A test strips the @Metadata of MetadataStrippedBase, like R8 or shading might,
// so that only its JVM members are known, and removes the JVM methods shrunk(Int) and shrunkAlone(),
// like R8 might, keeping them only in the metadata of MetadataStrippedSubclass
abstract class MetadataStrippedBase<T> {
    abstract val presentable: String
    abstract val isReady: Boolean
    abstract var count: Int
    abstract val String.measure: Int
    abstract fun String.describe(x: Int): String
    abstract suspend fun load(id: String): String
    abstract fun onEvent(callback: suspend (String) -> Unit)
    abstract fun process(item: T)
    abstract fun shrunk(x: Int)
    abstract fun shrunk(x: String)
}

abstract class MetadataStrippedSubclass : MetadataStrippedBase<String>() {
    override val presentable: String = ""
    override val isReady: Boolean = true
    override var count: Int = 0
    override val String.measure: Int get() = 0
    val Int.measure: Int get() = 0
    override fun String.describe(x: Int): String = ""
    fun describe(x: Int): String = ""
    override suspend fun load(id: String): String = ""
    fun load(id: String, extra: Int): String = ""
    override fun onEvent(callback: suspend (String) -> Unit) {}
    override fun process(item: String) {}
    fun process(item: Int) {}
    override fun shrunk(x: Int) {}
    override fun shrunk(x: String) {}
    fun shrunkAlone() {}
}
