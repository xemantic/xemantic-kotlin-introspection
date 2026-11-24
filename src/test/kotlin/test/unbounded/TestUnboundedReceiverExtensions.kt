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

// The extensions on an unbounded type parameter, like fun <T> T.f(), are listed for the classes of their own package,
// like the extensions on Any there, but not for the classes of the other packages, like BoundedReceiverOnlyA, see #366

fun <T> T.unboundedReceiver() {}

fun <T> T?.unboundedNullableReceiver() {}

val <T> T.unboundedProperty: Int get() = 0

// never called on a class next to an extension of the same name on a nullable type of its supertype,
// like Any?, see TestUnboundedShadowingExtensions, called instead also on a nullable receiver,
// unlike next to one on a type which is not nullable, like UnboundedStringPair, called on UnboundedStringPair? then

fun <T> T.unboundedNextToNullableAny() {}

fun <T> T?.unboundedNullableNextToNullableAny() {}

fun <T> T.unboundedNextToNullableClass() {}

fun <T> T.unboundedNextToClass() {}

interface UnboundedPair<A, B>

interface UnboundedStringPair : UnboundedPair<String, String>

class UnboundedHolder<V>
