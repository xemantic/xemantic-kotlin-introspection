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

package com.xemantic.kotlin.introspection.test

// the context parameters are a part of the signature, rendered with their names before the declaration
class ContextParametersSample {
    context(log: Logger)
    fun run() {}
    @Suppress("CONTEXTUAL_OVERLOAD_SHADOWED")
    fun run() {}
    context(log: Logger, c: Ctx)
    fun <T> process(item: T): List<T> = listOf(item)
    context(_: Logger)
    fun anonymous() {}
    context(`in`: Logger)
    fun keyword() {}
    context(log: Logger)
    val label: String get() = ""
    context(log: Logger)
    fun String.decorate(): String = this
    context(log: Logger)
    val String.decorated: String get() = this
}

context(log: Logger)
fun contextRun() {}

context(log: Logger)
val contextLabel: String get() = ""

context(log: Logger)
fun ContextParametersSample.contextExtension() {}

context(log: Logger)
val ContextParametersSample.contextExtensionLabel: String get() = ""

// the context parameters of the members of a companion object, found by a member lookup on the outer class
class ContextCompanionSample {
    companion object {
        context(log: Logger)
        val created: String get() = ""
        context(log: Logger)
        fun create(): ContextCompanionSample = ContextCompanionSample()
    }
}

// the context parameters of an extension of a supertype, found by a member lookup on the subtype
interface ContextSupertype

class ContextSubtype : ContextSupertype

context(log: Logger)
fun ContextSupertype.describeInContext() {}
