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

package com.xemantic.kotlin.introspection.test.unbounded

// the more specific extensions of the ones on an unbounded type parameter of TestUnboundedReceiverExtensions,
// in a file of their own, as they take the same JVM signatures

fun Any?.unboundedNextToNullableAny() {}

fun Any?.unboundedNullableNextToNullableAny() {}

fun UnboundedStringPair?.unboundedNextToNullableClass() {}

fun UnboundedStringPair.unboundedNextToClass() {}
