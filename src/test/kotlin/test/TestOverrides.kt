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

import com.xemantic.kotlin.introspection.test.other.PackagePrivateBase

// An overload of an open function of the superclass is not an override
open class OverloadedBase {
    open fun foo() {}
}

class OverloadingSubclass : OverloadedBase() {
    fun foo(x: Int) {}
}

// Final methods of java.lang.Object are not overridable,
// so functions sharing their names are not overrides
open class NonAnyBase

class ObjectMethodNamesSubclass : NonAnyBase() {
    fun notify(msg: String) {}
    fun wait(ms: Int) {}
}

// Protected members can be overridden as well
abstract class ProtectedAbstractBase {
    protected abstract fun bar()
}

class ProtectedOverrideSubclass : ProtectedAbstractBase() {
    override fun bar() {}
}

// An override of a generic function, next to its overload
interface Processor<T> {
    fun process(item: T)
}

abstract class StringProcessor : Processor<String> {
    override fun process(item: String) {}
    fun process(item: Int) {}
}

class IntProcessor : Processor<Int> {
    override fun process(item: Int) {}
    fun process(item: String) {}
}

// Overrides of functions declared further up the hierarchy
open class IntermediateProcessor : StringProcessor()

class DeepProcessor : IntermediateProcessor() {
    override fun process(item: String) {}
    override fun toString(): String = "DeepProcessor"
}

// Java collection members declaring Object parameters, seen in Kotlin as taking the element type
abstract class StringList : AbstractList<String>() {
    override fun contains(element: String): Boolean = false
    override fun indexOf(element: String): Int = -1
}

abstract class IntList : AbstractList<Int>() {
    override fun contains(element: Int): Boolean = false
}

abstract class StringKeyMap : AbstractMap<String, Int>() {
    override fun get(key: String): Int? = null
    override fun containsKey(key: String): Boolean = false
}

// Functions taking value class parameters have mangled JVM names
class UIntProcessor : Processor<UInt> {
    override fun process(item: UInt) {}
}

open class UIntBase {
    open fun take(x: UInt) {}
}

class UIntSubclass : UIntBase() {
    override fun take(x: UInt) {}
    fun take(x: Int) {}
}

@JvmInline
value class ValueComparable(val x: Int) : Comparable<ValueComparable> {
    override fun compareTo(other: ValueComparable): Int = 0
}

class ValueComparableHolder : Comparable<ValueComparable> {
    override fun compareTo(other: ValueComparable): Int = 0
}

// A type argument nested in another type
abstract class ArrayProcessor<U> : Processor<Array<U>>

class StringArrayProcessor : ArrayProcessor<String>() {
    override fun process(item: Array<String>) {}
}

// Overridable members of java.lang.Object, which are not members of Any
class Finalizable {
    protected fun finalize() {}
}

class CloneableCopy : Cloneable {
    public override fun clone(): Any = this
}

// A package-private Java method is not visible, therefore not overridable, from another package
class PackagePrivateSubclass : PackagePrivateBase() {
    fun hidden() {}
}

// Suspend functions take a trailing Continuation on the JVM
interface SuspendProcessor<T> {
    suspend fun process(item: T)
}

class UIntSuspendProcessor : SuspendProcessor<UInt> {
    override suspend fun process(item: UInt) {}
}

// Receivers precede the value parameters on the JVM
interface ReceiverProcessor<T> {
    suspend fun Int.handle(x: T)
    fun T.describe(): String
}

class UIntReceiverProcessor : ReceiverProcessor<UInt> {
    override suspend fun Int.handle(x: UInt) {}
    override fun UInt.describe(): String = ""
}

// A function name containing a dash, which is not a mangling suffix
class DashedNameProcessor : Processor<String> {
    override fun process(item: String) {}
    fun `process-item`(item: String) {}
}

// The getter of an isXxx property is named isXxx, not getIsXxx
interface Toggle {
    val isEnabled: Boolean
}

class EnabledToggle : Toggle {
    override val isEnabled: Boolean = true
}

class StandaloneToggle {
    val isEnabled: Boolean = true
}

// A getter renamed with @get:JvmName does not override a supertype function named like the default getter
open class FooGetterBase {
    open fun getFoo(): Int = 0
}

class RenamedFooGetter : FooGetterBase() {
    @get:JvmName("fooValue")
    val foo: Int = 0
}

// A @JvmField property has no getter, so it does not override a supertype function named like one
open class GetBarBase {
    open fun getBar(): Int = 0
}

class JvmFieldBar : GetBarBase() {
    @JvmField
    val bar: Int = 0
}

// The getter of a member extension property takes the receiver as a parameter
interface StringMeasure {
    val String.measure: Int
}

class StringLengthMeasure : StringMeasure {
    override val String.measure: Int get() = length
}

// A value class receiver of a member extension property overriding a generic one
interface GenericReceiverMeasure<T> {
    val T.measure: Int
}

class UIntReceiverMeasure : GenericReceiverMeasure<UInt> {
    override val UInt.measure: Int get() = 0
}

// The getter of a property of value class type has a mangled JVM name
interface UIntValueHolder {
    val value: UInt
}

class UIntValue : UIntValueHolder {
    override val value: UInt = 0u
}

// Mapped Java collection members, like Collection.size being size() on the JVM
abstract class SizedCollection : java.util.AbstractCollection<String>() {
    override val size: Int get() = 0
}
