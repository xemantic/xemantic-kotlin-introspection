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

import com.xemantic.kotlin.introspection.test.other.JavaInvokedInheritedTask
import com.xemantic.kotlin.introspection.test.other.JavaInvokedTask
import com.xemantic.kotlin.introspection.test.other.JavaInvokedTaskOf
import com.xemantic.kotlin.introspection.test.other.JavaSyntheticShadowingBase

// members taking a type whose type arguments accept the ones of the parameter of the extension of the same name,
// by their variance, called instead of it, like projectedShadowing.outProjected(listOf("")):
// a projection, a covariant, or a contravariant, type argument, also nested, or of a function type,
// and the bound of a type parameter of the extension
interface ProjectedShadowedSupertype

class ProjectedComparable<T> : Comparable<List<T>> {
    override fun compareTo(other: List<T>): Int = 0
}

interface ProjectedBox<T>

class ProjectedShadowing : ProjectedShadowedSupertype {
    fun outProjected(x: Collection<out CharSequence>): Int = 0
    fun inProjected(x: MutableCollection<in String>): Int = 0
    fun starProjected(x: Comparable<*>): Int = 0
    fun anyOfStar(x: Collection<Any?>): Int = 0
    fun covariant(x: Collection<CharSequence>): Int = 0
    fun contravariant(x: Comparable<String>): Int = 0
    fun nested(x: List<Collection<CharSequence>>): Int = 0
    fun functionType(x: (String) -> CharSequence): Int = 0
    fun suspendFunctionType(x: suspend (String) -> CharSequence): Int = 0
    fun boundOfTypeParameter(x: Collection<CharSequence>): Int = 0
    fun outOfInvariant(x: ProjectedBox<out CharSequence>): Int = 0
    fun inOfInvariant(x: ProjectedBox<in String>): Int = 0
    fun nothingOfStar(x: Comparable<Nothing>): Int = 0
    fun pairOfTypeParameter(x: Pair<CharSequence, CharSequence>): Int = 0
    fun receiverFunctionType(x: String.() -> CharSequence): Int = 0
}

fun ProjectedShadowedSupertype.outProjected(x: List<out String>): Int = 1

fun ProjectedShadowedSupertype.inProjected(x: MutableList<in CharSequence>): Int = 1

fun ProjectedShadowedSupertype.starProjected(x: ProjectedComparable<String>): Int = 1

fun ProjectedShadowedSupertype.anyOfStar(x: List<*>): Int = 1

fun ProjectedShadowedSupertype.covariant(x: List<String>): Int = 1

fun ProjectedShadowedSupertype.contravariant(x: Comparable<CharSequence>): Int = 1

fun ProjectedShadowedSupertype.nested(x: List<List<String>>): Int = 1

fun ProjectedShadowedSupertype.functionType(x: (CharSequence) -> String): Int = 1

fun ProjectedShadowedSupertype.suspendFunctionType(x: suspend (CharSequence) -> String): Int = 1

fun <T : String> ProjectedShadowedSupertype.boundOfTypeParameter(x: List<T>): Int = 1

fun ProjectedShadowedSupertype.outOfInvariant(x: ProjectedBox<String>): Int = 1

fun ProjectedShadowedSupertype.inOfInvariant(x: ProjectedBox<Any>): Int = 1

fun ProjectedShadowedSupertype.nothingOfStar(x: Comparable<*>): Int = 1

fun <T : CharSequence> ProjectedShadowedSupertype.pairOfTypeParameter(x: Pair<T, T>): Int = 1

fun ProjectedShadowedSupertype.receiverFunctionType(x: CharSequence.() -> String): Int = 1

// and the ones whose type arguments do not accept them, so that the extension is still called,
// like projectedUnshadowing.invariant(box), of a ProjectedBox<String>, which is no ProjectedBox<CharSequence>
interface ProjectedUnshadowedSupertype

class ProjectedUnshadowing : ProjectedUnshadowedSupertype {
    fun invariant(x: ProjectedBox<CharSequence>): Int = 0
    fun nullableArgument(x: Collection<CharSequence>): Int = 0
    fun notNullOfStar(x: Collection<Any>): Int = 0
    fun inOfStar(x: MutableCollection<in String>): Int = 0
    fun inOfOut(x: ProjectedBox<in String>): Int = 0
    fun outOfStar(x: ProjectedBox<out CharSequence>): Int = 0
    fun array(x: Array<CharSequence>): Int = 0
    fun contravariantSubtype(x: Comparable<CharSequence>): Int = 0
    fun functionTypeParameter(x: (CharSequence) -> CharSequence): Int = 0
    fun notSuspendFunctionType(x: () -> CharSequence): Int = 0
    fun contravariantTypeParameter(x: Comparable<String>): Int = 0
}

fun ProjectedUnshadowedSupertype.invariant(x: ProjectedBox<String>): Int = 1

fun ProjectedUnshadowedSupertype.nullableArgument(x: List<String?>): Int = 1

fun ProjectedUnshadowedSupertype.notNullOfStar(x: List<*>): Int = 1

fun ProjectedUnshadowedSupertype.inOfStar(x: MutableList<*>): Int = 1

fun ProjectedUnshadowedSupertype.inOfOut(x: ProjectedBox<out CharSequence>): Int = 1

fun ProjectedUnshadowedSupertype.outOfStar(x: ProjectedBox<*>): Int = 1

fun ProjectedUnshadowedSupertype.array(x: Array<String>): Int = 1

fun ProjectedUnshadowedSupertype.contravariantSubtype(x: Comparable<String>): Int = 1

fun ProjectedUnshadowedSupertype.functionTypeParameter(x: (String) -> String): Int = 1

fun ProjectedUnshadowedSupertype.notSuspendFunctionType(x: suspend () -> String): Int = 1

fun <T : CharSequence> ProjectedUnshadowedSupertype.contravariantTypeParameter(x: Comparable<T>): Int = 1

// members taking a function type with a receiver, or with a context parameter, next to the extensions of the same name
// taking a function type without them, also of the same parameter types, which Kotlin calls for a lambda naming its parameters,
// like functionShapeUnshadowing.receiver { a -> "" }, which a lambda with a receiver cannot
interface FunctionShapeSupertype

class FunctionShapeUnshadowing : FunctionShapeSupertype {
    fun receiver(x: String.() -> CharSequence): Int = 0
    fun context(x: context(String) () -> CharSequence): Int = 0
    fun receiverAndParameter(x: Int.(String) -> CharSequence): Int = 0
    fun contextAndParameter(x: context(String) (Int) -> CharSequence): Int = 0
    fun sameReceiver(x: String.() -> Unit): Int = 0
}

fun FunctionShapeSupertype.receiver(x: (CharSequence) -> String): Int = 1

fun FunctionShapeSupertype.context(x: (String) -> String): Int = 1

fun FunctionShapeSupertype.receiverAndParameter(x: (Int, String) -> String): Int = 1

fun FunctionShapeSupertype.contextAndParameter(x: (String, Int) -> String): Int = 1

fun FunctionShapeSupertype.sameReceiver(x: (String) -> Unit): Int = 1

// member properties of a Java type with an invoke method, which Kotlin calls as an operator, invoked instead of the extension
// functions of their names, like javaInvokedShadowing.task(), also through an inherited invoke,
// unlike javaInvokedShadowing.taskOf(x = 1), which calls the extension, as Kotlin passes no named argument to a Java method
interface JavaInvokedSupertype

class JavaInvokedShadowing : JavaInvokedSupertype {
    val task: JavaInvokedTask = JavaInvokedTask()
    val inheritedTask: JavaInvokedInheritedTask = JavaInvokedInheritedTask()
    val taskOf: JavaInvokedTaskOf = JavaInvokedTaskOf()
}

fun JavaInvokedSupertype.task(): Int = 1

fun JavaInvokedSupertype.inheritedTask(): Int = 1

fun JavaInvokedSupertype.taskOf(x: Int): Int = 1

// the getters of a Java supertype, read as synthetic properties instead of the extension properties of their names,
// like syntheticShadowing.title, also when overridden in Kotlin, like syntheticShadowing.overridden,
// unlike a Kotlin function named like a getter, a static, or a protected, Java method, or a getter of another property,
// like isActive() of syntheticShadowing.active, while nullableSyntheticShadowing.nullableTitle reads the extension
interface SyntheticShadowedSupertype

open class SyntheticShadowing : JavaSyntheticShadowingBase(), SyntheticShadowedSupertype {
    override fun getOverridden(): String = ""
    fun getKotlinTitle(): String = ""
}

val SyntheticShadowedSupertype.title: String get() = "extension"

val SyntheticShadowedSupertype.isActive: Boolean get() = false

var SyntheticShadowedSupertype.name: String
    get() = "extension"
    set(value) {}

val SyntheticShadowedSupertype.overridden: String get() = "extension"

val <T : SyntheticShadowedSupertype> T.bounded: String get() = "extension"

context(text: String) val SyntheticShadowedSupertype.contextual: String get() = text

val SyntheticShadowedSupertype.active: Boolean get() = false

val SyntheticShadowedSupertype?.nullableTitle: String get() = "extension"

val SyntheticShadowedSupertype.staticTitle: String get() = "extension"

val SyntheticShadowedSupertype.protectedTitle: String get() = "extension"

val SyntheticShadowedSupertype.kotlinTitle: String get() = "extension"

// and of an object, like SyntheticShadowingObject.title, or of a companion object, like SyntheticShadowingCompanionHolder.title
object SyntheticShadowingObject : JavaSyntheticShadowingBase(), SyntheticShadowedSupertype

class SyntheticShadowingCompanionHolder {
    companion object : JavaSyntheticShadowingBase(), SyntheticShadowedSupertype
}

val SyntheticShadowingCompanionHolder.Companion.title: String get() = "extension"

val SyntheticShadowingCompanionHolder.Companion.protectedTitle: String get() = "extension"

// extensions on the supertypes of a companion object, the more specific one called on the name of the class,
// like SpecificCompanionHolder.rank of val SpecificReceiver.rank, also instead of one on a nullable receiver,
// like SpecificCompanionHolder.nullableRank of val SpecificReceiver.nullableRank, as the name of a class is never null,
// also when the class itself is of the general one, whose instance reads the other one,
// like GeneralWithSpecificCompanionHolder().rank of var GeneralReceiver.rank,
// or the other way round, like SpecificWithGeneralCompanionHolder.rank of var GeneralReceiver.rank
class SpecificCompanionHolder {
    companion object : SpecificReceiver()
}

class GeneralWithSpecificCompanionHolder : GeneralReceiver {
    companion object : SpecificReceiver()
}

class SpecificWithGeneralCompanionHolder : SpecificReceiver() {
    companion object : GeneralReceiver
}

// and of an object, like SpecificObject.nullableRank of val SpecificReceiver.nullableRank
object SpecificObject : SpecificReceiver()

// an infix extension on the supertype of a companion object, next to its member which is not infix, called only
// as InfixCompanionHolder inf 1, as InfixCompanionHolder.inf(1) calls the member, and one called both ways
interface InfixCompanionSupertype

class InfixCompanionHolder {
    companion object : InfixCompanionSupertype {
        fun inf(other: Int): Int = 0
    }
}

infix fun InfixCompanionSupertype.inf(other: Int): Int = 1

infix fun InfixCompanionSupertype.bothWays(other: Int): Int = 1
