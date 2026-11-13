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

package com.xemantic.kotlin.introspection.test.nullable

import com.xemantic.kotlin.introspection.test.PlatformBox
import com.xemantic.kotlin.introspection.test.other.JavaPlatformBoxes

// A type parameter of a class standing for a type of either nullability, like V of V!, V?, or V & Any,
// takes one type fitting all its positions, like String for PlatformBox<String?> and TypedOther<String> of TypedPassedBox<V>,
// in a package of its own, as these extensions are listed on any map there

interface TypedOther<T>

interface TypedSource<out T>

interface TypedSelfBox<T>

interface TypedMixed<out A, in B>

interface TypedNotNullMixed<T> : TypedMixed<T & Any, T>

// V passed through Java is the platform type V!, like String! for V of String, a PlatformBox<String?> as well

interface TypedPassedBox<V> : JavaPlatformBoxes.Passing<V>, TypedOther<V>

fun <P> P.nullableBoxAndOther() where P : PlatformBox<String?>, P : TypedOther<String> {}

fun <P> P.nullableBoxAndNullableOther() where P : PlatformBox<String?>, P : TypedOther<String?> {}

fun <P> P.boxAndNullableOther() where P : PlatformBox<String>, P : TypedOther<String?> {}

// but not the type arguments of a Kotlin type given for a type parameter of Java, like V of PlatformBox<List<V>!>

interface TypedPassedListBox<V> : JavaPlatformBoxes.Passing<List<V>>, TypedSource<V?>

fun <P> P.nullableListBoxAndNullableSource() where P : PlatformBox<List<String?>>, P : TypedSource<String?> {}

interface TypedPassedNullableListBox<V> : JavaPlatformBoxes.Passing<List<V?>>

fun PlatformBox<List<String>>.boxOfStringList() {}

fun PlatformBox<List<String?>>.boxOfNullableStringList() {}

// a Java map of the same type parameter for keys and values, a MutableMap<V!, V!>, is a Map<String?, String> for V of String,
// and so is a Map<V, V & Any> for V of String?

open class TypedSameKeyMap<V> : JavaPlatformBoxes.SameKeyMap<V>()

interface TypedNotNullValueMap<V> : Map<V, V & Any>

fun Map<String?, String>.nullableKeysOfStrings() {}

// V & Any is never nullable, and satisfies the bounds of V as V does, like no List<String> of V : List<V>?,
// nor is V a String? of V : Comparable<V>, also in a contravariant position, or of V : Comparable<V>?,
// as String is no Comparable<String?>

interface TypedSelfListOfNotNull<V : List<V>?> : TypedSelfBox<V & Any>

fun TypedSelfBox<List<String>>.selfBoxOfStringList() {}

interface TypedComparableMixed<V : Comparable<V>> : TypedNotNullMixed<V>

interface TypedNullableComparableMixed<V : Comparable<V>?> : TypedNotNullMixed<V>

fun TypedMixed<String, String?>.mixedOfNullableString() {}

fun TypedMixed<String?, String?>.mixedOfNullableStrings() {}

fun TypedMixed<String, String>.mixedOfString() {}

// like no PlatformBox<String?>, see boxOfNullableString of the test package

interface TypedNotNullOfBox<V> : PlatformBox<V & Any>

interface TypedNotNullListsBox<V> : JavaPlatformBoxes.NotNullList<V>

fun PlatformBox<List<String>?>.boxOfNullableList() {}

// T & Any in an invariant position, of T inferred as the type matched, or as its nullable type, satisfies its bounds as either,
// like List<TypedSelfList?>? of T : List<T>?, but not List<TypedSelfList?>

interface TypedSelfList : List<TypedSelfList>

fun <T : List<T>?> TypedSelfBox<T & Any>.selfBoxOfNotNull() {}

interface TypedNullableSelfListsBox : TypedSelfBox<List<TypedSelfList?>>

// and so for a list of a nullable type parameter, like PlatformBox<(Mutable)List<V?>>, List<V?>? of T for V of TypedSelfList

fun <T : List<T>?> PlatformBox<T & Any>.boxOfSelfListOfNotNull() {}

interface TypedNullableNotNullListsBox<V> : JavaPlatformBoxes.NotNullList<V?>

// also of a Kotlin list passed through Java, like PlatformBox<List<V?>!>, whose bound check takes V? as List<V?>! does

interface TypedPassedNullableSelfListsBox<V> : JavaPlatformBoxes.Passing<List<V?>>

// V & Any passed through Java is the platform type (V & Any)!, from V & Any to V?, like String! for V of String?

interface TypedPassedNotNullBox<V> : JavaPlatformBoxes.Passing<V & Any>, TypedOther<V>

// a platform type of a supertype of a type of the extension, like String! of PlatformBox<String!> of PassingString,
// in a contravariant position, takes V as String, no Int

interface TypedSink<in T>

interface TypedSinkOfBoxAndOther<V> : TypedSink<PlatformBox<V>>, TypedOther<V>

fun <P> P.sinkOfStringBoxAndIntOther() where P : TypedSink<JavaPlatformBoxes.PassingString>, P : TypedOther<Int> {}

// V of V : W, of W : Any, is not nullable, no String?

interface TypedBoundedMixed<W : Any, V : W> : TypedNotNullMixed<V>

// nor is V of V : TypedCmp<V>? a TypedCmpSelf?, which is no TypedCmp<TypedCmpSelf?>

interface TypedCmp<in T>

class TypedCmpSelf : TypedCmp<TypedCmpSelf>

interface TypedCmpMixed<V : TypedCmp<V>?> : TypedNotNullMixed<V>

fun TypedMixed<TypedCmpSelf, TypedCmpSelf?>.mixedOfNullableCmpSelf() {}

// T of TypedSink<T> for TypedSink<List<TypedSelfList?>> is the not nullable List<TypedSelfList?>, no List<List<TypedSelfList?>>?

interface TypedNullableSelfListsBoxAndSink : TypedSelfBox<List<TypedSelfList?>>, TypedSink<List<TypedSelfList?>>

fun <P, T : List<T>?> P.selfBoxAndSink() where P : TypedSelfBox<T & Any>, P : TypedSink<T> {}

fun <P, T : List<T>?> P.sinkAndSelfBox() where P : TypedSink<T>, P : TypedSelfBox<T & Any> {}

// nor is it for TypedOther<V>, which V of TypedSelfBox<List<V?>> makes T as well, a not nullable List<V?>

interface TypedNullableSelfListsBoxAndOther<V> : TypedSelfBox<List<V?>>, TypedOther<V>

fun <P, T : List<T>?> P.selfBoxAndOther() where P : TypedSelfBox<T & Any>, P : TypedOther<T> {}

// nor is it for List<V?> of V fixed by another bound, like TypedOther<Int>, as only a type referring to no type parameter
// of the class is taken as its nullable type, not modelled otherwise

fun <P, T : List<T>?> P.selfBoxAndIntOther() where P : TypedSelfBox<T & Any>, P : TypedOther<Int> {}
