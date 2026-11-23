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
import com.xemantic.kotlin.introspection.test.PlatformMixed
import com.xemantic.kotlin.introspection.test.PlatformSink
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

// T & Any in a contravariant position is a subtype of String, also of the platform type String! given through Java,
// of T inferred as String?, which is no Comparable<String?>, unlike T : List<T>?, which no String? contradicts

interface TypedStringSink : PlatformSink<String>

interface TypedJavaStringSink : JavaPlatformBoxes.SinkOfString

fun <T : Comparable<T>?> PlatformSink<T & Any>.sinkOfNotNullComparable() {}

fun <T : List<T>?> PlatformSink<T & Any>.sinkOfNotNullSelfList() {}

fun <T> PlatformSink<T & Any>.sinkOfNotNull() {}

// unless a bound of another type parameter refers to T, like U : T, which, given explicitly, as String, makes T a String

fun <T : Comparable<T>?, U : T> PlatformSink<T & Any>.sinkOfNotNullComparableAndBounded() {}

// V of V : W, of W which no supertype refers to, takes any type, like String?, as W does

interface TypedFreelyBoundedMixed<W, V : W> : TypedNotNullMixed<V>

// V? is the platform type String! of PlatformBox<String!> of PassingString, either String, or String?, for V of String

interface TypedSinkOfNullableBoxAndOther<V> : TypedSink<PlatformBox<V?>>, TypedOther<V>

fun <P> P.sinkOfStringBoxAndStringOther() where P : TypedSink<JavaPlatformBoxes.PassingString>, P : TypedOther<String> {}

// T & Any satisfies its bounds as the nullable type also on a type parameter receiver of that sole bound,
// and next to a type parameter bounded only by T, which nothing refers to, telling nothing more of T

interface TypedNullableListsBox<V> : TypedSelfBox<List<V?>>

fun <P : TypedSelfBox<T & Any>, T : List<T>?> P.selfBoxReceiverOfNotNull() {}

fun <T : List<T>?, U : T> TypedSelfBox<T & Any>.selfBoxOfNotNullAndBounded() {}

// but not of W which another bound refers to, like X : MutableList<W>, pinning it to Int, no supertype of String?

interface TypedPinnedBoundMixed<W, V : W, X : MutableList<W>> : TypedNotNullMixed<V>, TypedOther<X>

fun <P> P.mixedOfNullableStringAndOtherOfIntList() where P : TypedMixed<String, String?>, P : TypedOther<MutableList<Int>> {}

// V? against a platform type, like String! of PlatformBox<String!>, tells nothing where the types a type parameter
// is inferred from are compared, like T of PlatformMixed<T, T>, nor next to a bound which is another type parameter, like V : K

interface TypedMixedOfNullableBoxes<V> : JavaPlatformBoxes.MixedOfBoxes<V?>, TypedOther<V>

fun <P, T> P.mixedOfSameAndIntOther() where P : PlatformMixed<T, T>, P : TypedOther<Int> {}

interface TypedSinkOfNullableKeyBoxAndOther<K, V : K> : TypedSink<PlatformBox<K?>>, TypedOther<V>

// nor of W bounding V which another bound refers to, like X : V, of X of Int, no subtype of V of String?

interface TypedBoundingFreelyBoundedMixed<W, V : W, X : V> : TypedNotNullMixed<V>, TypedOther<X>

fun <P> P.mixedOfNullableStringAndIntOther() where P : TypedMixed<String, String?>, P : TypedOther<Int> {}

// nor is the type parameter of an outer class, like O of O : Number, told against a platform type

class TypedOuter<O : Number> {

    abstract inner class Inner<V> : TypedSink<PlatformBox<O?>>, TypedOther<V>

}

fun TypedSink<JavaPlatformBoxes.PassingString>.sinkOfStringBox() {}

// nor is X : K, of K of String, an Int, next to V of the unconstrained W

interface TypedFreelyBoundedMixedAndBoundedOther<W, V : W, K, X : K> : TypedNotNullMixed<V>, TypedSelfBox<K>, TypedOther<X>

fun <P> P.mixedOfNullableStringAndStringBoxAndIntOther() where P : TypedMixed<String, String?>, P : TypedSelfBox<String>, P : TypedOther<Int> {}

fun <P> P.mixedOfNullableStringAndStringBoxAndStringOther() where P : TypedMixed<String, String?>, P : TypedSelfBox<String>, P : TypedOther<String> {}

// nor the type parameter of an outer class, like O of O : Number, a String, next to V of the unconstrained W

class TypedBoundedOuter<O : Number> {

    abstract inner class Inner<W, V : W> : TypedNotNullMixed<V>, TypedOther<O>

}

fun <P> P.mixedOfNullableStringAndStringOther() where P : TypedMixed<String, String?>, P : TypedOther<String> {}

// a bound which is another type parameter is checked against the types both take, like X : V, of V of String, no Int,
// also through other type parameters, like C : B of B : A, which V of the unconstrained A is a subtype of as well,
// like the bound of a type parameter of an outer class

interface TypedBoxAndBoundedOther<V, X : V> : TypedSelfBox<V>, TypedOther<X>

fun <P> P.stringBoxAndIntOther() where P : TypedSelfBox<String>, P : TypedOther<Int> {}

fun <P> P.stringBoxAndStringOther() where P : TypedSelfBox<String>, P : TypedOther<String> {}

interface TypedChainBoundedBoxAndOther<A, B : A, C : B> : TypedSelfBox<A>, TypedOther<C>

interface TypedChainBoundedMixed<A, B : A, C : B> : TypedNotNullMixed<C>

class TypedBoundedOuterOfBox<O : Number> {

    abstract inner class Inner<V> : TypedSelfBox<V>, TypedOther<O>

}
