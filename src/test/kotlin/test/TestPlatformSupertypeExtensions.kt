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

import com.xemantic.kotlin.introspection.test.other.JavaPlatformBoxes

// A platform type of Java given for a type parameter used as definitely not nullable, like String! for T of T & Any,
// is not nullable, like String, and for one used as nullable, like T?, it is nullable, like String?,
// neither of them a platform type any more, unlike its own type arguments, like String! of PlatformWrap<String!>,
// see PlatformNotNullBox, and PlatformNullableBox, of the other module, which the interfaces of JavaPlatformBoxes extend

fun PlatformBox<PlatformWrap<String>>.wrapOfString() {}

fun PlatformBox<PlatformWrap<String?>>.wrapOfNullableString() {}

fun PlatformBox<PlatformWrap<String>?>.nullableWrapOfString() {}

fun PlatformBox<PlatformWrap<String?>?>.nullableWrapOfNullableString() {}

fun <T> PlatformBox<PlatformWrap<T?>>.wrapOfNullable() {}

fun <T> PlatformBox<PlatformWrap<T & Any>>.wrapOfNotNull() {}

fun <T> PlatformBox<T & Any>.boxOfNotNull() {}

class PlatformJavaNotNullStringBox : JavaPlatformBoxes.NotNullString

class PlatformJavaNotNullWrapBox : JavaPlatformBoxes.NotNullWrap

class PlatformJavaNullableWrapBox : JavaPlatformBoxes.NullableWrap

// reached also through Kotlin, of no platform type, kotlinc keeps both, the class being either of them
interface PlatformBothNotNullWrapBox : PlatformNotNullBox<PlatformWrap<String>>, JavaPlatformBoxes.NotNullWrap

interface PlatformBothNullableWrapBox : PlatformNullableBox<PlatformWrap<String>>, JavaPlatformBoxes.NullableWrap

// a nullable type is no definitely not nullable one, like String? for T & Any, also of Kotlin alone,
// nor is a type parameter of the class used as definitely not nullable, like V & Any, a nullable type

class PlatformNullableStringBox : PlatformBox<String?>

class PlatformNotNullParameterBox<V> : PlatformBox<V & Any>

fun PlatformBox<String?>.boxOfNullableString() {}

fun PlatformBox<String>.boxOfString() {}

// a type parameter used as definitely not nullable in an invariant position is the type itself, nullable or not,
// satisfying its bounds as such, like not List<PlatformSelfSubList>, no List<List<PlatformSelfSubList>>,
// while no supertype of it can be inferred instead, unlike in a covariant position

interface PlatformSelfBox<T>

interface PlatformSelfList : List<PlatformSelfList>

interface PlatformSelfSubList : PlatformSelfList

fun <T : List<T>?> PlatformSelfBox<T & Any>.selfListOfNotNull() {}

class PlatformSelfSubListsBox : PlatformSelfBox<List<PlatformSelfSubList>>

class PlatformSelfListBox : PlatformSelfBox<PlatformSelfList>

// a JDK class named as a type argument, which kotlin-reflect describes as a built-in type, like java.lang.Integer as Int,
// is the Java class, a Comparable<java.lang.Integer>, no Comparable<String>, nor a T : Comparable<T> for kotlinc

interface PlatformSource<out T>

fun PlatformSource<Comparable<String>>.sourceOfStringComparable() {}

fun <T : Comparable<T>> PlatformSource<T>.sourceOfComparable() {}

interface PlatformJavaIntegerSource : PlatformSource<java.lang.Integer>

// a type parameter of the class passed through a Java supertype, a platform type there, like V! of PlatformBox<V!>,
// is the nullable type of another position as well, like String? of PlatformOther<V>, or the not nullable one

interface PlatformOther<T>

interface PlatformPassedBox<V> : JavaPlatformBoxes.Passing<V>, PlatformOther<V>

fun <P> P.nullableBoxAndOther() where P : PlatformBox<String?>, P : PlatformOther<String?> {}

fun <P> P.nullableBoxAndNotNullOther() where P : PlatformBox<String?>, P : PlatformOther<String> {}

fun <P> P.nullableBoxAndOtherOrder() where P : PlatformBox<String?>, P : PlatformOther<CollectionOrder> {}

// a type parameter of the class used as definitely not nullable still satisfies its bounds referring to itself,
// like no List<String> of V : List<V>?, nor is one of a not nullable bound nullable, like V : Comparable<V>

interface PlatformSelfListParameterBox<V : List<V>?> : PlatformSelfBox<V & Any>

fun PlatformSelfBox<List<String>>.selfBoxOfStrings() {}

interface PlatformMixed<out A, in B>

interface PlatformNotNullMixed<T> : PlatformMixed<T & Any, T>

interface PlatformComparableMixed<V : Comparable<V>> : PlatformNotNullMixed<V>

fun PlatformMixed<String, String?>.mixedNullableStrings() {}

fun PlatformMixed<String, String>.mixedStrings() {}

// also when passed through a Java supertype, a platform type there, the nullable type, or the not nullable one, of a nullable type

interface PlatformPassedSelfListBox<V : List<V>> : JavaPlatformBoxes.Passing<V>

fun PlatformBox<List<String>?>.boxOfNullableStrings() {}

fun PlatformBox<PlatformSelfList?>.boxOfNullableSelfList() {}
