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

package com.xemantic.kotlin.introspection.test.witness

import com.xemantic.kotlin.introspection.test.other.JavaPlatformBoxes

// An extension of a type parameter bounded recursively, like T : Comparable<T>, on a kotlin.* or java.* supertype
// referring to the type parameters of the class, is listed when a type the type parameter of the class can take,
// a witness, like String, makes it callable, in a package of its own, as these extensions are listed on any map there

fun <T : Comparable<T>> Map<T, T>.witnessSameComparables() {}

fun <K : Comparable<K>, V> Map<K, V>.witnessComparableKeys() {}

fun <T : Comparable<T>> Map<T, List<T>>.witnessComparableKeysOfLists() {}

// a V of String makes a HashMap<V, V>, or a Map<V, V>, a Map<T, T> of T : Comparable<T>,
// and a V of interface X : List<X>, Comparable<X> a Map<V, V> a Map<T, List<T>>, Nothing standing for such a declared type

abstract class WitnessSameKeyHashMap<V> : HashMap<V, V>()

interface WitnessSameKeyMap<V> : Map<V, V>

// also the MutableMap<V!, V!> of a Java subclass

abstract class WitnessJavaSameKeyMap<V> : JavaPlatformBoxes.SameKeyMap<V>()

// a V of interface X : List<X>, Comparable<X>, Nothing standing for it, is a V : List<V> making a Map<V, V> one

interface WitnessSelfListMap<V : List<V>> : Map<V, V>

// no V : Any makes a Map<V, V?> a Map<T, T>, a Map<String, String?> at best, which is a Map<K, V> of K : Comparable<K>

interface WitnessNullableValueMap<V : Any> : Map<V, V?>

// and no V a Map<V, Comparable<V>> a Map<T, T>, nor a Map<T, List<T>>

interface WitnessComparableValueMap<V> : Map<V, Comparable<V>>

// a K of String, and a V of List<String>, make a Map<K, V> a Map<T, List<T>>

interface WitnessKeyValueMap<K, V> : Map<K, V>

// no witness is given for a function type, like (V) -> Unit, which kotlinc matches in ways not modelled

fun <T : Comparable<T>> List<T>.witnessComparableElements() {}

interface WitnessFunctionList<V> : List<(V) -> Unit>

// nor Nothing for a type parameter of a final class, which no declared type is a subtype of, like V : WitnessFinal,
// whose only V making a Map<V, V> a Map<K, V> of V : Comparable<V> is Nothing, also passed through Java, the platform type V!

class WitnessFinal

fun <K, V : Comparable<V>> Map<K, V>.witnessComparableValues() {}

abstract class WitnessJavaFinalKeyMap<V : WitnessFinal> : JavaPlatformBoxes.SameKeyMap<V>()

interface WitnessFinalKeyMap<V : WitnessFinal> : Map<V, V>

// also passed to a Java class within a type argument, like V of List<PassingList<V>>

fun <T : Comparable<T>> List<List<T>>.witnessComparableElementsOfLists() {}

interface WitnessListOfJavaLists<V : WitnessFinal> : List<JavaPlatformBoxes.PassingList<V>>

// no witness is given through a Java supertype annotating a type argument, like List<@Nullable V>, a List<V?>

interface WitnessNullableElementList<V> : JavaPlatformBoxes.NullableElementList<V>

// each type parameter is given its own witness, also of more of them than can all be combined

interface WitnessThreeKeyValueMap<A, K, V> : Map<K, V>

// also of so many of them that their combinations are past a Long, like 4^32 of 32 type parameters

interface WitnessManyTypeParametersList<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, A23, A24, A25, A26, A27, A28, A29, A30, A31, A32> : List<A1>

// nor through a Java supertype annotating a type argument which only a type argument of a supertype of another class reaches,
// like the List<V?> of the NullableElementList<V> of WitnessProducerOfNullableElementList<V>, also through a Java class

interface WitnessProducer<out T>

interface WitnessProducerOfNullableElementList<V> : WitnessProducer<JavaPlatformBoxes.NullableElementList<V>>

interface WitnessListOfProducersOfNullableElementLists<V> : List<WitnessProducerOfNullableElementList<V>>

fun <T : Comparable<T>> List<WitnessProducer<Iterable<T>>>.witnessComparableElementsOfProducedIterables() {}

interface WitnessListOfListsOfNullableElementLists<V> : List<JavaPlatformBoxes.ListOfNullableElementLists<V>>

fun <T : Comparable<T>> List<Iterable<Iterable<T>>>.witnessComparableElementsOfIterablesOfIterables() {}

// while such a Java class only a bound refers to, which no supertype reaches, leaves the witnesses, like the V of a declared type

interface WitnessNullableElementListKeyMap<V : JavaPlatformBoxes.NullableElementList<V>> : Map<V, V>

// Nothing stands for a type which can be declared, like interface X : List<X>, Comparable<X> of a Map<V, V>
// as a Map<T, List<T>?>, but not for a V : CharSequence, as no type is both a CharSequence and a List of itself,
// clashing on get(index: Int), nor for a V of a final class, like the V : WitnessFinal of WitnessFinalKeyMap,
// but for a platform type V! as well, as kotlinc takes X! like X, like the MutableMap<V!, V!> of WitnessJavaSameKeyMap

fun <T : Comparable<T>> Map<T, List<T>?>.witnessComparableKeysOfNullableLists() {}

interface WitnessCharSequenceKeyMap<V : CharSequence> : Map<V, V?>

// while a CharSequence can be a List<Char>, like abstract class Y : CharSequence, List<Char>, Comparable<Y>,
// its get(index: Int) returning a Char either way

fun <T> Map<T, T>.witnessComparableCharLists() where T : List<Char>, T : Comparable<T> {}

interface WitnessCharSequenceSameKeyMap<V : CharSequence> : Map<V, V>

// the bounds of a type parameter of the extension tell what a type of the class it stands for is a subtype of, see #386:
// a List<V?> standing for T of WitnessBox<T & Any> is a List<T?>? of T : List<T?>?, so that V is a List<V?> too,
// like interface X : List<X?>, but not of a V : Int?, as Int is final, nor of a V : CharSequence, clashing on get(index: Int)

interface WitnessBox<T>

fun <T : List<T?>?> WitnessBox<T & Any>.witnessSelfListBoxes() {}

interface WitnessListBox<V> : WitnessBox<List<V?>>

interface WitnessIntListBox<V : Int?> : WitnessBox<List<V?>>

interface WitnessCharSequenceListBox<V : CharSequence> : WitnessBox<List<V?>>

// a V : Comparable<String> is no Comparable<V> of a Map<V, V> as a Map<T, List<T>>, which kotlinc rejects ("has inconsistent values"),
// unlike the String of a Map<T, T>
interface WitnessStringComparableKeyMap<V : Comparable<String>> : Map<V, V>

// nor can a type be declared extending a sealed class of another module, see #386, nor a class whose constructors are all private,
// unlike through an open sealed subclass

sealed class WitnessSealed

interface WitnessSealedKeyMap<V : WitnessSealed> : Map<V, V>

sealed class WitnessSealedWithOpenSubclass {
    abstract class Open : WitnessSealedWithOpenSubclass()
}

interface WitnessOpenSealedKeyMap<V : WitnessSealedWithOpenSubclass> : Map<V, V>

abstract class WitnessPrivatelyConstructed private constructor()

interface WitnessPrivatelyConstructedKeyMap<V : WitnessPrivatelyConstructed> : Map<V, V>

// a V of List<String>, of the List<T> the receiver takes in its place, with the String it takes in the place of T,
// makes a Map<V, String> a Map<List<T>, T>, see #386
fun <T : Comparable<T>> Map<List<T>, T>.witnessListKeysOfComparableValues() {}

interface WitnessStringValueMap<V> : Map<V, String>

// though constructed only internally, extended from another module through a public subclass of its own module,
// like by abstract class Y : WitnessInternallyConstructedSubclass(), List<Y>, Comparable<Y>, see #386
abstract class WitnessInternallyConstructed internal constructor()

abstract class WitnessInternallyConstructedSubclass : WitnessInternallyConstructed()

interface WitnessInternallyConstructedKeyMap<V : WitnessInternallyConstructed> : Map<V, V>

interface WitnessInternallyConstructedBox<V : WitnessInternallyConstructed> : WitnessBoxOfComparable<V>

interface WitnessBoxOfComparable<T>

fun <T : Comparable<T>> WitnessBoxOfComparable<T>.witnessComparableBox() {}

// also of a class referring to itself, where the receiver is not matched against such a supertype, see #386,
// like the V of interface X : WitnessSelfBoundedKeyMap<X>, Comparable<X>, or the String of a Map<V, V>
// next to a Comparable<WitnessComparableToItselfMap<V>>
interface WitnessSelfBoundedKeyMap<V : WitnessSelfBoundedKeyMap<V>> : Map<V, V>

interface WitnessComparableToItselfMap<V> : Map<V, V>, Comparable<WitnessComparableToItselfMap<V>>
