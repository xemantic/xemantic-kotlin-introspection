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

import com.xemantic.kotlin.introspection.test.other.JavaInvokedGenericTask
import com.xemantic.kotlin.introspection.test.other.JavaInvokedGetterBase
import com.xemantic.kotlin.introspection.test.other.JavaInvokedInheritedTask
import com.xemantic.kotlin.introspection.test.other.JavaInvokedKotlinSubTask
import com.xemantic.kotlin.introspection.test.other.JavaInvokedTask
import com.xemantic.kotlin.introspection.test.other.JavaInvokedTaskOf
import com.xemantic.kotlin.introspection.test.other.JavaInvokedVarargTask
import com.xemantic.kotlin.introspection.test.other.JavaSyntheticShadowingBase
import kotlin.reflect.KCallable
import kotlin.reflect.KFunction1

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
    fun listOfReceiverFunctionType(x: List<String.() -> Unit>): Int = 0
    fun listOfFunctionType(x: List<(String) -> Unit>): Int = 0
    fun collectionOfReceiverFunctionType(x: Collection<String.() -> CharSequence>): Int = 0
    fun returningReceiverFunctionType(x: () -> (String.() -> Unit)): Int = 0
    fun takingReceiverFunctionType(x: (String.() -> Unit) -> Unit): Int = 0
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

// a function type with a receiver as a type argument takes the lambdas of the one without it,
// like projectedShadowing.listOfReceiverFunctionType(listOf({ a -> })), unlike as the type of the parameter itself
fun ProjectedShadowedSupertype.listOfReceiverFunctionType(x: List<(String) -> Unit>): Int = 1

fun ProjectedShadowedSupertype.listOfFunctionType(x: List<String.() -> Unit>): Int = 1

fun ProjectedShadowedSupertype.collectionOfReceiverFunctionType(x: List<(CharSequence) -> String>): Int = 1

fun ProjectedShadowedSupertype.returningReceiverFunctionType(x: () -> ((String) -> Unit)): Int = 1

fun ProjectedShadowedSupertype.takingReceiverFunctionType(x: ((String) -> Unit) -> Unit): Int = 1

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

// a more specific extension taking a function type with a receiver as a type argument, called instead of the one
// taking the function type without it, like shapeSpecific.describeAll(listOf({ a -> })) of the one on ShapeSpecificReceiver
interface ShapeGeneralReceiver

class ShapeSpecificReceiver : ShapeGeneralReceiver

fun ShapeGeneralReceiver.describeAll(x: List<(String) -> Unit>): Int = 0

fun ShapeSpecificReceiver.describeAll(x: List<String.() -> Unit>): Int = 1

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

// the members which a companion object inherits, called on the name of its class too, like InheritingCompanionHolder.inherited(),
// next to the ones it declares, and next to an extension on its supertype, called for other arguments
open class InheritingCompanionBase {
    fun inherited(): Int = 0
    val inheritedProperty: Int = 0
    fun overloaded(x: Int): Int = 0
}

class InheritingCompanionHolder {
    companion object : InheritingCompanionBase() {
        fun overloaded(): Int = 0
    }
}

fun InheritingCompanionBase.overloaded(x: String): Int = 1

// members accepting Nothing, a subtype of every type, also where a contravariant type argument is expected,
// called instead of the extensions of their names, like nothingShadowing.sink(sinkOfString),
// unlike the ones taking a nullable Nothing where the type is not nullable, or an invariant one
interface NothingShadowedSupertype

interface NothingSink<in T>

class NothingShadowing : NothingShadowedSupertype {
    fun sink(x: NothingSink<Nothing>): Int = 0
    fun comparable(x: Comparable<Nothing>): Int = 0
    fun comparableOfTypeParameter(x: Comparable<Nothing>): Int = 0
    fun nested(x: Comparable<List<Nothing>>): Int = 0
    fun returningNothing(x: (String) -> Unit): Int = 0
    fun pair(x: Pair<CharSequence, Comparable<Nothing>>): Int = 0
    fun listOfNothing(x: List<String>): Int = 0
    fun nothing(x: String): Int = 0
    fun nullableNothing(x: String?): Int = 0
    fun sinkOfNullableNothing(x: NothingSink<Nothing?>): Int = 0
    fun invariantNothing(x: ProjectedBox<Nothing>): Int = 0
    fun onlyNull(x: String): Int = 0
}

fun NothingShadowedSupertype.sink(x: NothingSink<String>): Int = 1

fun NothingShadowedSupertype.comparable(x: String): Int = 1

fun <T> NothingShadowedSupertype.comparableOfTypeParameter(x: Comparable<T>): Int = 1

fun NothingShadowedSupertype.nested(x: ProjectedComparable<String>): Int = 1

fun NothingShadowedSupertype.returningNothing(x: (Any?) -> Nothing): Int = 1

fun <T : CharSequence> NothingShadowedSupertype.pair(x: Pair<T, Comparable<T>>): Int = 1

fun NothingShadowedSupertype.listOfNothing(x: List<Nothing>): Int = 1

fun NothingShadowedSupertype.nothing(x: Nothing): Int = 1

fun NothingShadowedSupertype.nullableNothing(x: Nothing?): Int = 1

fun NothingShadowedSupertype.sinkOfNullableNothing(x: NothingSink<String>): Int = 1

fun NothingShadowedSupertype.invariantNothing(x: ProjectedBox<String>): Int = 1

fun NothingShadowedSupertype.onlyNull(x: Nothing?): Int = 1

// members accepting what a star projection of a bounded type parameter is at least, called instead of the extensions,
// like boundedStarShadowing.source(sourceOfAnything), unlike the ones accepting less
interface BoundedStarShadowedSupertype

interface BoundedSource<out T : Any>

interface BoundedBox<T : CharSequence>

class BoundedStarShadowing : BoundedStarShadowedSupertype {
    fun source(x: BoundedSource<Any>): Int = 0
    fun box(x: BoundedBox<out CharSequence>): Int = 0
    fun inProjectedBox(x: BoundedBox<out CharSequence>): Int = 0
    fun narrowerSource(x: BoundedSource<CharSequence>): Int = 0
    fun narrowerBox(x: BoundedBox<out String>): Int = 0
}

fun BoundedStarShadowedSupertype.source(x: BoundedSource<*>): Int = 1

fun BoundedStarShadowedSupertype.box(x: BoundedBox<*>): Int = 1

fun BoundedStarShadowedSupertype.inProjectedBox(x: BoundedBox<in String>): Int = 1

fun BoundedStarShadowedSupertype.narrowerSource(x: BoundedSource<*>): Int = 1

fun BoundedStarShadowedSupertype.narrowerBox(x: BoundedBox<*>): Int = 1

// members taking a supertype of the function type, or of the function reference type, of the extension, called instead of it,
// like functionSupertypeShadowing.function { "" }, or functionSupertypeShadowing.reference(::describe),
// unlike one returning a narrower type, or a suspend function type, which takes no anonymous function,
// so that functionSupertypeShadowing.suspendConversion(fun(): String = "") calls the extension,
// and unlike a Function<Unit> next to a function type returning Unit, which a reference to a function returning
// anything is coerced to, so that functionSupertypeShadowing.unitFunction(::toString) calls the extension
interface FunctionSupertypeShadowedSupertype

class FunctionSupertypeShadowing : FunctionSupertypeShadowedSupertype {
    fun function(x: Function<CharSequence>): Int = 0
    fun starFunction(x: Function<*>): Int = 0
    fun receiverFunction(x: Function<Unit>): Int = 0
    fun reference(x: (String) -> CharSequence): Int = 0
    fun receiverReference(x: String.() -> Unit): Int = 0
    fun callable(x: KCallable<Unit>): Int = 0
    fun narrowerFunction(x: Function<String>): Int = 0
    fun otherReference(x: (Int) -> Unit): Int = 0
    fun suspendConversion(x: suspend () -> CharSequence): Int = 0
    fun unitFunction(x: Function<Unit>): Int = 0
    fun anyFunction(x: Function<Any?>): Int = 0
}

fun FunctionSupertypeShadowedSupertype.function(x: () -> String): Int = 1

fun FunctionSupertypeShadowedSupertype.starFunction(x: (Int) -> String): Int = 1

fun FunctionSupertypeShadowedSupertype.receiverFunction(x: String.() -> Unit): Int = 1

fun FunctionSupertypeShadowedSupertype.reference(x: KFunction1<CharSequence, String>): Int = 1

fun FunctionSupertypeShadowedSupertype.receiverReference(x: KFunction1<String, Unit>): Int = 1

fun FunctionSupertypeShadowedSupertype.callable(x: KFunction1<String, Unit>): Int = 1

fun FunctionSupertypeShadowedSupertype.narrowerFunction(x: () -> CharSequence): Int = 1

fun FunctionSupertypeShadowedSupertype.otherReference(x: KFunction1<String, Unit>): Int = 1

fun FunctionSupertypeShadowedSupertype.suspendConversion(x: () -> String): Int = 1

fun FunctionSupertypeShadowedSupertype.unitFunction(x: () -> Unit): Int = 1

fun FunctionSupertypeShadowedSupertype.anyFunction(x: () -> Unit): Int = 1

// members accepting a type parameter of the extension bounded by another one, like its bounds, called instead of the extensions,
// like boundedTypeParameterShadowing.boundedByOther(listOf("")), unlike one accepting less
interface BoundedTypeParameterShadowedSupertype

class BoundedTypeParameterShadowing : BoundedTypeParameterShadowedSupertype {
    fun boundedByOther(x: Collection<CharSequence>): Int = 0
    fun boundedByTypeOfOther(x: Collection<Collection<CharSequence>>): Int = 0
    fun pairBoundedByTypeOfOther(x: Pair<Collection<CharSequence>, Collection<CharSequence>>): Int = 0
    fun wholeBoundedByOther(x: CharSequence): Int = 0
    fun narrower(x: Collection<String>): Int = 0
}

fun <T : CharSequence, U : T> BoundedTypeParameterShadowedSupertype.boundedByOther(x: List<U>): Int = 1

fun <T : String, L : List<T>> BoundedTypeParameterShadowedSupertype.boundedByTypeOfOther(x: List<L>): Int = 1

fun <T : String, L : List<T>> BoundedTypeParameterShadowedSupertype.pairBoundedByTypeOfOther(x: Pair<L, L>): Int = 1

fun <T : CharSequence, U : T> BoundedTypeParameterShadowedSupertype.wholeBoundedByOther(x: U): Int = 1

fun <T : CharSequence, U : T> BoundedTypeParameterShadowedSupertype.narrower(x: List<U>): Int = 1

// members with a type parameter of their own, inferred from a type argument of the argument of the extension,
// or bounded by a type accepting it, called instead of the extensions, like memberTypeParameterShadowing.covariant(listOf("")),
// unlike the ones whose type parameter cannot be inferred so: of a bound which the type argument violates,
// captured from a projection nested in a type argument, inferred from two types, bounded by another type parameter,
// or having no subtype in common with the type argument, and unlike the ones bounded by a type which a lambda,
// or a function reference, is converted to, but not to the type parameter: memberTypeParameterShadowing.boundedSam { }
// and memberTypeParameterShadowing.boundedFunction(::toString) call the extensions
interface MemberTypeParameterShadowedSupertype

class MemberTypeParameterShadowing : MemberTypeParameterShadowedSupertype {
    fun <U> covariant(x: Collection<U>): Int = 0
    fun <U : CharSequence> boundedCovariant(x: Collection<U>): Int = 0
    fun <U : CharSequence> bounded(x: U): Int = 0
    fun <U> inProjected(x: ProjectedBox<in U>): Int = 0
    fun <U> functionParameter(x: (U) -> Unit): Int = 0
    fun <U> invariant(x: ProjectedBox<U>): Int = 0
    fun <U> captured(x: ProjectedBox<U>): Int = 0
    fun <U : CharSequence> boundedContravariant(x: Comparable<U>): Int = 0
    fun <U> nullable(x: Collection<U?>): Int = 0
    fun <U : CharSequence> boundViolated(x: Collection<U>): Int = 0
    fun <U : Any> notNullBoundViolated(x: Collection<U>): Int = 0
    fun <U> nestedCaptured(x: Collection<ProjectedBox<U>>): Int = 0
    fun <U> twice(x: (U) -> U): Int = 0
    fun <U, V : U> boundedByOther(x: Map<U, V>): Int = 0
    fun <U : CharSequence> nothingInCommon(x: ProjectedBox<in U>): Int = 0
    fun <U> receiverFunction(x: U.() -> Unit): Int = 0
    fun <U : Runnable> boundedSam(x: U): Int = 0
    fun <U : () -> Unit> boundedFunction(x: U): Int = 0
}

fun MemberTypeParameterShadowedSupertype.covariant(x: List<String>): Int = 1

fun MemberTypeParameterShadowedSupertype.boundedCovariant(x: List<String>): Int = 1

fun MemberTypeParameterShadowedSupertype.bounded(x: String): Int = 1

fun MemberTypeParameterShadowedSupertype.inProjected(x: ProjectedBox<String>): Int = 1

fun MemberTypeParameterShadowedSupertype.functionParameter(x: (String) -> Unit): Int = 1

fun MemberTypeParameterShadowedSupertype.invariant(x: ProjectedBox<String>): Int = 1

fun MemberTypeParameterShadowedSupertype.captured(x: ProjectedBox<*>): Int = 1

fun MemberTypeParameterShadowedSupertype.boundedContravariant(x: Comparable<Any>): Int = 1

fun MemberTypeParameterShadowedSupertype.nullable(x: List<String>): Int = 1

fun MemberTypeParameterShadowedSupertype.boundViolated(x: List<Int>): Int = 1

fun MemberTypeParameterShadowedSupertype.notNullBoundViolated(x: List<String?>): Int = 1

fun MemberTypeParameterShadowedSupertype.nestedCaptured(x: List<ProjectedBox<*>>): Int = 1

fun MemberTypeParameterShadowedSupertype.twice(x: (String) -> Int): Int = 1

fun MemberTypeParameterShadowedSupertype.boundedByOther(x: HashMap<String, Int>): Int = 1

fun MemberTypeParameterShadowedSupertype.nothingInCommon(x: ProjectedBox<Int>): Int = 1

fun MemberTypeParameterShadowedSupertype.receiverFunction(x: (String) -> Unit): Int = 1

fun MemberTypeParameterShadowedSupertype.boundedSam(x: Runnable): Int = 1

fun MemberTypeParameterShadowedSupertype.boundedFunction(x: () -> Unit): Int = 1

// members of a generic class, whose type parameter stands for the type argument of the receiver of an extension on the class,
// also for a type parameter of the extension, or for a supertype of a contravariant projection, called instead of the extensions,
// like classTypeParameterShadowing.typeParameterReceiver(listOf("")), unlike the ones which the receiver tells too little of,
// and the ones taking a type which a lambda is converted to, but not to the type parameter of the class:
// on a ClassTypeParameterShadowing<in (String) -> Unit>, .inProjectedFunction { it.length } calls the extension,
// and so does .typeParameter { s -> } on a ClassTypeParameterShadowing<String.() -> Unit>
class ClassTypeParameterShadowing<T> {
    fun inProjectedReceiver(x: Collection<T>): Int = 0
    fun typeParameterReceiver(x: Collection<T>): Int = 0
    fun boundedTypeParameterReceiver(x: Collection<T>): Int = 0
    fun boundedByTypeParameterReceiver(x: Collection<T>): Int = 0
    fun contravariant(x: Comparable<T>): Int = 0
    fun contravariantAny(x: Comparable<T>): Int = 0
    fun invariant(x: ProjectedBox<T>): Int = 0
    fun nested(x: T): Int = 0
    fun outProjectedReceiver(x: Collection<T>): Int = 0
    fun contravariantInProjectedReceiver(x: Comparable<T>): Int = 0
    fun nullableTypeParameter(x: T): Int = 0
    fun starProjectedReceiver(x: Collection<T>): Int = 0
    fun inProjectedFunction(x: T): Int = 0
    fun typeParameter(x: T): Int = 0
    fun boundedTypeParameter(x: T): Int = 0
}

fun ClassTypeParameterShadowing<in String>.inProjectedReceiver(x: List<String>): Int = 1

fun <T> ClassTypeParameterShadowing<T>.typeParameterReceiver(x: List<T>): Int = 1

fun <T : CharSequence> ClassTypeParameterShadowing<T>.boundedTypeParameterReceiver(x: List<T>): Int = 1

fun <T, U : T> ClassTypeParameterShadowing<T>.boundedByTypeParameterReceiver(x: List<U>): Int = 1

fun <T> ClassTypeParameterShadowing<T>.contravariant(x: Comparable<T>): Int = 1

fun <T> ClassTypeParameterShadowing<T>.contravariantAny(x: Comparable<Any?>): Int = 1

fun <T> ClassTypeParameterShadowing<T>.invariant(x: ProjectedBox<T>): Int = 1

fun <T> ClassTypeParameterShadowing<List<T>>.nested(x: List<T>): Int = 1

fun <T> ClassTypeParameterShadowing<out T>.outProjectedReceiver(x: List<T>): Int = 1

fun ClassTypeParameterShadowing<in String>.contravariantInProjectedReceiver(x: Comparable<CharSequence>): Int = 1

fun <T> ClassTypeParameterShadowing<T>.nullableTypeParameter(x: T?): Int = 1

fun ClassTypeParameterShadowing<*>.starProjectedReceiver(x: List<String>): Int = 1

fun ClassTypeParameterShadowing<in (String) -> Unit>.inProjectedFunction(x: (String) -> Unit): Int = 1

fun <T> ClassTypeParameterShadowing<T>.typeParameter(x: T): Int = 1

fun <T : CharSequence> ClassTypeParameterShadowing<T>.boundedTypeParameter(x: T): Int = 1

// and of a class with two type parameters, each standing for its own type argument of the receiver
class TwoClassTypeParametersShadowing<T, U> {
    fun map(x: Map<T, U>): Int = 0
    fun sameTypeParameter(x: Map<T, U>): Int = 0
    fun swapped(x: Map<T, U>): Int = 0
}

fun <A, B> TwoClassTypeParametersShadowing<A, B>.map(x: Map<A, B>): Int = 1

fun <A> TwoClassTypeParametersShadowing<A, A>.sameTypeParameter(x: HashMap<A, A>): Int = 1

fun <A, B> TwoClassTypeParametersShadowing<B, A>.swapped(x: Map<A, B>): Int = 1

// members of a final class called instead of the extensions on a type parameter bounded by the class, which stands for it,
// like pinnedReceiverShadowing.pinned { }, unlike one bounded by the nullable class, inferred also as the nullable one
class PinnedReceiverShadowing {
    fun pinned(x: PinnedReceiverShadowing.() -> Unit): Int = 0
    fun box(x: ProjectedBox<PinnedReceiverShadowing>): Int = 0
    fun nullableBound(x: ProjectedBox<PinnedReceiverShadowing>): Int = 0
}

fun <T : PinnedReceiverShadowing> T.pinned(x: T.() -> Unit): Int = 1

fun <T : PinnedReceiverShadowing> T.box(x: ProjectedBox<T>): Int = 1

fun <T : PinnedReceiverShadowing?> T.nullableBound(x: ProjectedBox<T>): Int = 1

// and one of an open class, whose subclass the type parameter is inferred as, like openReceiverSubclass.openBox(boxOfSubclass)
open class OpenReceiverUnshadowing {
    fun openBox(x: ProjectedBox<OpenReceiverUnshadowing>): Int = 0
}

fun <T : OpenReceiverUnshadowing> T.openBox(x: ProjectedBox<T>): Int = 1

// members of a class with a covariant type parameter in a contravariant position, accepting the argument of the extension
// for any receiver, like covariantShadowing.contravariant(comparableToCharSequence), also on a CovariantShadowing<Nothing>,
// unlike in a covariant position, where covariantShadowingOfNothing.covariant(listOf("")) calls the extension
class CovariantShadowing<out T> {
    fun contravariant(x: Comparable<@UnsafeVariance T>): Int = 0
    fun functionParameter(x: (T) -> Unit): Int = 0
    fun covariant(x: Collection<@UnsafeVariance T>): Int = 0
}

fun CovariantShadowing<String>.contravariant(x: Comparable<CharSequence>): Int = 1

fun CovariantShadowing<CharSequence>.functionParameter(x: (Any) -> Unit): Int = 1

fun CovariantShadowing<String>.covariant(x: List<String>): Int = 1

// and one of a class with a contravariant type parameter in a contravariant position, where
// contravariantUnshadowingOfAny.contravariant(comparableToString) calls the extension
class ContravariantUnshadowing<in T> {
    fun contravariant(x: Comparable<@UnsafeVariance T>): Int = 0
    fun covariant(x: T): Int = 0
}

fun ContravariantUnshadowing<String>.contravariant(x: Comparable<String>): Int = 1

fun ContravariantUnshadowing<String>.covariant(x: String): Int = 1

// a member taking an inner class of a generic outer class, called instead of the extension only when its outer type
// accepts the one of the extension, like innerShadowing.sameOuter(inner), unlike innerShadowing.otherOuter(inner)
class ShadowingOuter<T> {
    inner class Inner
}

interface InnerShadowedSupertype

class InnerShadowing : InnerShadowedSupertype {
    fun sameOuter(x: ShadowingOuter<String>.Inner): Int = 0
    fun projectedOuter(x: ShadowingOuter<out CharSequence>.Inner): Int = 0
    fun otherOuter(x: ShadowingOuter<CharSequence>.Inner): Int = 0
    fun listOfOtherOuter(x: List<ShadowingOuter<CharSequence>.Inner>): Int = 0
}

fun InnerShadowedSupertype.sameOuter(x: ShadowingOuter<String>.Inner): Int = 1

fun InnerShadowedSupertype.projectedOuter(x: ShadowingOuter<String>.Inner): Int = 1

fun InnerShadowedSupertype.otherOuter(x: ShadowingOuter<String>.Inner): Int = 1

fun InnerShadowedSupertype.listOfOtherOuter(x: List<ShadowingOuter<String>.Inner>): Int = 1

// a more specific extension taking an inner class of another outer type, which does not shadow the general one
interface InnerGeneralReceiver

class InnerSpecificReceiver : InnerGeneralReceiver

fun InnerGeneralReceiver.inner(x: ShadowingOuter<String>.Inner): Int = 0

fun InnerSpecificReceiver.inner(x: ShadowingOuter<CharSequence>.Inner): Int = 1

// member properties of the Java types whose invoke Kotlin calls instead of the extension functions of their names:
// taking only a vararg, like javaInvokeShapesShadowing.varargTask(), of a generic type, like genericTask(),
// of a Java getter read as a synthetic property, like got(), or run(), of a function type,
// or inherited from a Kotlin class, taking the named arguments, like kotlinSubTask(x = 1),
// and of a generic Kotlin type, whose type argument its invoke takes, like genericKotlinTask(1),
// unlike gotOf(x = 1), as Kotlin passes no named argument to a Java method
open class KotlinInvokedOpenTask {
    operator fun invoke(x: Int): Int = 0
}

class KotlinInvokedGenericTask<T> {
    operator fun invoke(x: T): Int = 0
}

interface JavaInvokeShapesShadowedSupertype

class JavaInvokeShapesShadowing : JavaInvokedGetterBase(), JavaInvokeShapesShadowedSupertype {
    val varargTask: JavaInvokedVarargTask = JavaInvokedVarargTask()
    val genericTask: JavaInvokedGenericTask<String> = JavaInvokedGenericTask()
    val kotlinSubTask: JavaInvokedKotlinSubTask = JavaInvokedKotlinSubTask()
    val genericKotlinTask: KotlinInvokedGenericTask<Int> = KotlinInvokedGenericTask()
    val otherGenericKotlinTask: KotlinInvokedGenericTask<String> = KotlinInvokedGenericTask()
}

fun JavaInvokeShapesShadowedSupertype.varargTask(): Int = 1

fun JavaInvokeShapesShadowedSupertype.genericTask(): Int = 1

fun JavaInvokeShapesShadowedSupertype.got(): Int = 1

fun JavaInvokeShapesShadowedSupertype.run(): Int = 1

fun JavaInvokeShapesShadowedSupertype.kotlinSubTask(x: Int): Int = 1

fun JavaInvokeShapesShadowedSupertype.genericKotlinTask(x: Int): Int = 1

fun JavaInvokeShapesShadowedSupertype.otherGenericKotlinTask(x: Int): Int = 1

fun JavaInvokeShapesShadowedSupertype.gotOf(x: Int): Int = 1

// members taking more parameters than the extension of their name, all of them optional, called instead of it,
// like optionalParametersShadowing.defaulted(), or optionalParametersShadowing.trailingDefault(x = 1),
// unlike one with a required parameter, a vararg which takes no named argument, like variadicNamed(x = 1),
// and one whose last parameter takes the trailing lambda instead, like trailingLambda { }
interface OptionalParametersShadowedSupertype

class OptionalParametersShadowing : OptionalParametersShadowedSupertype {
    fun defaulted(x: Int = 0): Int = 0
    fun variadic(vararg x: Int): Int = 0
    fun trailingDefault(x: Int, more: Int = 0): Int = 0
    fun required(x: Int, more: Int = 0): Int = 0
    fun variadicNamed(vararg x: Int): Int = 0
    fun trailingLambda(x: () -> Unit, more: Int = 0): Int = 0
    fun trailingAny(x: Any, more: Int = 0): Int = 0
}

fun OptionalParametersShadowedSupertype.defaulted(): Int = 1

fun OptionalParametersShadowedSupertype.variadic(): Int = 1

fun OptionalParametersShadowedSupertype.trailingDefault(x: Int): Int = 1

fun OptionalParametersShadowedSupertype.required(): Int = 1

fun OptionalParametersShadowedSupertype.variadicNamed(x: Int): Int = 1

fun OptionalParametersShadowedSupertype.trailingLambda(x: () -> Unit): Int = 1

fun OptionalParametersShadowedSupertype.trailingAny(x: Any): Int = 1

// extensions on a nullable supertype, never called next to the more specific ones on a nullable receiver,
// like nullableSpecificShadowing.bothNullable, also on a nullable value, unlike next to the ones which are not nullable,
// like nullableValue.onlyGeneralNullable, also of an object, whose value can be null too
open class NullableGeneralReceiver

open class NullableSpecificReceiver : NullableGeneralReceiver()

class NullableSpecificShadowing : NullableSpecificReceiver()

object NullableSpecificObject : NullableSpecificReceiver() {
    val member: Int = 0
}

class NullableSpecificCompanionHolder {
    companion object : NullableSpecificReceiver()
}

val NullableSpecificReceiver?.bothNullable: Int get() = 1

val NullableGeneralReceiver?.bothNullable: Int get() = 0

val NullableSpecificReceiver?.boundedNullable: Int get() = 1

val <T : NullableGeneralReceiver?> T.boundedNullable: Int get() = 0

val NullableSpecificReceiver.onlyGeneralNullable: Int get() = 1

val NullableGeneralReceiver?.onlyGeneralNullable: Int get() = 0

val NullableSpecificReceiver.same: Int get() = 1

val NullableSpecificReceiver?.same: Int @JvmName("getNullableSame") get() = 0

val NullableSpecificReceiver.boundedGeneralNullable: Int get() = 1

val <T : NullableGeneralReceiver?> T.boundedGeneralNullable: Int get() = 0

val NullableGeneralReceiver?.member: Int get() = 1

fun NullableSpecificReceiver?.describeNullable(x: Int): Int = 1

fun NullableGeneralReceiver?.describeNullable(x: Int): Int = 0

// infix extensions on the supertype of a companion object, called only as InfixMembersCompanionHolder invoked 1,
// or optional 1, next to its members called for InfixMembersCompanionHolder.invoked(1), or optional(other = 1):
// a property of a type with an operator invoke, and a function taking one more parameter, which is optional,
// unlike the ones next to a vararg, or a function type, called also as InfixMembersCompanionHolder.variadic(other = 1)
class InfixInvokedTask {
    operator fun invoke(other: Int): Int = 0
}

class InfixMembersCompanionHolder {
    companion object : InfixCompanionSupertype {
        val invoked: InfixInvokedTask = InfixInvokedTask()
        fun optional(other: Int, more: Int = 0): Int = 0
        fun variadic(vararg other: Int): Int = 0
        val functionTyped: (Int) -> Int = { it }
    }
}

infix fun InfixCompanionSupertype.invoked(other: Int): Int = 1

infix fun InfixCompanionSupertype.optional(other: Int): Int = 1

infix fun InfixCompanionSupertype.variadic(other: Int): Int = 1

infix fun InfixCompanionSupertype.functionTyped(other: Int): Int = 1

// operator extensions on the supertype of a companion object, called only as OperatorCompanionHolder + 1,
// OperatorCompanionHolder[1], 1 in OperatorCompanionHolder, -OperatorCompanionHolder, or OperatorCompanionHolder(1),
// next to its members which are no operators, called for OperatorCompanionHolder.plus(1),
// and one called both ways, like OperatorCompanionHolder - 1, or OperatorCompanionHolder.minus(1)
interface OperatorCompanionSupertype

class OperatorCompanionHolder {
    companion object : OperatorCompanionSupertype {
        fun plus(other: Int): Int = 0
        fun get(index: Int): Int = 0
        fun contains(other: Int): Boolean = false
        fun unaryMinus(): Int = 0
        fun invoke(other: Int): Int = 0
        fun rangeTo(other: Int): Int = 0
        fun inc(): OperatorCompanionSupertype = this
    }
}

// and one whose invoke the constructor is called instead of, like OperatorConstructedCompanionHolder(1)
class OperatorConstructedCompanionHolder(val value: Int) {
    companion object : OperatorCompanionSupertype {
        fun invoke(other: Int): Int = 0
    }
}

operator fun OperatorCompanionSupertype.plus(other: Int): Int = 1

operator fun OperatorCompanionSupertype.minus(other: Int): Int = 1

operator fun OperatorCompanionSupertype.get(index: Int): Int = 1

operator fun OperatorCompanionSupertype.contains(other: Int): Boolean = true

operator fun OperatorCompanionSupertype.unaryMinus(): Int = 1

operator fun OperatorCompanionSupertype.invoke(other: Int): Int = 1

operator fun OperatorCompanionSupertype.rangeTo(other: Int): Int = 1

operator fun OperatorCompanionSupertype.inc(): OperatorCompanionSupertype = this

// infix operator extensions on the supertype of a companion object, next to its members of their names:
// next to an operator which is not infix, also an inherited one, only InfixOperatorNextToOperatorHolder plus 1 calls the extension,
// as both InfixOperatorNextToOperatorHolder + 1 and InfixOperatorNextToOperatorHolder.plus(1) call the member,
// while next to an infix one which is no operator, or to one which is neither, InfixOperatorNextToInfixHolder + 1 calls it
interface InfixOperatorCompanionSupertype

open class InfixOperatorBase {
    operator fun plus(other: Int): Int = 0
}

class InfixOperatorNextToOperatorHolder {
    companion object : InfixOperatorCompanionSupertype {
        operator fun plus(other: Int): Int = 0
        operator fun get(index: Int): Int = 0
        operator fun invoke(other: Int): Int = 0
    }
}

class InfixOperatorNextToInheritedOperatorHolder {
    companion object : InfixOperatorBase(), InfixOperatorCompanionSupertype
}

class InfixOperatorNextToInfixHolder {
    companion object : InfixOperatorCompanionSupertype {
        infix fun plus(other: Int): Int = 0
    }
}

class InfixOperatorNextToNeitherHolder {
    companion object : InfixOperatorCompanionSupertype {
        fun plus(other: Int): Int = 0
    }
}

infix operator fun InfixOperatorCompanionSupertype.plus(other: Int): Int = 1

infix operator fun InfixOperatorCompanionSupertype.get(index: Int): Int = 1

infix operator fun InfixOperatorCompanionSupertype.invoke(other: Int): Int = 1
