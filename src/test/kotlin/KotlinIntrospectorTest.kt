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


package com.xemantic.kotlin.introspection

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.reflect.Method
import kotlin.reflect.full.allSupertypes
import kotlin.test.Test
import kotlin.test.assertFailsWith

// we keep this dependency statically initialized, so that classpath scanning happens only once
// given
val resolver = KotlinIntrospector()

/**
 * Skips a test assuming that the Java [method] has no parameter names,
 * like the methods of the JDK, unless it is built with the `-parameters` option.
 */
internal fun assumeNoParameterNames(method: Method) {
    assumeFalse(method.parameters.any { it.isNamePresent }, "$method has parameter names")
}

class KotlinIntrospectorTest {

    @Test
    fun `should resolve String as known stdlib class`() {
        resolver.resolve(
            "kotlin.String"
        ) sameAsKotlin """
            // kotlin.String [page 1/42]
            class String : Comparable<String>, CharSequence {
                inline fun byteInputStream(charset: java.nio.charset.Charset = …): java.io.ByteArrayInputStream
                @Deprecated("Use replaceFirstChar instead.", ReplaceWith("replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }")) fun capitalize(): String
                @Deprecated("Use replaceFirstChar instead.", ReplaceWith("replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }")) fun capitalize(locale: java.util.Locale): String
                override fun chars(): java.util.stream.IntStream
                inline fun codePointAt(index: Int): Int
                inline fun codePointBefore(index: Int): Int
                inline fun codePointCount(beginIndex: Int, endIndex: Int): Int
                override fun codePoints(): java.util.stream.IntStream
                override operator fun compareTo(other: String): Int
                fun compareTo(other: String, ignoreCase: Boolean = …): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve String as known stdlib class - page 2`() {
        // verify key structure elements
        resolver.resolve(
            "kotlin.String",
            page = 2
        ) sameAsKotlin """
            // kotlin.String [page 2/42]
            class String : Comparable<String>, CharSequence {
                inline fun contentEquals(charSequence: CharSequence): Boolean
                inline fun contentEquals(stringBuilder: java.lang.StringBuffer): Boolean
                @Deprecated("Use replaceFirstChar instead.", ReplaceWith("replaceFirstChar { it.lowercase(Locale.getDefault()) }")) fun decapitalize(): String
                @Deprecated("Use replaceFirstChar instead.", ReplaceWith("replaceFirstChar { it.lowercase(locale) }")) fun decapitalize(locale: java.util.Locale): String
                fun drop(n: Int): String
                fun dropLast(n: Int): String
                inline fun dropLastWhile(predicate: (Char) -> Boolean): String
                inline fun dropWhile(predicate: (Char) -> Boolean): String
                fun encodeToByteArray(): ByteArray
                fun encodeToByteArray(startIndex: Int = …, endIndex: Int = …, throwOnInvalidSequence: Boolean = …): ByteArray
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve String as known stdlib class - page 3`() {
        // verify key structure elements
        resolver.resolve(
            "kotlin.String",
            page = 3
        ) sameAsKotlin """
            // kotlin.String [page 3/42]
            class String : Comparable<String>, CharSequence {
                fun endsWith(suffix: String, ignoreCase: Boolean = …): Boolean
                override operator fun equals(other: Any?): Boolean
                inline fun filter(predicate: (Char) -> Boolean): String
                inline fun filterIndexed(predicate: (index: Int, Char) -> Boolean): String
                inline fun filterNot(predicate: (Char) -> Boolean): String
                inline fun format(vararg args: Any?): String
                inline fun format(locale: java.util.Locale?, vararg args: Any?): String
                override operator fun get(index: Int): Char
                fun hexToByte(format: HexFormat = …): Byte
                fun hexToByteArray(format: HexFormat = …): ByteArray
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve String as known stdlib class - page 17 (the last page of the class body)`() {
        // page 17 shows the last class members and the companion object, followed by the first extensions
        resolver.resolve(
            "kotlin.String",
            page = 17
        ) sameAsKotlin """
            // kotlin.String [page 17/42]
            class String : Comparable<String>, CharSequence {
                fun trimIndent(): String
                fun trimMargin(marginPrefix: String = …): String
                inline fun trimStart(predicate: (Char) -> Boolean): String
                fun trimStart(vararg chars: Char): String
                inline fun trimStart(): String
                inline fun uppercase(): String
                inline fun uppercase(locale: java.util.Locale): String
                companion object
            }
            // from kotlin
            inline infix fun <T> Comparable<T>.compareTo(other: T): Int
            // from kotlin.ranges
            fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T
        """.trimIndent()
    }

    @Test
    fun `should resolve String as known stdlib class - the last page`() {
        // extension-only pages omit the empty class body
        resolver.resolve(
            "kotlin.String",
            page = 42
        ) sameAsKotlin """
            // kotlin.String [page 42/42]
            // from kotlin.text
            inline fun <R> CharSequence.zipWithNext(transform: (a: Char, b: Char) -> R): List<R>
            // from kotlinx.serialization.builtins
            fun String.Companion.serializer(): kotlinx.serialization.KSerializer<String>
        """.trimIndent()
    }

    @Test
    fun `should resolve String with extension functions from kotlin stdlib - huge page size`() {
        resolver.resolve(
            "kotlin.String",
            pageSize = 10000
        ) sameAsKotlin """
            // kotlin.String [page 1/1]
            class String : Comparable<String>, CharSequence {
                inline fun byteInputStream(charset: java.nio.charset.Charset = …): java.io.ByteArrayInputStream
                @Deprecated("Use replaceFirstChar instead.", ReplaceWith("replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }")) fun capitalize(): String
                @Deprecated("Use replaceFirstChar instead.", ReplaceWith("replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }")) fun capitalize(locale: java.util.Locale): String
                override fun chars(): java.util.stream.IntStream
                inline fun codePointAt(index: Int): Int
                inline fun codePointBefore(index: Int): Int
                inline fun codePointCount(beginIndex: Int, endIndex: Int): Int
                override fun codePoints(): java.util.stream.IntStream
                override operator fun compareTo(other: String): Int
                fun compareTo(other: String, ignoreCase: Boolean = …): Int
                inline fun contentEquals(charSequence: CharSequence): Boolean
                inline fun contentEquals(stringBuilder: java.lang.StringBuffer): Boolean
                @Deprecated("Use replaceFirstChar instead.", ReplaceWith("replaceFirstChar { it.lowercase(Locale.getDefault()) }")) fun decapitalize(): String
                @Deprecated("Use replaceFirstChar instead.", ReplaceWith("replaceFirstChar { it.lowercase(locale) }")) fun decapitalize(locale: java.util.Locale): String
                fun drop(n: Int): String
                fun dropLast(n: Int): String
                inline fun dropLastWhile(predicate: (Char) -> Boolean): String
                inline fun dropWhile(predicate: (Char) -> Boolean): String
                fun encodeToByteArray(): ByteArray
                fun encodeToByteArray(startIndex: Int = …, endIndex: Int = …, throwOnInvalidSequence: Boolean = …): ByteArray
                fun endsWith(suffix: String, ignoreCase: Boolean = …): Boolean
                override operator fun equals(other: Any?): Boolean
                inline fun filter(predicate: (Char) -> Boolean): String
                inline fun filterIndexed(predicate: (index: Int, Char) -> Boolean): String
                inline fun filterNot(predicate: (Char) -> Boolean): String
                inline fun format(vararg args: Any?): String
                inline fun format(locale: java.util.Locale?, vararg args: Any?): String
                override operator fun get(index: Int): Char
                fun hexToByte(format: HexFormat = …): Byte
                fun hexToByteArray(format: HexFormat = …): ByteArray
                fun hexToInt(format: HexFormat = …): Int
                fun hexToLong(format: HexFormat = …): Long
                fun hexToShort(format: HexFormat = …): Short
                inline fun hexToUByte(format: HexFormat = …): UByte
                inline fun hexToUByteArray(format: HexFormat = …): UByteArray
                inline fun hexToUInt(format: HexFormat = …): UInt
                inline fun hexToULong(format: HexFormat = …): ULong
                inline fun hexToUShort(format: HexFormat = …): UShort
                inline fun intern(): String
                override val length: Int
                inline fun lowercase(): String
                inline fun lowercase(locale: java.util.Locale): String
                inline fun offsetByCodePoints(index: Int, codePointOffset: Int): Int
                fun padEnd(length: Int, padChar: Char = …): String
                fun padStart(length: Int, padChar: Char = …): String
                inline fun partition(predicate: (Char) -> Boolean): Pair<String, String>
                operator fun plus(other: Any?): String
                fun prependIndent(indent: String = …): String
                inline fun reader(): java.io.StringReader
                fun regionMatches(thisOffset: Int, other: String, otherOffset: Int, length: Int, ignoreCase: Boolean = …): Boolean
                fun removePrefix(prefix: CharSequence): String
                inline fun removeRange(startIndex: Int, endIndex: Int): String
                inline fun removeRange(range: IntRange): String
                fun removeSuffix(suffix: CharSequence): String
                fun removeSurrounding(prefix: CharSequence, suffix: CharSequence): String
                fun removeSurrounding(delimiter: CharSequence): String
                fun replace(oldChar: Char, newChar: Char, ignoreCase: Boolean = …): String
                fun replace(oldValue: String, newValue: String, ignoreCase: Boolean = …): String
                fun replaceAfter(delimiter: Char, replacement: String, missingDelimiterValue: String = …): String
                fun replaceAfter(delimiter: String, replacement: String, missingDelimiterValue: String = …): String
                fun replaceAfterLast(delimiter: String, replacement: String, missingDelimiterValue: String = …): String
                fun replaceAfterLast(delimiter: Char, replacement: String, missingDelimiterValue: String = …): String
                fun replaceBefore(delimiter: Char, replacement: String, missingDelimiterValue: String = …): String
                fun replaceBefore(delimiter: String, replacement: String, missingDelimiterValue: String = …): String
                fun replaceBeforeLast(delimiter: Char, replacement: String, missingDelimiterValue: String = …): String
                fun replaceBeforeLast(delimiter: String, replacement: String, missingDelimiterValue: String = …): String
                fun replaceFirst(oldChar: Char, newChar: Char, ignoreCase: Boolean = …): String
                fun replaceFirst(oldValue: String, newValue: String, ignoreCase: Boolean = …): String
                inline fun replaceFirstChar(transform: (Char) -> Char): String
                inline fun replaceFirstChar(transform: (Char) -> CharSequence): String
                fun replaceIndent(newIndent: String = …): String
                fun replaceIndentByMargin(newIndent: String = …, marginPrefix: String = …): String
                inline fun replaceRange(startIndex: Int, endIndex: Int, replacement: CharSequence): String
                inline fun replaceRange(range: IntRange, replacement: CharSequence): String
                inline fun reversed(): String
                fun slice(indices: IntRange): String
                inline fun slice(indices: Iterable<Int>): String
                fun startsWith(prefix: String, ignoreCase: Boolean = …): Boolean
                fun startsWith(prefix: String, startIndex: Int, ignoreCase: Boolean = …): Boolean
                override fun subSequence(startIndex: Int, endIndex: Int): CharSequence
                inline fun substring(startIndex: Int): String
                inline fun substring(startIndex: Int, endIndex: Int): String
                fun substring(range: IntRange): String
                fun substringAfter(delimiter: Char, missingDelimiterValue: String = …): String
                fun substringAfter(delimiter: String, missingDelimiterValue: String = …): String
                fun substringAfterLast(delimiter: Char, missingDelimiterValue: String = …): String
                fun substringAfterLast(delimiter: String, missingDelimiterValue: String = …): String
                fun substringBefore(delimiter: Char, missingDelimiterValue: String = …): String
                fun substringBefore(delimiter: String, missingDelimiterValue: String = …): String
                fun substringBeforeLast(delimiter: Char, missingDelimiterValue: String = …): String
                fun substringBeforeLast(delimiter: String, missingDelimiterValue: String = …): String
                fun take(n: Int): String
                fun takeLast(n: Int): String
                inline fun takeLastWhile(predicate: (Char) -> Boolean): String
                inline fun takeWhile(predicate: (Char) -> Boolean): String
                inline fun toBigDecimal(): java.math.BigDecimal
                inline fun toBigDecimal(mathContext: java.math.MathContext): java.math.BigDecimal
                fun toBigDecimalOrNull(): java.math.BigDecimal?
                fun toBigDecimalOrNull(mathContext: java.math.MathContext): java.math.BigDecimal?
                inline fun toBigInteger(): java.math.BigInteger
                inline fun toBigInteger(radix: Int): java.math.BigInteger
                fun toBigIntegerOrNull(): java.math.BigInteger?
                fun toBigIntegerOrNull(radix: Int): java.math.BigInteger?
                fun toBooleanStrict(): Boolean
                fun toBooleanStrictOrNull(): Boolean?
                inline fun toByte(): Byte
                inline fun toByte(radix: Int): Byte
                inline fun toByteArray(charset: java.nio.charset.Charset = …): ByteArray
                fun toByteOrNull(): Byte?
                fun toByteOrNull(radix: Int): Byte?
                fun toCharArray(startIndex: Int = …, endIndex: Int = …): CharArray
                inline fun toCharArray(): CharArray
                inline fun toCharArray(destination: CharArray, destinationOffset: Int = …, startIndex: Int = …, endIndex: Int = …): CharArray
                inline fun toDouble(): Double
                fun toDoubleOrNull(): Double?
                inline fun toFloat(): Float
                fun toFloatOrNull(): Float?
                inline fun toInt(): Int
                inline fun toInt(radix: Int): Int
                fun toIntOrNull(): Int?
                fun toIntOrNull(radix: Int): Int?
                inline fun toLong(): Long
                inline fun toLong(radix: Int): Long
                fun toLongOrNull(): Long?
                fun toLongOrNull(radix: Int): Long?
                @Deprecated("Use lowercase() instead.", ReplaceWith("lowercase(Locale.getDefault())"), level = DeprecationLevel.ERROR) inline fun toLowerCase(): String
                @Deprecated("Use lowercase() instead.", ReplaceWith("lowercase(locale)"), level = DeprecationLevel.ERROR) inline fun toLowerCase(locale: java.util.Locale): String
                inline fun toPattern(flags: Int = …): java.util.regex.Pattern
                inline fun toRegex(): Regex
                inline fun toRegex(option: RegexOption): Regex
                inline fun toRegex(options: Set<RegexOption>): Regex
                inline fun toShort(): Short
                inline fun toShort(radix: Int): Short
                fun toShortOrNull(): Short?
                fun toShortOrNull(radix: Int): Short?
                override fun toString(): String
                fun toUByte(): UByte
                fun toUByte(radix: Int): UByte
                fun toUByteOrNull(): UByte?
                fun toUByteOrNull(radix: Int): UByte?
                fun toUInt(): UInt
                fun toUInt(radix: Int): UInt
                fun toUIntOrNull(): UInt?
                fun toUIntOrNull(radix: Int): UInt?
                fun toULong(): ULong
                fun toULong(radix: Int): ULong
                fun toULongOrNull(): ULong?
                fun toULongOrNull(radix: Int): ULong?
                fun toUShort(): UShort
                fun toUShort(radix: Int): UShort
                fun toUShortOrNull(): UShort?
                fun toUShortOrNull(radix: Int): UShort?
                @Deprecated("Use uppercase() instead.", ReplaceWith("uppercase(Locale.getDefault())"), level = DeprecationLevel.ERROR) inline fun toUpperCase(): String
                @Deprecated("Use uppercase() instead.", ReplaceWith("uppercase(locale)"), level = DeprecationLevel.ERROR) inline fun toUpperCase(locale: java.util.Locale): String
                inline fun trim(predicate: (Char) -> Boolean): String
                fun trim(vararg chars: Char): String
                inline fun trim(): String
                inline fun trimEnd(predicate: (Char) -> Boolean): String
                fun trimEnd(vararg chars: Char): String
                inline fun trimEnd(): String
                fun trimIndent(): String
                fun trimMargin(marginPrefix: String = …): String
                inline fun trimStart(predicate: (Char) -> Boolean): String
                fun trimStart(vararg chars: Char): String
                inline fun trimStart(): String
                inline fun uppercase(): String
                inline fun uppercase(locale: java.util.Locale): String
                companion object
            }
            // from kotlin
            inline infix fun <T> Comparable<T>.compareTo(other: T): Int
            // from kotlin.ranges
            fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T
            fun <T : Comparable<T>> T.coerceAtMost(maximumValue: T): T
            fun <T : Comparable<T>> T.coerceIn(minimumValue: T?, maximumValue: T?): T
            fun <T : Comparable<T>> T.coerceIn(range: ClosedFloatingPointRange<T>): T
            fun <T : Comparable<T>> T.coerceIn(range: ClosedRange<T>): T
            operator fun <T : Comparable<T>> T.rangeTo(that: T): ClosedRange<T>
            operator fun <T : Comparable<T>> T.rangeUntil(that: T): OpenEndRange<T>
            // from kotlin.text
            val String.Companion.CASE_INSENSITIVE_ORDER: java.util.Comparator<String>
            val CharSequence.indices: IntRange
            val CharSequence.lastIndex: Int
            inline fun CharSequence.all(predicate: (Char) -> Boolean): Boolean
            fun CharSequence.any(): Boolean
            inline fun CharSequence.any(predicate: (Char) -> Boolean): Boolean
            fun CharSequence.asIterable(): Iterable<Char>
            fun CharSequence.asSequence(): Sequence<Char>
            inline fun <K, V> CharSequence.associate(transform: (Char) -> Pair<K, V>): Map<K, V>
            inline fun <K> CharSequence.associateBy(keySelector: (Char) -> K): Map<K, Char>
            inline fun <K, V> CharSequence.associateBy(keySelector: (Char) -> K, valueTransform: (Char) -> V): Map<K, V>
            inline fun <K, M : MutableMap<in K, in Char>> CharSequence.associateByTo(destination: M, keySelector: (Char) -> K): M
            inline fun <K, V, M : MutableMap<in K, in V>> CharSequence.associateByTo(destination: M, keySelector: (Char) -> K, valueTransform: (Char) -> V): M
            inline fun <K, V, M : MutableMap<in K, in V>> CharSequence.associateTo(destination: M, transform: (Char) -> Pair<K, V>): M
            inline fun <V> CharSequence.associateWith(valueSelector: (Char) -> V): Map<Char, V>
            inline fun <V, M : MutableMap<in Char, in V>> CharSequence.associateWithTo(destination: M, valueSelector: (Char) -> V): M
            fun CharSequence.chunked(size: Int): List<String>
            fun <R> CharSequence.chunked(size: Int, transform: (CharSequence) -> R): List<R>
            fun CharSequence.chunkedSequence(size: Int): Sequence<String>
            fun <R> CharSequence.chunkedSequence(size: Int, transform: (CharSequence) -> R): Sequence<R>
            fun CharSequence.commonPrefixWith(other: CharSequence, ignoreCase: Boolean = …): String
            fun CharSequence.commonSuffixWith(other: CharSequence, ignoreCase: Boolean = …): String
            operator fun CharSequence.contains(other: CharSequence, ignoreCase: Boolean = …): Boolean
            operator fun CharSequence.contains(char: Char, ignoreCase: Boolean = …): Boolean
            inline operator fun CharSequence.contains(regex: Regex): Boolean
            infix fun CharSequence?.contentEquals(other: CharSequence?): Boolean
            fun CharSequence?.contentEquals(other: CharSequence?, ignoreCase: Boolean): Boolean
            inline fun CharSequence.count(): Int
            inline fun CharSequence.count(predicate: (Char) -> Boolean): Int
            fun CharSequence.drop(n: Int): CharSequence
            fun CharSequence.dropLast(n: Int): CharSequence
            inline fun CharSequence.dropLastWhile(predicate: (Char) -> Boolean): CharSequence
            inline fun CharSequence.dropWhile(predicate: (Char) -> Boolean): CharSequence
            inline fun CharSequence.elementAt(index: Int): Char
            inline fun CharSequence.elementAtOrElse(index: Int, defaultValue: (Int) -> Char): Char
            inline fun CharSequence.elementAtOrNull(index: Int): Char?
            fun CharSequence.endsWith(char: Char, ignoreCase: Boolean = …): Boolean
            fun CharSequence.endsWith(suffix: CharSequence, ignoreCase: Boolean = …): Boolean
            fun String?.equals(other: String?, ignoreCase: Boolean = …): Boolean
            inline fun CharSequence.filter(predicate: (Char) -> Boolean): CharSequence
            inline fun CharSequence.filterIndexed(predicate: (index: Int, Char) -> Boolean): CharSequence
            inline fun <C : java.lang.Appendable> CharSequence.filterIndexedTo(destination: C, predicate: (index: Int, Char) -> Boolean): C
            inline fun CharSequence.filterNot(predicate: (Char) -> Boolean): CharSequence
            inline fun <C : java.lang.Appendable> CharSequence.filterNotTo(destination: C, predicate: (Char) -> Boolean): C
            inline fun <C : java.lang.Appendable> CharSequence.filterTo(destination: C, predicate: (Char) -> Boolean): C
            inline fun CharSequence.find(predicate: (Char) -> Boolean): Char?
            fun CharSequence.findAnyOf(strings: Collection<String>, startIndex: Int = …, ignoreCase: Boolean = …): Pair<Int, String>?
            inline fun CharSequence.findLast(predicate: (Char) -> Boolean): Char?
            fun CharSequence.findLastAnyOf(strings: Collection<String>, startIndex: Int = …, ignoreCase: Boolean = …): Pair<Int, String>?
            fun CharSequence.first(): Char
            inline fun CharSequence.first(predicate: (Char) -> Boolean): Char
            inline fun <R : Any> CharSequence.firstNotNullOf(transform: (Char) -> R?): R
            inline fun <R : Any> CharSequence.firstNotNullOfOrNull(transform: (Char) -> R?): R?
            fun CharSequence.firstOrNull(): Char?
            inline fun CharSequence.firstOrNull(predicate: (Char) -> Boolean): Char?
            inline fun <R> CharSequence.flatMap(transform: (Char) -> Iterable<R>): List<R>
            inline fun <R> CharSequence.flatMapIndexed(transform: (index: Int, Char) -> Iterable<R>): List<R>
            inline fun <R, C : MutableCollection<in R>> CharSequence.flatMapIndexedTo(destination: C, transform: (index: Int, Char) -> Iterable<R>): C
            inline fun <R, C : MutableCollection<in R>> CharSequence.flatMapTo(destination: C, transform: (Char) -> Iterable<R>): C
            inline fun <R> CharSequence.fold(initial: R, operation: (acc: R, Char) -> R): R
            inline fun <R> CharSequence.foldIndexed(initial: R, operation: (index: Int, acc: R, Char) -> R): R
            inline fun <R> CharSequence.foldRight(initial: R, operation: (Char, acc: R) -> R): R
            inline fun <R> CharSequence.foldRightIndexed(initial: R, operation: (index: Int, Char, acc: R) -> R): R
            inline fun CharSequence.forEach(action: (Char) -> Unit)
            inline fun CharSequence.forEachIndexed(action: (index: Int, Char) -> Unit)
            inline fun String.Companion.format(format: String, vararg args: Any?): String
            inline fun String.Companion.format(locale: java.util.Locale?, format: String, vararg args: Any?): String
            inline fun CharSequence.getOrElse(index: Int, defaultValue: (Int) -> Char): Char
            fun CharSequence.getOrNull(index: Int): Char?
            inline fun <K> CharSequence.groupBy(keySelector: (Char) -> K): Map<K, List<Char>>
            inline fun <K, V> CharSequence.groupBy(keySelector: (Char) -> K, valueTransform: (Char) -> V): Map<K, List<V>>
            inline fun <K, M : MutableMap<in K, MutableList<Char>>> CharSequence.groupByTo(destination: M, keySelector: (Char) -> K): M
            inline fun <K, V, M : MutableMap<in K, MutableList<V>>> CharSequence.groupByTo(destination: M, keySelector: (Char) -> K, valueTransform: (Char) -> V): M
            inline fun <K> CharSequence.groupingBy(keySelector: (Char) -> K): Grouping<Char, K>
            fun CharSequence.hasSurrogatePairAt(index: Int): Boolean
            inline fun <C, R> C.ifBlank(defaultValue: () -> R): R where C : CharSequence, C : R
            inline fun <C, R> C.ifEmpty(defaultValue: () -> R): R where C : CharSequence, C : R
            fun CharSequence.indexOf(char: Char, startIndex: Int = …, ignoreCase: Boolean = …): Int
            fun CharSequence.indexOf(string: String, startIndex: Int = …, ignoreCase: Boolean = …): Int
            fun CharSequence.indexOfAny(chars: CharArray, startIndex: Int = …, ignoreCase: Boolean = …): Int
            fun CharSequence.indexOfAny(strings: Collection<String>, startIndex: Int = …, ignoreCase: Boolean = …): Int
            inline fun CharSequence.indexOfFirst(predicate: (Char) -> Boolean): Int
            inline fun CharSequence.indexOfLast(predicate: (Char) -> Boolean): Int
            fun CharSequence.isBlank(): Boolean
            inline fun CharSequence.isEmpty(): Boolean
            inline fun CharSequence.isNotBlank(): Boolean
            inline fun CharSequence.isNotEmpty(): Boolean
            inline fun CharSequence?.isNullOrBlank(): Boolean
            inline fun CharSequence?.isNullOrEmpty(): Boolean
            operator fun CharSequence.iterator(): CharIterator
            fun CharSequence.last(): Char
            inline fun CharSequence.last(predicate: (Char) -> Boolean): Char
            fun CharSequence.lastIndexOf(char: Char, startIndex: Int = …, ignoreCase: Boolean = …): Int
            fun CharSequence.lastIndexOf(string: String, startIndex: Int = …, ignoreCase: Boolean = …): Int
            fun CharSequence.lastIndexOfAny(chars: CharArray, startIndex: Int = …, ignoreCase: Boolean = …): Int
            fun CharSequence.lastIndexOfAny(strings: Collection<String>, startIndex: Int = …, ignoreCase: Boolean = …): Int
            fun CharSequence.lastOrNull(): Char?
            inline fun CharSequence.lastOrNull(predicate: (Char) -> Boolean): Char?
            fun CharSequence.lineSequence(): Sequence<String>
            fun CharSequence.lines(): List<String>
            inline fun <R> CharSequence.map(transform: (Char) -> R): List<R>
            inline fun <R> CharSequence.mapIndexed(transform: (index: Int, Char) -> R): List<R>
            inline fun <R : Any> CharSequence.mapIndexedNotNull(transform: (index: Int, Char) -> R?): List<R>
            inline fun <R : Any, C : MutableCollection<in R>> CharSequence.mapIndexedNotNullTo(destination: C, transform: (index: Int, Char) -> R?): C
            inline fun <R, C : MutableCollection<in R>> CharSequence.mapIndexedTo(destination: C, transform: (index: Int, Char) -> R): C
            inline fun <R : Any> CharSequence.mapNotNull(transform: (Char) -> R?): List<R>
            inline fun <R : Any, C : MutableCollection<in R>> CharSequence.mapNotNullTo(destination: C, transform: (Char) -> R?): C
            inline fun <R, C : MutableCollection<in R>> CharSequence.mapTo(destination: C, transform: (Char) -> R): C
            inline infix fun CharSequence.matches(regex: Regex): Boolean
            fun CharSequence.max(): Char
            inline fun <R : Comparable<R>> CharSequence.maxBy(selector: (Char) -> R): Char
            inline fun <R : Comparable<R>> CharSequence.maxByOrNull(selector: (Char) -> R): Char?
            inline fun CharSequence.maxOf(selector: (Char) -> Double): Double
            inline fun CharSequence.maxOf(selector: (Char) -> Float): Float
            inline fun <R : Comparable<R>> CharSequence.maxOf(selector: (Char) -> R): R
            inline fun CharSequence.maxOfOrNull(selector: (Char) -> Double): Double?
            inline fun CharSequence.maxOfOrNull(selector: (Char) -> Float): Float?
            inline fun <R : Comparable<R>> CharSequence.maxOfOrNull(selector: (Char) -> R): R?
            inline fun <R> CharSequence.maxOfWith(comparator: java.util.Comparator<in R>, selector: (Char) -> R): R
            inline fun <R> CharSequence.maxOfWithOrNull(comparator: java.util.Comparator<in R>, selector: (Char) -> R): R?
            fun CharSequence.maxOrNull(): Char?
            fun CharSequence.maxWith(comparator: java.util.Comparator<in Char>): Char
            fun CharSequence.maxWithOrNull(comparator: java.util.Comparator<in Char>): Char?
            fun CharSequence.min(): Char
            inline fun <R : Comparable<R>> CharSequence.minBy(selector: (Char) -> R): Char
            inline fun <R : Comparable<R>> CharSequence.minByOrNull(selector: (Char) -> R): Char?
            inline fun CharSequence.minOf(selector: (Char) -> Double): Double
            inline fun CharSequence.minOf(selector: (Char) -> Float): Float
            inline fun <R : Comparable<R>> CharSequence.minOf(selector: (Char) -> R): R
            inline fun CharSequence.minOfOrNull(selector: (Char) -> Double): Double?
            inline fun CharSequence.minOfOrNull(selector: (Char) -> Float): Float?
            inline fun <R : Comparable<R>> CharSequence.minOfOrNull(selector: (Char) -> R): R?
            inline fun <R> CharSequence.minOfWith(comparator: java.util.Comparator<in R>, selector: (Char) -> R): R
            inline fun <R> CharSequence.minOfWithOrNull(comparator: java.util.Comparator<in R>, selector: (Char) -> R): R?
            fun CharSequence.minOrNull(): Char?
            fun CharSequence.minWith(comparator: java.util.Comparator<in Char>): Char
            fun CharSequence.minWithOrNull(comparator: java.util.Comparator<in Char>): Char?
            fun CharSequence.none(): Boolean
            inline fun CharSequence.none(predicate: (Char) -> Boolean): Boolean
            inline fun <S : CharSequence> S.onEach(action: (Char) -> Unit): S
            inline fun <S : CharSequence> S.onEachIndexed(action: (index: Int, Char) -> Unit): S
            inline fun String?.orEmpty(): String
            fun CharSequence.padEnd(length: Int, padChar: Char = …): CharSequence
            fun CharSequence.padStart(length: Int, padChar: Char = …): CharSequence
            inline fun CharSequence.partition(predicate: (Char) -> Boolean): Pair<CharSequence, CharSequence>
            inline fun CharSequence.random(): Char
            fun CharSequence.random(random: Random): Char
            inline fun CharSequence.randomOrNull(): Char?
            fun CharSequence.randomOrNull(random: Random): Char?
            inline fun CharSequence.reduce(operation: (acc: Char, Char) -> Char): Char
            inline fun CharSequence.reduceIndexed(operation: (index: Int, acc: Char, Char) -> Char): Char
            inline fun CharSequence.reduceIndexedOrNull(operation: (index: Int, acc: Char, Char) -> Char): Char?
            inline fun CharSequence.reduceOrNull(operation: (acc: Char, Char) -> Char): Char?
            inline fun CharSequence.reduceRight(operation: (Char, acc: Char) -> Char): Char
            inline fun CharSequence.reduceRightIndexed(operation: (index: Int, Char, acc: Char) -> Char): Char
            inline fun CharSequence.reduceRightIndexedOrNull(operation: (index: Int, Char, acc: Char) -> Char): Char?
            inline fun CharSequence.reduceRightOrNull(operation: (Char, acc: Char) -> Char): Char?
            fun CharSequence.regionMatches(thisOffset: Int, other: CharSequence, otherOffset: Int, length: Int, ignoreCase: Boolean = …): Boolean
            fun CharSequence.removePrefix(prefix: CharSequence): CharSequence
            fun CharSequence.removeRange(startIndex: Int, endIndex: Int): CharSequence
            fun CharSequence.removeRange(range: IntRange): CharSequence
            fun CharSequence.removeSuffix(suffix: CharSequence): CharSequence
            fun CharSequence.removeSurrounding(prefix: CharSequence, suffix: CharSequence): CharSequence
            fun CharSequence.removeSurrounding(delimiter: CharSequence): CharSequence
            fun CharSequence.repeat(n: Int): String
            inline fun CharSequence.replace(regex: Regex, replacement: String): String
            inline fun CharSequence.replace(regex: Regex, transform: (MatchResult) -> CharSequence): String
            inline fun CharSequence.replaceFirst(regex: Regex, replacement: String): String
            fun CharSequence.replaceRange(startIndex: Int, endIndex: Int, replacement: CharSequence): CharSequence
            fun CharSequence.replaceRange(range: IntRange, replacement: CharSequence): CharSequence
            fun CharSequence.reversed(): CharSequence
            inline fun <R> CharSequence.runningFold(initial: R, operation: (acc: R, Char) -> R): List<R>
            inline fun <R> CharSequence.runningFoldIndexed(initial: R, operation: (index: Int, acc: R, Char) -> R): List<R>
            inline fun CharSequence.runningReduce(operation: (acc: Char, Char) -> Char): List<Char>
            inline fun CharSequence.runningReduceIndexed(operation: (index: Int, acc: Char, Char) -> Char): List<Char>
            inline fun <R> CharSequence.scan(initial: R, operation: (acc: R, Char) -> R): List<R>
            inline fun <R> CharSequence.scanIndexed(initial: R, operation: (index: Int, acc: R, Char) -> R): List<R>
            fun CharSequence.single(): Char
            inline fun CharSequence.single(predicate: (Char) -> Boolean): Char
            fun CharSequence.singleOrNull(): Char?
            inline fun CharSequence.singleOrNull(predicate: (Char) -> Boolean): Char?
            fun CharSequence.slice(indices: IntRange): CharSequence
            fun CharSequence.slice(indices: Iterable<Int>): CharSequence
            fun CharSequence.split(regex: java.util.regex.Pattern, limit: Int = …): List<String>
            fun CharSequence.split(vararg delimiters: String, ignoreCase: Boolean = …, limit: Int = …): List<String>
            fun CharSequence.split(vararg delimiters: Char, ignoreCase: Boolean = …, limit: Int = …): List<String>
            inline fun CharSequence.split(regex: Regex, limit: Int = …): List<String>
            fun CharSequence.splitToSequence(vararg delimiters: String, ignoreCase: Boolean = …, limit: Int = …): Sequence<String>
            fun CharSequence.splitToSequence(vararg delimiters: Char, ignoreCase: Boolean = …, limit: Int = …): Sequence<String>
            inline fun CharSequence.splitToSequence(regex: Regex, limit: Int = …): Sequence<String>
            fun CharSequence.startsWith(char: Char, ignoreCase: Boolean = …): Boolean
            fun CharSequence.startsWith(prefix: CharSequence, ignoreCase: Boolean = …): Boolean
            fun CharSequence.startsWith(prefix: CharSequence, startIndex: Int, ignoreCase: Boolean = …): Boolean
            @Deprecated("Use parameters named startIndex and endIndex.", ReplaceWith("subSequence(startIndex = start, endIndex = end)"), level = DeprecationLevel.ERROR) inline fun String.subSequence(start: Int, end: Int): CharSequence
            fun CharSequence.subSequence(range: IntRange): CharSequence
            inline fun CharSequence.substring(startIndex: Int, endIndex: Int = …): String
            fun CharSequence.substring(range: IntRange): String
            @Deprecated("Use sumOf instead.", ReplaceWith("this.sumOf(selector)")) inline fun CharSequence.sumBy(selector: (Char) -> Int): Int
            @Deprecated("Use sumOf instead.", ReplaceWith("this.sumOf(selector)")) inline fun CharSequence.sumByDouble(selector: (Char) -> Double): Double
            inline fun CharSequence.sumOf(selector: (Char) -> java.math.BigDecimal): java.math.BigDecimal
            inline fun CharSequence.sumOf(selector: (Char) -> java.math.BigInteger): java.math.BigInteger
            inline fun CharSequence.sumOf(selector: (Char) -> Double): Double
            inline fun CharSequence.sumOf(selector: (Char) -> Int): Int
            inline fun CharSequence.sumOf(selector: (Char) -> Long): Long
            inline fun CharSequence.sumOf(selector: (Char) -> UInt): UInt
            inline fun CharSequence.sumOf(selector: (Char) -> ULong): ULong
            fun CharSequence.take(n: Int): CharSequence
            fun CharSequence.takeLast(n: Int): CharSequence
            inline fun CharSequence.takeLastWhile(predicate: (Char) -> Boolean): CharSequence
            inline fun CharSequence.takeWhile(predicate: (Char) -> Boolean): CharSequence
            inline fun String?.toBoolean(): Boolean
            fun <C : MutableCollection<in Char>> CharSequence.toCollection(destination: C): C
            fun CharSequence.toHashSet(): java.util.HashSet<Char>
            fun CharSequence.toList(): List<Char>
            fun CharSequence.toMutableList(): MutableList<Char>
            fun CharSequence.toSet(): Set<Char>
            fun CharSequence.toSortedSet(): java.util.SortedSet<Char>
            inline fun CharSequence.trim(predicate: (Char) -> Boolean): CharSequence
            fun CharSequence.trim(vararg chars: Char): CharSequence
            fun CharSequence.trim(): CharSequence
            inline fun CharSequence.trimEnd(predicate: (Char) -> Boolean): CharSequence
            fun CharSequence.trimEnd(vararg chars: Char): CharSequence
            fun CharSequence.trimEnd(): CharSequence
            inline fun CharSequence.trimStart(predicate: (Char) -> Boolean): CharSequence
            fun CharSequence.trimStart(vararg chars: Char): CharSequence
            fun CharSequence.trimStart(): CharSequence
            fun CharSequence.windowed(size: Int, step: Int = …, partialWindows: Boolean = …): List<String>
            fun <R> CharSequence.windowed(size: Int, step: Int = …, partialWindows: Boolean = …, transform: (CharSequence) -> R): List<R>
            fun CharSequence.windowedSequence(size: Int, step: Int = …, partialWindows: Boolean = …): Sequence<String>
            fun <R> CharSequence.windowedSequence(size: Int, step: Int = …, partialWindows: Boolean = …, transform: (CharSequence) -> R): Sequence<R>
            fun CharSequence.withIndex(): Iterable<IndexedValue<Char>>
            infix fun CharSequence.zip(other: CharSequence): List<Pair<Char, Char>>
            inline fun <V> CharSequence.zip(other: CharSequence, transform: (a: Char, b: Char) -> V): List<V>
            fun CharSequence.zipWithNext(): List<Pair<Char, Char>>
            inline fun <R> CharSequence.zipWithNext(transform: (a: Char, b: Char) -> R): List<R>
            // from kotlinx.serialization.builtins
            fun String.Companion.serializer(): kotlinx.serialization.KSerializer<String>
        """.trimIndent()
    }


    // TestClass tests

    @Test
    fun `should resolve TestClass with extension functions from multiple packages`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestClass [page 1/1]
            class TestClass(val name: String) {
                fun greet(): String
            }
            // from com.xemantic.kotlin.introspection.test
            fun TestClass.farewell(): String
            // from com.xemantic.kotlin.introspection.test.ext
            val TestClass.uppercaseName: String
            fun TestClass.describe(): String
        """.trimIndent()
    }

    @Test
    fun `should resolve class with cross-package type references`() {
        // CrossPackageTypeTest references types from test.other package
        // types from different packages should be fully qualified
        // so that an LLM can look them up or import them correctly
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CrossPackageTypeTest"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CrossPackageTypeTest [page 1/1]
            class CrossPackageTypeTest(val processor: com.xemantic.kotlin.introspection.test.other.CustomProcessor) {
                fun process(input: String): com.xemantic.kotlin.introspection.test.other.CustomResult
                fun processWithProcessor(processor: com.xemantic.kotlin.introspection.test.other.CustomProcessor, input: String): com.xemantic.kotlin.introspection.test.other.CustomResult
            }
        """.trimIndent()
    }

    // TestClassTypes tests

    @Test
    fun `should resolve data class`() {
        // data classes include generated methods like componentN, copy, equals, hashCode, toString
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DataTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DataTestClass [page 1/1]
            data class DataTestClass(val id: Int, val name: String) {
                operator fun component1(): Int
                operator fun component2(): String
                fun copy(id: Int = …, name: String = …): DataTestClass
                override operator fun equals(other: Any?): Boolean
                override fun hashCode(): Int
                override fun toString(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve value class`() {
        // value classes include generated methods equals, hashCode, toString
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ValueTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ValueTestClass [page 1/1]
            @JvmInline value class ValueTestClass(val value: String) {
                override operator fun equals(other: Any?): Boolean
                override fun hashCode(): Int
                override fun toString(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should paginate value class`() {
        // the annotation is a part of the class declaration line, restored on every page
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ValueTestClass",
            page = 2,
            pageSize = 2
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ValueTestClass [page 2/2]
            @JvmInline value class ValueTestClass(val value: String) {
                override fun toString(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve fun interface`() {
        // an LLM needs to know that a lambda can be passed by SAM conversion
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SamHandler"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SamHandler [page 1/1]
            fun interface SamHandler {
                fun handle(x: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should list value class and fun interface with their modifiers`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ClassModifiersHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ClassModifiersHolder [page 1/1]
            sealed interface ClassModifiersHolder {
                @JvmInline value class Id : ClassModifiersHolder
                fun interface Callback
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve object (singleton)`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ObjectTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ObjectTestClass [page 1/1]
            object ObjectTestClass {
                val name: String
                fun greet(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve simple enum class`() {
        // an LLM needs to see enum entries to know what values are available
        // Note: Enum<T> supertype is filtered out as redundant - enum class already implies it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.EnumTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.EnumTestClass [page 1/1]
            enum class EnumTestClass {
                FIRST,
                SECOND,
                THIRD
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve rich enum class with constructor, properties and functions`() {
        // an LLM needs to see constructor params, entries, properties and functions
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.RichEnumTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.RichEnumTestClass [page 1/1]
            enum class RichEnumTestClass(val code: Int, val label: String) {
                ALPHA,
                BETA,
                GAMMA;
                val displayName: String
                fun isFirst(): Boolean
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve enum class with only a companion object`() {
        // the last entry needs a semicolon before the companion object, otherwise the rendered Kotlin is invalid
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionOnlyEnum"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionOnlyEnum [page 1/1]
            enum class CompanionOnlyEnum {
                RED,
                GREEN;
                companion object {
                    fun default(): CompanionOnlyEnum
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve enum class without entries but with a companion object`() {
        // without entries, the members still have to be preceded by a semicolon, otherwise the rendered Kotlin is invalid
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.EntrylessCompanionEnum"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.EntrylessCompanionEnum [page 1/1]
            enum class EntrylessCompanionEnum {
                ;
                companion object {
                    fun x(): Int
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve enum class without entries but with members`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.EntrylessEnum"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.EntrylessEnum [page 1/1]
            enum class EntrylessEnum {
                ;
                val a: Int
                fun f1()
                fun f2()
            }
        """.trimIndent()
    }

    @Test
    fun `should paginate members of enum class without entries - the first page`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.EntrylessEnum",
            pageSize = 2
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.EntrylessEnum [page 1/2]
            enum class EntrylessEnum {
                ;
                val a: Int
                fun f1()
            }
        """.trimIndent()
    }

    @Test
    fun `should paginate members of enum class without entries - the last page`() {
        // the semicolon is not an item of its own, but it precedes the members on every page
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.EntrylessEnum",
            page = 2,
            pageSize = 2
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.EntrylessEnum [page 2/2]
            enum class EntrylessEnum {
                ;
                fun f2()
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve enum class without entries but with a nested class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.EntrylessNestedEnum"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.EntrylessNestedEnum [page 1/1]
            enum class EntrylessNestedEnum {
                ;
                class Nested
            }
        """.trimIndent()
    }

    @Test
    fun `should paginate enum class without entries - the page with the members`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.EntrylessExtendedEnum",
            pageSize = 2
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.EntrylessExtendedEnum [page 1/2]
            enum class EntrylessExtendedEnum {
                ;
                fun f()
            }
            // from com.xemantic.kotlin.introspection.test
            fun EntrylessExtendedEnum.e1()
        """.trimIndent()
    }

    @Test
    fun `should paginate enum class without entries - the page with only extensions`() {
        // the semicolon belongs to the class body, which is not shown without members on the page
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.EntrylessExtendedEnum",
            page = 2,
            pageSize = 2
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.EntrylessExtendedEnum [page 2/2]
            // from com.xemantic.kotlin.introspection.test
            fun EntrylessExtendedEnum.e2()
        """.trimIndent()
    }

    @Test
    fun `should paginate enum entries named in backticks one by one`() {
        // the entries are separate items, whatever their names, which unescaped would look like the continuation of a multi-line member
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BacktickedEntryEnum",
            page = 2,
            pageSize = 1
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BacktickedEntryEnum [page 2/4]
            enum class BacktickedEntryEnum {
                ` SECOND`,
            }
        """.trimIndent()
    }

    @Test
    fun `should paginate the last enum entry named in backticks with the members`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BacktickedEntryEnum",
            page = 2,
            pageSize = 2
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BacktickedEntryEnum [page 2/2]
            enum class BacktickedEntryEnum {
                `}`;
                fun f()
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve annotation class`() {
        // an LLM needs to see annotation parameters to know how to use the annotation
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.AnnotationTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.AnnotationTestClass [page 1/1]
            annotation class AnnotationTestClass(val message: String = "")
        """.trimIndent()
    }

    @Test
    fun `should resolve annotation class without parameters`() {
        // the Annotation supertype is implicit, like the Enum one of an enum class
        resolver.resolve("kotlin.jvm.JvmStatic") sameAsKotlin """
            // kotlin.jvm.JvmStatic [page 1/1]
            annotation class JvmStatic
        """.trimIndent()
    }

    @Test
    fun `should resolve class with companion object`() {
        // an LLM needs to see companion object members to access static-like functionality,
        // but not the private ones
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionTestClass [page 1/1]
            class CompanionTestClass {
                companion object {
                    const val CONSTANT: String
                    fun create(): CompanionTestClass
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve companion object function as class member`() {
        // called as CompanionTestClass.create(), so looked up by the name of the outer class,
        // rendered within the companion object, like in the listing, not to be taken for an instance member
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionTestClass.create"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionTestClass.create [page 1/1]
            companion object {
                fun create(): CompanionTestClass
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve companion object property as class member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionTestClass.CONSTANT"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionTestClass.CONSTANT [page 1/1]
            companion object {
                const val CONSTANT: String
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve private companion object property as class member`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.CompanionTestClass.PRIVATE_CONSTANT") == null)
    }

    @Test
    fun `should resolve named companion object function as class member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NamedCompanionHolder.of"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NamedCompanionHolder.of [page 1/1]
            companion object Factory {
                fun of(): NamedCompanionHolder
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve companion object function of nested class as its member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NestedCompanionTestClass.Inner.create"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NestedCompanionTestClass.Inner.create [page 1/1]
            companion object {
                fun create(): NestedCompanionTestClass.Inner
            }
        """.trimIndent()
    }

    @Test
    fun `should list extensions of companion object with the extensions of the class`() {
        // called on the name of the class, like CompanionExtensionHolder.parse("…"), rendered with their receiver
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionExtensionHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionExtensionHolder [page 1/1]
            class CompanionExtensionHolder {
                companion object {
                    fun create(): CompanionExtensionHolder
                }
            }
            // from com.xemantic.kotlin.introspection.test
            val CompanionExtensionHolder.Companion.default: CompanionExtensionHolder
            fun CompanionExtensionHolder.describe(): String
            fun CompanionExtensionHolder.Companion.parse(text: String): CompanionExtensionHolder
        """.trimIndent()
    }

    @Test
    fun `should resolve extension of companion object as class member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionExtensionHolder.parse"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionExtensionHolder.parse [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun CompanionExtensionHolder.Companion.parse(text: String): CompanionExtensionHolder
        """.trimIndent()
    }

    @Test
    fun `should resolve extension of named companion object as class member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NamedCompanionExtensionHolder.of"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NamedCompanionExtensionHolder.of [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun NamedCompanionExtensionHolder.Factory.of(): NamedCompanionExtensionHolder
        """.trimIndent()
    }

    @Test
    fun `should not list extensions of hidden companion object with the extensions of the class`() {
        // Kotlin does not call HiddenCompanionExtensionHolder.of(), as the companion object is hidden
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenCompanionExtensionHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenCompanionExtensionHolder [page 1/1]
            class HiddenCompanionExtensionHolder
            // from com.xemantic.kotlin.introspection.test
            fun HiddenCompanionExtensionHolder.describe(): String
        """.trimIndent()
    }

    @Test
    fun `should not resolve extension of hidden companion object as class member`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenCompanionExtensionHolder.of") == null)
    }

    @Test
    fun `should resolve extension of companion object of built-in type as its member`() {
        // String.format("%d", 42), next to the extensions on the instances, like "%d".format(42)
        resolver.resolve("kotlin.String.format") sameAsKotlin """
            // kotlin.String.format [page 1/1]
            // from kotlin.text
            inline fun String.format(vararg args: Any?): String
            inline fun String.format(locale: java.util.Locale?, vararg args: Any?): String
            inline fun String.Companion.format(format: String, vararg args: Any?): String
            inline fun String.Companion.format(locale: java.util.Locale?, format: String, vararg args: Any?): String
        """.trimIndent()
    }

    @Test
    fun `should not resolve class nested in companion object through outer class name`() {
        // Kotlin does not resolve CompanionNestedClassesHolder.Builder, only CompanionNestedClassesHolder.Companion.Builder
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.CompanionNestedClassesHolder.Builder") == null)
    }

    @Test
    fun `should resolve class nested in companion object through companion object name`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionNestedClassesHolder.Companion.Factory"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionNestedClassesHolder.Companion.Factory [page 1/1]
            interface Factory
        """.trimIndent()
    }

    @Test
    fun `should paginate companion object as a single member`() {
        // 9 members and the companion object block make 10 items, fitting the default page size
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PaginatedCompanionClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PaginatedCompanionClass [page 1/1]
            class PaginatedCompanionClass {
                val a1: Int
                val a2: Int
                val a3: Int
                val a4: Int
                val a5: Int
                fun f6()
                fun f7()
                fun f8()
                fun f9()
                companion object {
                    const val CONSTANT: String
                    fun create(): PaginatedCompanionClass
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should not split companion object across pages - the page ending with it`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PaginatedCompanionClass",
            page = 2,
            pageSize = 5
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PaginatedCompanionClass [page 2/2]
            class PaginatedCompanionClass {
                fun f6()
                fun f7()
                fun f8()
                fun f9()
                companion object {
                    const val CONSTANT: String
                    fun create(): PaginatedCompanionClass
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should paginate members of companion object resolved directly`() {
        // the companion object is a single item of the outer class, but its members are paged one by one
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PaginatedCompanionClass.Companion",
            page = 2,
            pageSize = 1
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PaginatedCompanionClass.Companion [page 2/2]
            companion object {
                fun create(): PaginatedCompanionClass
            }
        """.trimIndent()
    }

    @Test
    fun `should separate enum members from entries on a page without entries`() {
        // without the leading semicolon, enum members would be parsed as enum entries
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.RichEnumTestClass",
            page = 3,
            pageSize = 2
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.RichEnumTestClass [page 3/3]
            enum class RichEnumTestClass(val code: Int, val label: String) {
                ;
                fun isFirst(): Boolean
            }
        """.trimIndent()
    }

    @Test
    fun `should not split companion object across pages - the page before it`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PaginatedCompanionClass",
            pageSize = 9
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PaginatedCompanionClass [page 1/2]
            class PaginatedCompanionClass {
                val a1: Int
                val a2: Int
                val a3: Int
                val a4: Int
                val a5: Int
                fun f6()
                fun f7()
                fun f8()
                fun f9()
            }
        """.trimIndent()
    }

    @Test
    fun `should not split companion object across pages - the page starting with it`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PaginatedCompanionClass",
            page = 2,
            pageSize = 9
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PaginatedCompanionClass [page 2/2]
            class PaginatedCompanionClass {
                companion object {
                    const val CONSTANT: String
                    fun create(): PaginatedCompanionClass
                }
            }
        """.trimIndent()
    }

    // TestValueParameters tests

    @Test
    fun `should resolve class with vararg and default constructor and function parameters`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ValueParametersSample"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ValueParametersSample [page 1/1]
            class ValueParametersSample(vararg val names: String, val count: Int = …, label: String = …) {
                fun join(vararg parts: Int, separator: String = …): String
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve top-level function with vararg and default parameters`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestValueParametersKt.valueParametersSample"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestValueParametersKt.valueParametersSample [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun <T> valueParametersSample(vararg elements: T, limit: Int = …): List<T>
        """.trimIndent()
    }

    @Test
    fun `should mark parameter of override inheriting default value`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SqlRepo"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SqlRepo [page 1/1]
            class SqlRepo : Repo {
                override fun find(limit: Int = …)
                fun find(limit: Long)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark parameter of interface override inheriting default value`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CachedRepo"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CachedRepo [page 1/1]
            interface CachedRepo : Repo {
                override fun find(limit: Int = …)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark parameter of override of override inheriting default value`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CachingSqlRepo"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CachingSqlRepo [page 1/1]
            class CachingSqlRepo : CachedRepo {
                override fun find(limit: Int = …)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark parameter of member extension override inheriting default value`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.IntFormatter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.IntFormatter [page 1/1]
            class IntFormatter : Formatter<Int> {
                override fun Int.format(width: Int = …, pad: Char): String
            }
        """.trimIndent()
    }

    @Test
    fun `should mark parameters of class function overrides inheriting default values`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.MemoryRepo"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.MemoryRepo [page 1/1]
            class MemoryRepo : AbstractRepo {
                override fun find(limit: Int = …)
                override fun count(from: Int = …, to: Int): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should mark parameter of member implemented by delegation inheriting default value`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DelegatingRepo"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DelegatingRepo [page 1/1]
            class DelegatingRepo(repo: Repo) : Repo {
                override fun find(limit: Int = …)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark parameter of override inheriting default value from one of its supertypes`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.FinderRepo"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.FinderRepo [page 1/1]
            class FinderRepo : Repo, Finder {
                override fun find(limit: Int = …)
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark parameter inheriting default value of overload not overridden through raw Java superclass`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.RawDefaultsLeaf"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.RawDefaultsLeaf [page 1/1]
            class RawDefaultsLeaf : com.xemantic.kotlin.introspection.test.other.JavaRawDefaultsMid {
                override fun f(x: String, y: Int)
                override fun f(x: Any?, y: Int = …)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark parameter of looked up override member inheriting default value`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SqlRepo.find"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SqlRepo.find [page 1/1]
            override fun find(limit: Int = …)
            fun find(limit: Long)
        """.trimIndent()
    }

    // TestFunctionModifiers tests

    @Test
    fun `should resolve class with function modifiers`() {
        // functions are output in declaration order
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.FunctionModifiersTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.FunctionModifiersTestClass [page 1/1]
            class FunctionModifiersTestClass {
                suspend fun fetchData(): String
                inline fun processData(block: () -> Unit)
                infix fun combine(other: String): String
                operator fun plus(other: FunctionModifiersTestClass): FunctionModifiersTestClass
                tailrec fun factorial(n: Int, acc: Int = …): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve suspend function types`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SuspendFunctionTypesTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SuspendFunctionTypesTestClass [page 1/1]
            class SuspendFunctionTypesTestClass {
                val handler: suspend (String) -> Unit
                fun launch(block: suspend () -> String)
                fun launchWithParameters(block: suspend (Int, String) -> Boolean)
                fun launchWithReceiver(block: suspend FunctionModifiersTestClass.() -> Unit)
                fun launchNullable(block: (suspend () -> Unit)?)
                fun supplier(): suspend () -> List<String>
                fun launchAll(blocks: List<suspend () -> Unit>)
                fun launchWithNullableReceiver(block: suspend (suspend () -> Unit)?.() -> Unit)
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve context parameters of suspend function types`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SuspendContextFunctionTypesTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SuspendContextFunctionTypesTestClass [page 1/1]
            class SuspendContextFunctionTypesTestClass {
                fun launchInContext(block: suspend context(FunctionModifiersTestClass) () -> Unit)
                fun launchInContexts(block: suspend context(FunctionModifiersTestClass, String) (Int) -> Boolean)
                fun launchInContextWithReceiver(block: suspend context(FunctionModifiersTestClass) String.(Int) -> Unit)
                fun launchInContextNullable(block: (suspend context(FunctionModifiersTestClass) () -> Unit)?)
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve extension function on suspend function type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestFunctionModifiersKt.retry"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestFunctionModifiersKt.retry [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun (suspend () -> Unit).retry()
        """.trimIndent()
    }

    @Test
    fun `should resolve extension property on suspend function type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestFunctionModifiersKt.retryCount"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestFunctionModifiersKt.retryCount [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            val (suspend () -> Unit).retryCount: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve function types in arrow notation`() {
        // KFunction1, KFunction0 and KSuspendFunction1 are function reference types, not function types
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.FunctionTypesSample",
            pageSize = 20
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.FunctionTypesSample [page 1/1]
            class FunctionTypesSample {
                val handler: (String) -> Unit
                fun launchBoth(block: () -> Unit, suspendBlock: suspend () -> Unit)
                fun launchWithReceiver(block: FunctionModifiersTestClass.() -> Unit)
                fun launchInContext(block: context(String) () -> Unit)
                fun launchInContextWithReceiver(block: context(String) Int.(Long) -> Boolean)
                fun launchNullable(block: (() -> Unit)?)
                fun launchHigherOrder(block: suspend (() -> Unit) -> Unit)
                fun launchWithFunctionReceiver(block: (() -> Unit).() -> Unit)
                fun curried(): (Int) -> (Int) -> Int
                fun reference(function: KFunction1<Int, String>)
                fun referenceWithoutParameters(function: KFunction0<String>)
                fun suspendReference(function: KSuspendFunction1<Int, String>)
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve function types above arity 22 in arrow notation`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.LargeArityFunctionTypesSample"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.LargeArityFunctionTypesSample [page 1/1]
            class LargeArityFunctionTypesSample {
                fun function(block: (${List(23) { "Int" }.joinToString(", ")}) -> Unit)
                fun suspendFunction(block: suspend (${List(22) { "Int" }.joinToString(", ")}) -> Unit)
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve parameter names of function types`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NamedFunctionTypeParametersSample"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NamedFunctionTypeParametersSample [page 1/1]
            class NamedFunctionTypeParametersSample {
                fun launch(block: suspend (id: Int, name: String) -> Unit)
                fun filter(predicate: (index: Int, Char) -> Boolean)
                fun launchWithReceiver(block: String.(index: Int) -> Unit)
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve extension function on function type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestFunctionModifiersKt.repeat"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestFunctionModifiersKt.repeat [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun (() -> Unit).repeat()
        """.trimIndent()
    }

    @Test
    fun `should resolve extension property on function type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestFunctionModifiersKt.repeatCount"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestFunctionModifiersKt.repeatCount [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            val (() -> Unit).repeatCount: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve extension function on context function type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestFunctionModifiersKt.runInContext"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestFunctionModifiersKt.runInContext [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun (context(String) () -> Unit).runInContext()
        """.trimIndent()
    }

    // function types, resolved by their Kotlin names

    @Test
    fun `should resolve function type by its Kotlin name with its extensions`() {
        // the extensions of the supertype kotlin.Function are listed as well, and the ones of any library,
        // unlike for a built-in type, like the ones of the test package, or of kotlinx-coroutines, if on the classpath
        resolver.resolve(
            "kotlin.Function0",
            pageSize = 1000
        ) should {
            have(lines().take(5) == listOf(
                "// kotlin.Function0 [page 1/1]",
                "interface Function0<out R> : Function<R> {",
                "    operator fun invoke(): R",
                "}",
                "// from com.xemantic.kotlin.introspection.test"
            ))
            have("val (() -> Unit).repeatCount: Int" in lines())
            have("fun (() -> Unit).repeat()" in lines())
            have("fun <R> Function<R>.reflect(): KFunction<R>?" in lines())
        }
    }

    @Test
    fun `should resolve function type with the extensions of function types with receiver and context parameters`() {
        // String.() -> Unit and context(String) () -> Unit are both Function1<String, Unit>
        resolver.resolve(
            "kotlin.Function1",
            pageSize = 1000
        ) should {
            have(lines()[1] == "interface Function1<in P1, out R> : Function<R> {")
            have("    operator fun invoke(p1: P1): R" in lines())
            have("fun (context(String) () -> Unit).runInContext()" in lines())
        }
    }

    @Test
    fun `should not list the extensions of suspend function types of the same runtime arity among the ones of function type`() {
        // suspend () -> T is stored in the metadata as Function1<Continuation<T>, Any?>
        resolver.resolve(
            "kotlin.Function1",
            pageSize = 1000
        ) should {
            have("startCoroutine" !in this)
            have("retry" !in this)
        }
    }

    @Test
    fun `should resolve suspend function type by its Kotlin name with its extensions`() {
        resolver.resolve(
            "kotlin.coroutines.SuspendFunction0",
            pageSize = 1000
        ) should {
            have(lines()[1] == "interface SuspendFunction0<out R> : Function<R> {")
            have("    suspend operator fun invoke(): R" in lines())
            have("fun (suspend () -> Unit).retry()" in lines())
            have("val (suspend () -> Unit).retryCount: Int" in lines())
            have("fun <T> (suspend () -> T).startCoroutine(completion: Continuation<T>)" in lines())
            have("fun <T> (suspend () -> T).createCoroutine(completion: Continuation<T>): Continuation<Unit>" in lines())
            have("fun <R> Function<R>.reflect(): KFunction<R>?" in lines())
            have("repeat()" !in this)
        }
    }

    @Test
    fun `should resolve suspend function type with receiver with its extensions`() {
        resolver.resolve(
            "kotlin.coroutines.SuspendFunction1",
            pageSize = 1000
        ) should {
            have(lines()[1] == "interface SuspendFunction1<in P1, out R> : Function<R> {")
            have("    suspend operator fun invoke(p1: P1): R" in lines())
            have("fun <R, T> (suspend R.() -> T).startCoroutine(receiver: R, completion: Continuation<T>)" in lines())
        }
    }

    @Test
    fun `should resolve function type above arity 22`() {
        resolver.resolve(
            "kotlin.Function23"
        ) should {
            have(lines()[1] == "interface Function23<${(1..23).joinToString(", ") { "in P$it" }}, out R> : Function<R> {")
            have(lines()[2] == "    operator fun invoke(${(1..23).joinToString(", ") { "p$it: P$it" }}): R")
        }
    }

    @Test
    fun `should resolve function types up to the arity of a JVM method`() {
        // the invoke of a function type takes at most 254 parameters, besides this,
        // of which one is the continuation of a suspend function type

        // when
        val function254 = resolver.resolve("kotlin.Function254")
        val function255 = resolver.resolve("kotlin.Function255")
        val suspendFunction253 = resolver.resolve("kotlin.coroutines.SuspendFunction253")
        val suspendFunction254 = resolver.resolve("kotlin.coroutines.SuspendFunction254")

        // then
        assert(function254 != null && function255 == null)
        assert(suspendFunction253 != null && suspendFunction254 == null)
    }

    @Test
    fun `should not resolve function type name with leading zero in arity`() {
        assert(resolver.resolve("kotlin.Function01") == null)
    }

    @Test
    fun `should not resolve suspend function type name without arity`() {
        assert(resolver.resolve("kotlin.coroutines.SuspendFunction") == null)
    }

    @Test
    fun `should resolve invoke of function type`() {
        resolver.resolve(
            "kotlin.Function1.invoke"
        ) sameAsKotlin """
            // kotlin.Function1.invoke [page 1/1]
            operator fun invoke(p1: P1): R
        """.trimIndent()
    }

    @Test
    fun `should resolve extension of function type as its member`() {
        resolver.resolve(
            "kotlin.Function0.repeat"
        ) sameAsKotlin """
            // kotlin.Function0.repeat [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun (() -> Unit).repeat()
        """.trimIndent()
    }

    @Test
    fun `should resolve extension of suspend function type as its member`() {
        resolver.resolve(
            "kotlin.coroutines.SuspendFunction0.startCoroutine"
        ) sameAsKotlin """
            // kotlin.coroutines.SuspendFunction0.startCoroutine [page 1/1]
            // from kotlin.coroutines
            fun <T> (suspend () -> T).startCoroutine(completion: Continuation<T>)
        """.trimIndent()
    }

    @Test
    fun `should resolve JVM function interface with the extensions of its function type`() {
        // like kotlin.Function0
        resolver.resolve(
            "kotlin.jvm.functions.Function0",
            pageSize = 1000
        ) should {
            have(lines().take(5) == listOf(
                "// kotlin.jvm.functions.Function0 [page 1/1]",
                "interface Function0<out R> : Function<R> {",
                "    operator fun invoke(): R",
                "}",
                "// from com.xemantic.kotlin.introspection.test"
            ))
            have("val (() -> Unit).repeatCount: Int" in lines())
            have("fun (() -> Unit).repeat()" in lines())
            have("fun <R> Function<R>.reflect(): KFunction<R>?" in lines())
        }
    }

    @Test
    fun `should resolve extension of Function as member of function type`() {
        // like in the listing of kotlin.Function0
        resolver.resolve(
            "kotlin.Function0.reflect"
        ) sameAsKotlin """
            // kotlin.Function0.reflect [page 1/1]
            // from kotlin.reflect.jvm
            fun <R> Function<R>.reflect(): KFunction<R>?
        """.trimIndent()
    }

    @Test
    fun `should resolve extension of function type as member of JVM function interface`() {
        // like in the listing of kotlin.jvm.functions.Function0
        resolver.resolve(
            "kotlin.jvm.functions.Function0.repeat"
        ) sameAsKotlin """
            // kotlin.jvm.functions.Function0.repeat [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun (() -> Unit).repeat()
        """.trimIndent()
    }

    @Test
    fun `should find function types by their Kotlin names`() {
        resolver.search("Function1", pageSize = 1000) should {
            have("kotlin.Function1" in lines())
            have("kotlin.Function10" in lines())
            have("kotlin.coroutines.SuspendFunction1" in lines())
        }
    }

    @Test
    fun `should find function types up to the ones compiled to their own JVM interfaces`() {
        // SuspendFunction22 takes the continuation as the 23rd runtime parameter, compiled to FunctionN
        resolver.search("Function2", pageSize = 1000) should {
            have("kotlin.Function22" in lines())
            have("kotlin.coroutines.SuspendFunction21" in lines())
            have("kotlin.coroutines.SuspendFunction22" !in lines())
        }
    }

    // function reference types, resolved by their Kotlin names

    @Test
    fun `should resolve function reference type by its Kotlin name with its extensions`() {
        // the extensions of its function type, like of kotlin.Function1, and the ones of KFunction
        resolver.resolve(
            "kotlin.reflect.KFunction1",
            pageSize = 1000
        ) should {
            have(lines()[1] == "interface KFunction1<in P1, out R> : KFunction<R>, Function1<P1, R> {")
            have(lines()[2] == "    operator fun invoke(p1: P1): R")
            have("val KFunction<*>.javaMethod: java.lang.reflect.Method?" in lines())
            have("fun <P, R> ((P) -> R).handleAll(events: List<P>): List<R>" in lines())
            have("fun <R> Function<R>.reflect(): KFunction<R>?" in lines())
        }
    }

    @Test
    fun `should resolve suspend function reference type by its Kotlin name with its extensions`() {
        resolver.resolve(
            "kotlin.reflect.KSuspendFunction0",
            pageSize = 1000
        ) should {
            have(lines()[1] == "interface KSuspendFunction0<out R> : KFunction<R>, SuspendFunction0<R> {")
            have(lines()[2] == "    suspend operator fun invoke(): R")
            have("val KFunction<*>.javaMethod: java.lang.reflect.Method?" in lines())
            have("fun (suspend () -> Unit).retry()" in lines())
            have("fun (() -> Unit).repeat()" !in lines())
        }
    }

    @Test
    fun `should resolve extension of KFunction as member of function reference type`() {
        // like in the listing of kotlin.reflect.KFunction1
        resolver.resolve(
            "kotlin.reflect.KFunction1.javaMethod"
        ) sameAsKotlin """
            // kotlin.reflect.KFunction1.javaMethod [page 1/1]
            // from kotlin.reflect.jvm
            val KFunction<*>.javaMethod: java.lang.reflect.Method?
        """.trimIndent()
    }

    @Test
    fun `should resolve function reference type with the extensions of the supertypes of KFunction`() {
        // KFunction<R> : KCallable<R>, KCallable<R> : KAnnotatedElement
        resolver.resolve(
            "kotlin.reflect.KFunction1",
            pageSize = 1000
        ) should {
            have("var KCallable<*>.isAccessible: Boolean" in lines())
            have("suspend fun <R> KCallable<R>.callSuspend(vararg args: Any?): R" in lines())
            have("inline fun <reified T : Annotation> KAnnotatedElement.findAnnotation(): T?" in lines())
        }
    }

    @Test
    fun `should resolve member of KCallable as member of function reference type`() {
        resolver.resolve(
            "kotlin.reflect.KFunction1.name"
        ) sameAsKotlin """
            // kotlin.reflect.KFunction1.name [page 1/1]
            // inherited from kotlin.reflect.KCallable
            val name: String
        """.trimIndent()
    }

    @Test
    fun `should resolve invoke of function reference type`() {
        resolver.resolve(
            "kotlin.reflect.KFunction2.invoke"
        ) sameAsKotlin """
            // kotlin.reflect.KFunction2.invoke [page 1/1]
            operator fun invoke(p1: P1, p2: P2): R
        """.trimIndent()
    }

    @Test
    fun `should not resolve function reference type name with leading zero in arity`() {
        assert(resolver.resolve("kotlin.reflect.KFunction01") == null)
    }

    @Test
    fun `should not resolve function type by its metadata name`() {
        // like any other class, resolved only by its Kotlin name

        // when
        val function = resolver.resolve("kotlin/Function1")
        val functionReference = resolver.resolve("kotlin/reflect/KFunction1")

        // then
        assert(function == null && functionReference == null)
    }

    @Test
    fun `should find function reference types like their function types`() {
        resolver.search("Function2", pageSize = 1000) should {
            have("kotlin.reflect.KFunction22" in lines())
            have("kotlin.reflect.KSuspendFunction21" in lines())
            have("kotlin.reflect.KSuspendFunction22" !in lines())
        }
    }

    @Test
    fun `should resolve class with property modifiers`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PropertyModifiersTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PropertyModifiersTestClass [page 1/1]
            class PropertyModifiersTestClass {
                var lateInitProperty: String
                val lazyProperty: String
            }
        """.trimIndent()
    }

    // TestModifiers tests

    @Test
    fun `should resolve class with visibility modifiers`() {
        // private and internal members cannot be called from the user code,
        // and protected ones neither, as a final class cannot be subclassed
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.VisibilityTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.VisibilityTestClass [page 1/1]
            class VisibilityTestClass {
                val publicProperty: String
                fun publicFunction(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve open class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenTestClass [page 1/1]
            open class OpenTestClass {
                open val openProperty: String
                open fun openFunction(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve sealed class`() {
        // an LLM needs to see sealed subclasses to know what types are valid in when expressions
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SealedTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedTestClass [page 1/1]
            sealed class SealedTestClass {
                class SubClass1 : SealedTestClass
                class SubClass2 : SealedTestClass
                data class SubData : SealedTestClass
                data object SubDataObject : SealedTestClass
                object SubObject : SealedTestClass
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve nested class directly`() {
        // nested class can be resolved independently to see its full details
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SealedTestClass.SubClass1"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedTestClass.SubClass1 [page 1/1]
            class SubClass1(val name: String) : SealedTestClass
        """.trimIndent()
    }

    @Test
    fun `should resolve abstract class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.AbstractTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.AbstractTestClass [page 1/1]
            abstract class AbstractTestClass {
                abstract val abstractProperty: String
                open val openProperty: String
                abstract fun abstractFunction(): String
                open fun openFunction(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve class with override modifier`() {
        // compareTo from Comparable is an operator function,
        // with the extensions on Comparable, as the class is comparable to itself
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OverrideTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OverrideTestClass [page 1/1]
            class OverrideTestClass : Comparable<OverrideTestClass> {
                override operator fun compareTo(other: OverrideTestClass): Int
            }
            // from com.xemantic.kotlin.introspection.test
            fun <E : Comparable<in E>> E.isBetween(low: E, high: E): Boolean
            // from kotlin
            inline infix fun <T> Comparable<T>.compareTo(other: T): Int
            // from kotlin.ranges
            fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T
            fun <T : Comparable<T>> T.coerceAtMost(maximumValue: T): T
            fun <T : Comparable<T>> T.coerceIn(minimumValue: T?, maximumValue: T?): T
            fun <T : Comparable<T>> T.coerceIn(range: ClosedFloatingPointRange<T>): T
            fun <T : Comparable<T>> T.coerceIn(range: ClosedRange<T>): T
            operator fun <T : Comparable<T>> T.rangeTo(that: T): ClosedRange<T>
            operator fun <T : Comparable<T>> T.rangeUntil(that: T): OpenEndRange<T>
        """.trimIndent()
    }

    // TestOverrides tests

    @Test
    fun `should not mark overload of superclass function as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OverloadingSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OverloadingSubclass [page 1/1]
            class OverloadingSubclass : OverloadedBase {
                fun foo(x: Int)
            }
        """.trimIndent()
    }

    // the private member, compiled with -jvm-default=disable, is present only in DefaultImplsGreeter$DefaultImpls,
    // so its absence from the class file of the interface does not mean it was shrunk;
    // it is not listed, as private members are not part of the API since #94,
    // but the test guards that the relocated private overload is never rendered as an override
    @Test
    fun `should not mark private overload of interface member relocated to DefaultImpls as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsGreeter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsGreeter [page 1/1]
            interface DefaultImplsGreeter : DefaultImplsBase {
                open override val label: String
                open override fun greet(name: String): String
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark functions named like final Object methods as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ObjectMethodNamesSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ObjectMethodNamesSubclass [page 1/1]
            class ObjectMethodNamesSubclass : NonAnyBase {
                fun notify(msg: String)
                fun wait(ms: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should not list protected override in final class`() {
        // a final class cannot be subclassed, so its protected members are not part of the API
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.FinalProtectedOverrideSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.FinalProtectedOverrideSubclass [page 1/1]
            class FinalProtectedOverrideSubclass : ProtectedAbstractBase
        """.trimIndent()
    }

    @Test
    fun `should mark override of protected abstract function`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedOverrideSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedOverrideSubclass [page 1/1]
            open class ProtectedOverrideSubclass : ProtectedAbstractBase {
                protected override fun bar()
            }
        """.trimIndent()
    }

    @Test
    fun `should tell override of generic function from its overload`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.StringProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.StringProcessor [page 1/1]
            abstract class StringProcessor : Processor<String> {
                override fun process(item: String)
                fun process(item: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should tell override of generic function with primitive type argument from its overload`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.IntProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.IntProcessor [page 1/1]
            class IntProcessor : Processor<Int> {
                override fun process(item: Int)
                fun process(item: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should tell override of generic function with nullable type argument from its overload`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NullableStringProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NullableStringProcessor [page 1/1]
            class NullableStringProcessor : Processor<String?> {
                override fun process(item: String?)
                fun process(item: Int?)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of function declared further up the hierarchy`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DeepProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DeepProcessor [page 1/1]
            class DeepProcessor : IntermediateProcessor {
                override fun process(item: String)
                override fun toString(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of Kotlin collection function`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.StringList"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.StringList [page 1/1]
            abstract class StringList : AbstractList<String> {
                override operator fun contains(element: String): Boolean
                override fun indexOf(element: String): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of Kotlin collection function with primitive type argument`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.IntList"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.IntList [page 1/1]
            abstract class IntList : AbstractList<Int> {
                override operator fun contains(element: Int): Boolean
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of Kotlin map function`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.StringKeyMap"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.StringKeyMap [page 1/1]
            abstract class StringKeyMap : AbstractMap<String, Int> {
                override operator fun get(key: String): Int?
                override fun containsKey(key: String): Boolean
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of Java collection function declaring Object parameter`() {
        // AbstractList.contains(Object) is seen in Kotlin as contains(element: E) of MutableCollection
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaStringList"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaStringList [page 1/1]
            abstract class JavaStringList : java.util.AbstractList<String> {
                override operator fun get(index: Int): String
                override operator fun contains(element: String): Boolean
                fun contains(element: Int): Boolean
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of generic function with value class type argument`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.UIntProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.UIntProcessor [page 1/1]
            class UIntProcessor : Processor<UInt> {
                override fun process(item: UInt)
            }
        """.trimIndent()
    }

    @Test
    fun `should tell override of function with value class parameter from its overload`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.UIntSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.UIntSubclass [page 1/1]
            class UIntSubclass : UIntBase {
                override fun take(x: UInt)
                fun take(x: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of value class member with value class parameter`() {
        // with the extensions on Comparable, as the class is comparable to itself
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ValueComparable",
            pageSize = 100
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ValueComparable [page 1/1]
            @JvmInline value class ValueComparable(val x: Int) : Comparable<ValueComparable> {
                override operator fun compareTo(other: ValueComparable): Int
                override operator fun equals(other: Any?): Boolean
                override fun hashCode(): Int
                override fun toString(): String
            }
            // from com.xemantic.kotlin.introspection.test
            fun <E : Comparable<in E>> E.isBetween(low: E, high: E): Boolean
            // from kotlin
            inline infix fun <T> Comparable<T>.compareTo(other: T): Int
            // from kotlin.ranges
            fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T
            fun <T : Comparable<T>> T.coerceAtMost(maximumValue: T): T
            fun <T : Comparable<T>> T.coerceIn(minimumValue: T?, maximumValue: T?): T
            fun <T : Comparable<T>> T.coerceIn(range: ClosedFloatingPointRange<T>): T
            fun <T : Comparable<T>> T.coerceIn(range: ClosedRange<T>): T
            operator fun <T : Comparable<T>> T.rangeTo(that: T): ClosedRange<T>
            operator fun <T : Comparable<T>> T.rangeUntil(that: T): OpenEndRange<T>
        """.trimIndent()
    }

    @Test
    fun `should mark override with value class type argument`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ValueComparableHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ValueComparableHolder [page 1/1]
            class ValueComparableHolder : Comparable<ValueComparable> {
                override operator fun compareTo(other: ValueComparable): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override with type argument nested in another type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.StringArrayProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.StringArrayProcessor [page 1/1]
            class StringArrayProcessor : ArrayProcessor<String> {
                override fun process(item: Array<String>)
            }
        """.trimIndent()
    }

    @Test
    fun `should tell override of function with array of type parameter from its overload`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.StringItemsProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.StringItemsProcessor [page 1/1]
            class StringItemsProcessor : ItemsProcessor<String> {
                override fun process(items: Array<String>)
                fun process(items: Array<Int>)
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark finalize as override`() {
        // Object.finalize is overridable on the JVM, but it is not a member of Any
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.Finalizable"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.Finalizable [page 1/1]
            open class Finalizable {
                protected fun finalize()
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of clone of Cloneable`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CloneableCopy"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CloneableCopy [page 1/1]
            class CloneableCopy : Cloneable {
                override fun clone(): Any
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark function named like package-private Java method of another package as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PackagePrivateSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PackagePrivateSubclass [page 1/1]
            class PackagePrivateSubclass : com.xemantic.kotlin.introspection.test.other.PackagePrivateBase {
                fun hidden()
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of Java superclass method taking nested class named with dollar sign`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaDollarTaker"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaDollarTaker [page 1/1]
            class JavaDollarTaker : com.xemantic.kotlin.introspection.test.other.JavaDollarBase {
                override fun take(nested: NestedClassesHolder.`Dollar${'$'}Nested`)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of generic Java superclass methods`() {
        // JavaProcessorBase.finish(int) is final, and count() protected
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaProcessor [page 1/1]
            open class JavaProcessor : com.xemantic.kotlin.introspection.test.other.JavaProcessorBase<String> {
                override fun process(item: String)
                fun process(item: Int)
                override fun handle(items: List<String>)
                protected override fun count(): Int
                fun finish()
                override fun <N : Number> measure(value: N)
                fun measure(value: String)
                override fun all(items: Array<String>)
                fun all(items: Array<Int>)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override in interface`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.StringProcessorInterface"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.StringProcessorInterface [page 1/1]
            interface StringProcessorInterface : Processor<String> {
                override fun process(item: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should keep abstract and final modality of override`() {
        // an open override is rendered as just override, which is implicitly open
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ModalityOverrides"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ModalityOverrides [page 1/1]
            abstract class ModalityOverrides : ModalityBase {
                abstract override val abstractedValue: Int
                final override val finalizedValue: Int
                abstract override fun abstracted()
                final override fun finalized()
                protected final override fun protectedFinalized()
                override fun reopened()
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark override in final class as final`() {
        // all the members of a final class are final, which makes final override redundant
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.FinalClassOverrides"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.FinalClassOverrides [page 1/1]
            class FinalClassOverrides : ModalityBase {
                override fun finalized()
                override fun reopened()
            }
        """.trimIndent()
    }

    @Test
    fun `should keep final modality of override declared in primary constructor`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.FinalConstructorOverride"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.FinalConstructorOverride [page 1/1]
            abstract class FinalConstructorOverride(final override val u: UInt) : HasU
        """.trimIndent()
    }

    @Test
    fun `should mark open property declared in primary constructor`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenConstructorProperty"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenConstructorProperty [page 1/1]
            open class OpenConstructorProperty(open val a: Int, val b: Int)
        """.trimIndent()
    }

    @Test
    fun `should not mark override in enum class with entry bodies as final`() {
        // its entries can override its members, but the user code cannot subclass it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.EntryBodiesEnum"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.EntryBodiesEnum [page 1/1]
            enum class EntryBodiesEnum : EnumOverridable {
                A,
                B;
                override fun f(): Int
                override fun g()
            }
        """.trimIndent()
    }

    @Test
    fun `should mark neither open member nor final override of open class with only private constructor`() {
        // like its protected members, which are not listed, as the user code cannot subclass it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PrivateConstructorModality"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PrivateConstructorModality [page 1/1]
            open class PrivateConstructorModality private constructor() : ModalityBase {
                fun f()
                override fun finalized()
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve open member of open class with only private constructor without open modifier`() {
        resolver.resolve("com.xemantic.kotlin.introspection.test.PrivateConstructorModality.f") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PrivateConstructorModality.f [page 1/1]
            fun f()
        """.trimIndent()
    }

    @Test
    fun `should not mark open member of sealed class without open subclass as open`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ClosedSealedModality"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ClosedSealedModality [page 1/1]
            sealed class ClosedSealedModality {
                class Sub : ClosedSealedModality
                fun f()
            }
        """.trimIndent()
    }

    @Test
    fun `should mark open member of sealed class with open subclass as open`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenSealedModality"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenSealedModality [page 1/1]
            sealed class OpenSealedModality {
                open class Sub : OpenSealedModality
                open fun f()
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark override in enum class without entry bodies as final`() {
        // the metadata does not tell whether the entries have bodies, but the JVM class is final without them
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PlainEnum"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PlainEnum [page 1/1]
            enum class PlainEnum : EnumOverridable {
                A,
                B;
                override fun f(): Int
                override fun g()
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark constructor property override in enum class without entry bodies as final`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PlainConstructorOverrideEnum"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PlainConstructorOverrideEnum [page 1/1]
            enum class PlainConstructorOverrideEnum(override val u: UInt) : HasU {
                A
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override with default body in interface as open`() {
        // like any other member of an interface with a default body, unlike an abstract one
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DefaultBodyOverride"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DefaultBodyOverride [page 1/1]
            interface DefaultBodyOverride : DefaultBodyBase {
                open override fun f()
            }
        """.trimIndent()
    }

    @Test
    fun `should render abstract override of member with default body in interface without modality`() {
        // abstract again, which is implicit, like for any other member of an interface without a default body
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ReabstractedOverride"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ReabstractedOverride [page 1/1]
            interface ReabstractedOverride : DefaultBodyBase {
                override fun f()
            }
        """.trimIndent()
    }

    @Test
    fun `should keep abstract modality of override in sealed class, but not final one, without open subclass`() {
        // the user code cannot subclass it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SealedModalityOverrides"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedModalityOverrides [page 1/1]
            sealed class SealedModalityOverrides : ModalityBase {
                abstract override fun abstracted()
                override fun finalized()
                override fun reopened()
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark override in object as final`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ObjectModalityOverrides"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ObjectModalityOverrides [page 1/1]
            object ObjectModalityOverrides : ModalityBase {
                override fun finalized()
                override fun reopened()
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark override in companion object as final`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionModalityOverrides"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionModalityOverrides [page 1/1]
            class CompanionModalityOverrides {
                companion object : ModalityBase {
                    override fun finalized()
                    override fun reopened()
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should render modifiers of vararg property declared in primary constructor in conventional order`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.VarargConstructorProperty"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.VarargConstructorProperty [page 1/1]
            open class VarargConstructorProperty(open vararg val xs: Int)
        """.trimIndent()
    }

    @Test
    fun `should render vararg override declared in primary constructor in conventional order`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.VarargConstructorOverride"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.VarargConstructorOverride [page 1/1]
            class VarargConstructorOverride(override vararg val xs: Int) : HasXs
        """.trimIndent()
    }

    @Test
    fun `should keep abstract modality of override in standard library class`() {
        resolver.resolve(
            "kotlin.collections.AbstractCollection.size"
        ) sameAsKotlin """
            // kotlin.collections.AbstractCollection.size [page 1/1]
            abstract override val size: Int
        """.trimIndent()
    }

    @Test
    fun `should mark override of function taking type parameter passed as type argument of supertype`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.Box"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.Box [page 1/1]
            class Box<T> : Processor<T> {
                override fun process(item: T)
                fun process(item: List<T>)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark suspend override with value class type argument`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.UIntSuspendProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.UIntSuspendProcessor [page 1/1]
            class UIntSuspendProcessor : SuspendProcessor<UInt> {
                override suspend fun process(item: UInt)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark member extension overrides with value class type argument`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.UIntReceiverProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.UIntReceiverProcessor [page 1/1]
            class UIntReceiverProcessor : ReceiverProcessor<UInt> {
                override suspend fun Int.handle(x: UInt)
                override fun UInt.describe(): String
                fun describe(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark function with dash in its name as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DashedNameProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DashedNameProcessor [page 1/1]
            class DashedNameProcessor : Processor<String> {
                override fun process(item: String)
                fun `process-handler`(item: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of function of inner class referring to type parameter of outer class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProbeOuterString.Sub"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProbeOuterString.Sub [page 1/1]
            inner class Sub : ProbeOuter<String>.Inner {
                override fun f(t: String)
                fun f(t: Int)
            }
        """.trimIndent()
    }

    // the anonymous context parameter stays _, while a keyword name is escaped,
    // and an extension listed in the body of its receiver class keeps its context parameters
    @Test
    fun `should render context parameters of functions and properties`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ContextParametersSample"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ContextParametersSample [page 1/1]
            class ContextParametersSample {
                context(log: Logger) val label: String
                context(log: Logger) val String.decorated: String
                context(log: Logger) fun run()
                fun run()
                context(log: Logger, c: Ctx) fun <T> process(item: T): List<T>
                context(_: Logger) fun anonymous()
                context(`in`: Logger) fun keyword()
                context(log: Logger) fun String.decorate(): String
            }
            // from com.xemantic.kotlin.introspection.test
            context(log: Logger) val ContextParametersSample.contextExtensionLabel: String
            context(log: Logger) fun ContextParametersSample.contextExtension()
        """.trimIndent()
    }

    @Test
    fun `should render context parameters of overloads otherwise rendered the same`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ContextParametersSample.run"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ContextParametersSample.run [page 1/1]
            context(log: Logger) fun run()
            fun run()
        """.trimIndent()
    }

    @Test
    fun `should render context parameters of top-level function`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestContextParametersKt.contextRun"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestContextParametersKt.contextRun [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            context(log: Logger) fun contextRun()
        """.trimIndent()
    }

    @Test
    fun `should render context parameters of top-level property`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestContextParametersKt.contextLabel"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestContextParametersKt.contextLabel [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            context(log: Logger) val contextLabel: String
        """.trimIndent()
    }

    @Test
    fun `should render context parameters of companion object function found by outer class member lookup`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ContextCompanionSample.create"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ContextCompanionSample.create [page 1/1]
            companion object {
                context(log: Logger) fun create(): ContextCompanionSample
            }
        """.trimIndent()
    }

    @Test
    fun `should render context parameters of companion object property found by outer class member lookup`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ContextCompanionSample.created"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ContextCompanionSample.created [page 1/1]
            companion object {
                context(log: Logger) val created: String
            }
        """.trimIndent()
    }

    @Test
    fun `should render context parameters of supertype extension found by subtype member lookup`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ContextSubtype.describeInContext"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ContextSubtype.describeInContext [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            context(log: Logger) fun ContextSupertype.describeInContext()
        """.trimIndent()
    }

    // the superclass declares context(log: Logger) members
    @Test
    fun `should not mark overloads with other context parameters as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ContextOverloadingSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ContextOverloadingSubclass [page 1/1]
            class ContextOverloadingSubclass : ContextBase {
                context(c: Ctx) val label: String
                fun run()
                context(c: Ctx) fun run()
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides with the same context parameters`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ContextOverridingSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ContextOverridingSubclass [page 1/1]
            class ContextOverridingSubclass : ContextBase {
                context(log: Logger) override val label: String
                context(log: Logger) override fun run()
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override with context parameter and value class type argument`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.UIntContextProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.UIntContextProcessor [page 1/1]
            class UIntContextProcessor : ContextProcessor<UInt> {
                context(c: Ctx) override fun process(item: UInt)
                fun process(item: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of value class member with context parameter`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ValueContextProcessor.process"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ValueContextProcessor.process [page 1/1]
            context(c: Ctx) override fun process(item: UInt)
        """.trimIndent()
    }

    @Test
    fun `should match context parameter of type argument of supertype`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CtxContextual"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CtxContextual [page 1/1]
            class CtxContextual : Contextual<Ctx> {
                context(c: Ctx) override fun act()
                context(log: Logger) fun act()
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark function with context parameter named like Java method as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ContextRunnable"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ContextRunnable [page 1/1]
            class ContextRunnable : java.lang.Runnable {
                override fun run()
                context(c: Ctx) fun run()
            }
        """.trimIndent()
    }

    // context(c: Ctx) fun accept() is accept(Ctx) on the JVM, like Consumer<Ctx>.accept, but not an override in Kotlin
    @Test
    fun `should not mark function with context parameter taken as parameter by Java method as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ContextConsumer"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ContextConsumer [page 1/1]
            abstract class ContextConsumer : java.util.function.Consumer<Ctx> {
                context(c: Ctx) fun accept()
            }
        """.trimIndent()
    }

    // context(c: Ctx) val label has the getter getLabel(Ctx), like the Java method of JavaLabelBase<Ctx>
    @Test
    fun `should not mark property with context parameter named like Java getter taking it as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ContextLabel"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ContextLabel [page 1/1]
            abstract class ContextLabel : com.xemantic.kotlin.introspection.test.other.JavaLabelBase<Ctx> {
                context(c: Ctx) val label: String
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark members named like internal members of superclass of another module as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OtherModuleSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OtherModuleSubclass [page 1/1]
            class OtherModuleSubclass : com.xemantic.kotlin.introspection.othermodule.InternalBase {
                val state: Int
                fun reset()
                override fun keep()
            }
        """.trimIndent()
    }

    // the internal override of reset is not part of the API since #94, so it is not listed,
    // the public override of the internal state property still is
    @Test
    fun `should mark overrides of internal members of superclass of the same module`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.othermodule.SameModuleSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.othermodule.SameModuleSubclass [page 1/1]
            class SameModuleSubclass : InternalBase {
                override val state: Int
            }
        """.trimIndent()
    }

    // the internal override of reset is not listed, as in the same module case
    @Test
    fun `should mark overrides of internal members of superclass of a friend module`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.friendmodule.FriendModuleSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.friendmodule.FriendModuleSubclass [page 1/1]
            class FriendModuleSubclass : com.xemantic.kotlin.introspection.othermodule.InternalBase {
                override val state: Int
            }
        """.trimIndent()
    }

    // the compiler omits the default module name main from the metadata, so equal module names cannot be told apart
    @Test
    fun `should not mark members named like internal members of superclass of another module both named by default as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.otherdefaultmodule.OtherDefaultModuleSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.otherdefaultmodule.OtherDefaultModuleSubclass [page 1/1]
            class OtherDefaultModuleSubclass : com.xemantic.kotlin.introspection.defaultmodule.DefaultModuleBase {
                val state: Int
                fun reset()
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of internal members of superclass of the same module named by default`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.defaultmodule.DefaultModuleSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultmodule.DefaultModuleSubclass [page 1/1]
            class DefaultModuleSubclass : DefaultModuleBase {
                override val state: Int
                override fun clear()
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of PublishedApi internal members of superclass of the same module named by default`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.defaultmodule.DefaultModulePublishedApiSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultmodule.DefaultModulePublishedApiSubclass [page 1/1]
            class DefaultModulePublishedApiSubclass : DefaultModulePublishedApiBase {
                override fun publish(x: Int = …)
            }
        """.trimIndent()
    }

    // a @PublishedApi internal member is not mangled, so an unrelated module of the same name cannot be told
    // from the same one by the JVM methods, but a final member of a final class is not an override
    @Test
    fun `should not mark final members named like PublishedApi internal members of superclass of another module of the same name as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.otherdefaultmodule.OtherDefaultModulePublishedApiSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.otherdefaultmodule.OtherDefaultModulePublishedApiSubclass [page 1/1]
            class OtherDefaultModulePublishedApiSubclass : com.xemantic.kotlin.introspection.defaultmodule.DefaultModulePublishedApiBase {
                fun publish(x: Int)
            }
        """.trimIndent()
    }

    // a @PublishedApi internal member is not mangled, so its JVM method is declared by the subclass as well
    @Test
    fun `should not mark members named like PublishedApi internal members of superclass of another module as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PublishedApiSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PublishedApiSubclass [page 1/1]
            class PublishedApiSubclass : com.xemantic.kotlin.introspection.othermodule.PublishedApiBase {
                fun publish()
            }
        """.trimIndent()
    }

    // a @PublishedApi internal member is not mangled, so that the public override of a friend module
    // cannot be told by the JVM methods from a member of the same name of an unrelated module,
    // but an open member of a final class is an override
    @Test
    fun `should mark public overrides of PublishedApi internal members of superclass of a friend module in final class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.friendmodule.FriendModulePublishedApiSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.friendmodule.FriendModulePublishedApiSubclass [page 1/1]
            class FriendModulePublishedApiSubclass : com.xemantic.kotlin.introspection.othermodule.PublishedApiBase {
                override fun publish()
            }
        """.trimIndent()
    }

    // an accepted trade-off: in a class which is not final, an open member might not be an override,
    // so that the public override of a friend module cannot be told from a member of an unrelated module
    @Test
    fun `should not mark public overrides of PublishedApi internal members of superclass of a friend module in open class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.friendmodule.FriendModuleOpenPublishedApiSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.friendmodule.FriendModuleOpenPublishedApiSubclass [page 1/1]
            open class FriendModuleOpenPublishedApiSubclass : com.xemantic.kotlin.introspection.othermodule.PublishedApiBase {
                open fun publish()
            }
        """.trimIndent()
    }

    @Test
    fun `should match type parameters of overridden functions by position`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.GenericFunctionsImpl"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.GenericFunctionsImpl [page 1/1]
            class GenericFunctionsImpl : GenericFunctions {
                override fun <R> transform(item: R): R
                override fun <A, B> pair(key: A, value: B)
                fun <A, B> pair(key: B, value: A, extra: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of isXxx property`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.EnabledToggle"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.EnabledToggle [page 1/1]
            class EnabledToggle : Toggle {
                override val isEnabled: Boolean
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark isXxx property without overridden supertype property as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.StandaloneToggle"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.StandaloneToggle [page 1/1]
            class StandaloneToggle {
                val isEnabled: Boolean
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark property with getter renamed by JvmName as override of function named like default getter`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.RenamedFooGetter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.RenamedFooGetter [page 1/1]
            class RenamedFooGetter : FooGetterBase {
                val foo: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark JvmField property as override of function named like default getter`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JvmFieldBar"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JvmFieldBar [page 1/1]
            class JvmFieldBar : GetBarBase {
                val bar: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark property as override of function of the same name`() {
        // the length() function is overridden, while the length property overrides nothing
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.LenProbe"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.LenProbe [page 1/1]
            class LenProbe : LenBase {
                val length: Int
                override fun length(): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark value class property as override of function named like its getter`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.UValueNoOverride"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.UValueNoOverride [page 1/1]
            class UValueNoOverride : GetValueBase {
                val value: UInt
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of member extension property`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.StringLengthMeasure"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.StringLengthMeasure [page 1/1]
            class StringLengthMeasure : StringMeasure {
                override val String.measure: Int
                val Int.measure: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of member extension property with value class receiver as type argument`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.UIntReceiverMeasure"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.UIntReceiverMeasure [page 1/1]
            class UIntReceiverMeasure : GenericReceiverMeasure<UInt> {
                override val UInt.measure: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of property of value class type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.UIntValue"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.UIntValue [page 1/1]
            class UIntValue : UIntValueHolder {
                override val value: UInt
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of primary constructor property`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HasUImpl"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HasUImpl [page 1/1]
            class HasUImpl(override val u: UInt, val extra: Int) : HasU
        """.trimIndent()
    }

    @Test
    fun `should mark override of mapped Java collection property`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SizedCollection"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SizedCollection [page 1/1]
            abstract class SizedCollection : java.util.AbstractCollection<String> {
                override val size: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of built-in collection function mapped to differently named JVM method`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.RemovingList"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.RemovingList [page 1/1]
            abstract class RemovingList : java.util.ArrayList<String> {
                override fun removeAt(index: Int): String
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of CharSequence members mapped to differently named JVM methods`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.Chars"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.Chars [page 1/1]
            abstract class Chars : CharSequence {
                override val length: Int
                override operator fun get(index: Int): Char
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of Number members mapped to differently named JVM methods`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SmallNumber"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SmallNumber [page 1/1]
            abstract class SmallNumber : Number {
                override fun toInt(): Int
                override fun toByte(): Byte
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of Map properties mapped to differently named JVM methods`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.KeyedMap"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.KeyedMap [page 1/1]
            abstract class KeyedMap : Map<String, Int> {
                override val keys: Set<String>
                override val entries: Set<Map.Entry<String, Int>>
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of Throwable members of both Kotlin and Java`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.StacklessException"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.StacklessException [page 1/1]
            class StacklessException : java.lang.Exception {
                override val message: String?
                override fun fillInStackTrace(): Throwable
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of invoke of function type supertype`() {
        // with the extensions of the function type, like the ones of kotlin.Function1, of matching type arguments,
        // but not fun (context(String) () -> Unit).runInContext(), on Function1<String, Unit>, as Int is not Unit
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.FunctionTypeImpl"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.FunctionTypeImpl [page 1/1]
            class FunctionTypeImpl : (String) -> Int {
                override operator fun invoke(p1: String): Int
                fun invoke(p1: Int): Int
            }
            // from com.xemantic.kotlin.introspection.test
            fun <P, R> ((P) -> R).handleAll(events: List<P>): List<R>
            // from kotlin.reflect.jvm
            fun <R> Function<R>.reflect(): KFunction<R>?
        """.trimIndent()
    }

    @Test
    fun `should mark override of invoke of suspend function type supertype`() {
        // with the extensions of the suspend function type, like the ones of kotlin.coroutines.SuspendFunction1
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SuspendFunctionTypeImpl"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SuspendFunctionTypeImpl [page 1/1]
            class SuspendFunctionTypeImpl : suspend (String) -> Int {
                override suspend operator fun invoke(p1: String): Int
                fun invoke(p1: String, p2: Int): Int
            }
            // from kotlin.coroutines
            fun <R, T> (suspend R.() -> T).createCoroutine(receiver: R, completion: Continuation<T>): Continuation<Unit>
            fun <R, T> (suspend R.() -> T).startCoroutine(receiver: R, completion: Continuation<T>)
            // from kotlin.coroutines.intrinsics
            fun <R, T> (suspend R.() -> T).createCoroutineUnintercepted(receiver: R, completion: Continuation<T>): Continuation<Unit>
            inline fun <R, T> (suspend R.() -> T).startCoroutineUninterceptedOrReturn(receiver: R, completion: Continuation<T>): Any?
            // from kotlin.reflect.jvm
            fun <R> Function<R>.reflect(): KFunction<R>?
        """.trimIndent()
    }

    @Test
    fun `should mark override of Java method taking Void`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaVoidVisitor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaVoidVisitor [page 1/1]
            class JavaVoidVisitor : com.xemantic.kotlin.introspection.test.other.JavaVoidBase {
                override fun visit(v: java.lang.Void?)
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark properties without JVM getter named like Java method as override`() {
        // a @JvmField or private property has no getter, the getters of foo and value are named fooValue and getValue-<hash>,
        // the private property is not part of the API, so it is not rendered
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaGetterSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaGetterSubclass [page 1/1]
            class JavaGetterSubclass : com.xemantic.kotlin.introspection.test.other.JavaGetterBase {
                val bar: Int
                val foo: Int
                val value: UInt
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark function taking nullable type as override of Java method taking primitive`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaPrimitiveSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaPrimitiveSubclass [page 1/1]
            class JavaPrimitiveSubclass : com.xemantic.kotlin.introspection.test.other.JavaPrimitiveBase {
                fun prim(x: Int?)
                override fun boxed(x: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark function taking type parameter as override of Java method taking primitive`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaPrimitiveTypeParameterSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaPrimitiveTypeParameterSubclass [page 1/1]
            class JavaPrimitiveTypeParameterSubclass<T : Int?> : com.xemantic.kotlin.introspection.test.other.JavaPrimitiveBase {
                fun prim(x: T)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of Java methods taking primitive and boxed types`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaNullablePrimitiveSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaNullablePrimitiveSubclass [page 1/1]
            class JavaNullablePrimitiveSubclass : com.xemantic.kotlin.introspection.test.other.JavaPrimitiveBase {
                override fun prim(x: Int)
                override fun boxed(x: Int?)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override taking nullable type of Java method taking type variable substituted with Int`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaNullableIntProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaNullableIntProcessor [page 1/1]
            abstract class JavaNullableIntProcessor : com.xemantic.kotlin.introspection.test.other.JavaProcessorBase<Int> {
                override fun process(item: Int?)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of Java method whose type variable has parameterized bound`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaSorter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaSorter [page 1/1]
            class JavaSorter : com.xemantic.kotlin.introspection.test.other.JavaSortBase {
                override fun <T : Comparable<T>> sort(x: T)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of Java method whose type variable is bounded by type variable of its class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaClassBoundSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaClassBoundSubclass [page 1/1]
            class JavaClassBoundSubclass : com.xemantic.kotlin.introspection.test.other.JavaClassBoundBase<CharSequence> {
                override fun <T : CharSequence> bounded(x: T)
                fun <T : Number> bounded(x: T)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of Java methods taking arrays of type variable`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaArraySubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaArraySubclass [page 1/1]
            class JavaArraySubclass : com.xemantic.kotlin.introspection.test.other.JavaArrayBase<String> {
                override fun process(items: Array<String>)
                fun process(items: Array<Int>)
                override fun <S : String> bounded(items: Array<S>)
                fun <S : Number> bounded(items: Array<S>)
                override fun nested(items: Array<Array<String>>)
                fun nested(items: Array<Array<Int>>)
                override fun lists(items: Array<List<String>>)
                fun lists(items: Array<Set<String>>)
            }
        """.trimIndent()
    }

    @Test
    fun `should erase type variable of raw Java superclass taken as array to its bound`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaRawArraySubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaRawArraySubclass [page 1/1]
            class JavaRawArraySubclass : com.xemantic.kotlin.introspection.test.other.JavaRawArrayMid {
                override fun process(items: Array<Any?>)
                fun process(items: Array<Int>)
            }
        """.trimIndent()
    }

    @Test
    fun `should erase type variable of raw Java superclass bounding method type variable to its bound`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaRawClassBoundSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaRawClassBoundSubclass [page 1/1]
            class JavaRawClassBoundSubclass : com.xemantic.kotlin.introspection.test.other.JavaRawClassBoundMid {
                fun <T : Number> bounded(x: T)
            }
        """.trimIndent()
    }

    @Test
    fun `should erase type parameters of raw Kotlin superclass of Java class to their bounds`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaRawKotlinSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaRawKotlinSubclass [page 1/1]
            class JavaRawKotlinSubclass : com.xemantic.kotlin.introspection.test.other.JavaRawKotlinMid {
                override fun bounded(x: CharSequence)
                fun bounded(x: String)
                override fun compared(x: Comparable<*>)
                fun compared(x: String)
                override fun chained(x: CharSequence)
                fun chained(x: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should erase type parameters of raw inner Kotlin superclass of Java class and of its outer class to their bounds`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaRawInnerSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaRawInnerSubclass [page 1/1]
            class JavaRawInnerSubclass(outer: KotlinRawOuter<String>) : com.xemantic.kotlin.introspection.test.other.JavaRawInnerMid {
                override fun outer(x: CharSequence)
                fun outer(x: String)
                override fun inner(x: Number)
                fun inner(x: Int)
                override fun chained(x: CharSequence)
                fun chained(x: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should erase type parameter of raw built-in supertype of Java class to its bound`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaRawComparableSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaRawComparableSubclass [page 1/1]
            abstract class JavaRawComparableSubclass : com.xemantic.kotlin.introspection.test.other.JavaRawComparable {
                override operator fun compareTo(other: Any?): Int
                fun compareTo(other: String): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not bind Java method type variable shadowing type variable of its class to type argument of the class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaOuterSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaOuterSubclass [page 1/1]
            class JavaOuterSubclass : com.xemantic.kotlin.introspection.test.other.JavaOuterBase<String> {
                inner class InnerSubclass : com.xemantic.kotlin.introspection.test.other.JavaOuterBase<String>.Inner<Int>
                inner class ShadowingSubclass : com.xemantic.kotlin.introspection.test.other.JavaOuterBase<String>.Shadowing<Int>
                override fun <T : Number> shadowing(x: T)
                fun <T : String> shadowing(x: T)
            }
        """.trimIndent()
    }

    @Test
    fun `should bind type variable of outer Java class to type argument of outer class of supertype`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaOuterSubclass.InnerSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaOuterSubclass.InnerSubclass [page 1/1]
            inner class InnerSubclass : com.xemantic.kotlin.introspection.test.other.JavaOuterBase<String>.Inner<Int> {
                override fun outer(x: String)
                fun outer(x: Int)
                override fun inner(x: Int)
                fun inner(x: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should bind type variable of inner Java class shadowing type variable of outer class to its own type argument`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaOuterSubclass.ShadowingSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaOuterSubclass.ShadowingSubclass [page 1/1]
            inner class ShadowingSubclass : com.xemantic.kotlin.introspection.test.other.JavaOuterBase<String>.Shadowing<Int> {
                override fun shadowed(x: Int)
                fun shadowed(x: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark function of other erasure as override of Java method whose type variable has parameterized bound`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CharSequenceSorter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CharSequenceSorter [page 1/1]
            class CharSequenceSorter : com.xemantic.kotlin.introspection.test.other.JavaSortBase {
                fun <T : CharSequence> sort(x: T)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of Any members of data class implementing Java interface`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SerializableData"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SerializableData [page 1/1]
            data class SerializableData(val a: Int) : java.io.Serializable {
                operator fun component1(): Int
                fun copy(a: Int = …): SerializableData
                override operator fun equals(other: Any?): Boolean
                override fun hashCode(): Int
                override fun toString(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of Any members of class implementing Java interface`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.AnyMembersRunnable"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.AnyMembersRunnable [page 1/1]
            class AnyMembersRunnable : java.lang.Runnable {
                override fun run()
                override fun toString(): String
                override operator fun equals(other: Any?): Boolean
                override fun hashCode(): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of Any member of class implementing interface extending Java interface`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.RunnableExtensionImpl"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.RunnableExtensionImpl [page 1/1]
            class RunnableExtensionImpl : RunnableExtension {
                override fun run()
                override fun hashCode(): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of JDK members of read-only collection`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JdkList"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JdkList [page 1/1]
            abstract class JdkList : List<String> {
                override fun spliterator(): java.util.Spliterator<String>
                override fun forEach(action: java.util.function.Consumer<in String>)
                override fun stream(): java.util.stream.Stream<String>
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides of JDK members of mutable list`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JdkMutableList"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JdkMutableList [page 1/1]
            abstract class JdkMutableList : AbstractMutableList<String> {
                override fun removeIf(filter: java.util.function.Predicate<in String>): Boolean
                override fun replaceAll(operator: java.util.function.UnaryOperator<String>)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of JDK member of mutable map`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JdkMap"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JdkMap [page 1/1]
            abstract class JdkMap : MutableMap<String, Int> {
                override fun putIfAbsent(key: String, value: Int): Int?
            }
        """.trimIndent()
    }

    @Test
    fun `should not mark JDK members hidden from Kotlin collections as override`() {
        // Collection.toArray is not visible in Kotlin, AbstractCollection declares its own
        resolver.resolve(
            "kotlin.collections.AbstractCollection.toArray"
        ) sameAsKotlin """
            // kotlin.collections.AbstractCollection.toArray [page 1/1]
            protected open fun toArray(): Array<Any?>
            protected open fun <T> toArray(array: Array<T>): Array<T>
        """.trimIndent()
    }

    @Test
    fun `should not mark functions named like JDK 21 sequenced collection members as override`() {
        // the standard library is compiled against JDK 8, without List.addFirst
        resolver.resolve(
            "kotlin.collections.ArrayDeque.addFirst"
        ) sameAsKotlin """
            // kotlin.collections.ArrayDeque.addFirst [page 1/1]
            fun addFirst(element: E)
        """.trimIndent()
    }

    @Test
    fun `should mark override of Java method taking Kotlin function type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.KotlinFunctionTaker"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.KotlinFunctionTaker [page 1/1]
            class KotlinFunctionTaker : com.xemantic.kotlin.introspection.test.other.JavaFunctionTaker {
                override fun onEvent(callback: (String) -> Unit)
                fun onEvent(callback: (Int) -> Unit, extra: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides without redundant use-site projections`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.RedundantProjectionImpl"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.RedundantProjectionImpl [page 1/1]
            abstract class RedundantProjectionImpl : RedundantProjection {
                override fun f(x: List<String>)
                override fun c(x: Comparable<in String>)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark overrides with redundant use-site projections`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PlainProjectionImpl"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PlainProjectionImpl [page 1/1]
            abstract class PlainProjectionImpl : PlainProjection {
                override fun f(x: List<out String>)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override in object`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ObjectProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ObjectProcessor [page 1/1]
            object ObjectProcessor : Processor<String> {
                override fun process(item: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override in companion object`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionProcessor [page 1/1]
            class CompanionProcessor {
                companion object : Processor<Int> {
                    override fun process(item: Int)
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve members inherited from superclass`() {
        // StringProcessor declares both overloads, its override of Processor.process(T) being the nearest declaration
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.IntermediateProcessor.process"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.IntermediateProcessor.process [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.StringProcessor
            override fun process(item: String)
            fun process(item: Int)
        """.trimIndent()
    }

    @Test
    fun `should repeat inherited header on every page`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.IntermediateProcessor.process",
            page = 2,
            pageSize = 1
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.IntermediateProcessor.process [page 2/2]
            // inherited from com.xemantic.kotlin.introspection.test.StringProcessor
            fun process(item: Int)
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited member overridden by the nearest superclass`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedLeaf.greet"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedLeaf.greet [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedMiddle
            override fun greet(name: String): String
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited member overridden by subtype of direct supertype reached deeper`() {
        // InheritedShape is a direct supertype, but InheritedSquareShape, a supertype of the superclass, overrides its area()
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedSquare.area"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedSquare.area [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedSquareShape
            open override fun area(): Double
        """.trimIndent()
    }

    @Test
    fun `should resolve overloads inherited from superclass next to override of another overload`() {
        // InheritedOverloadMiddle overrides only greet(String), greet(String, Int) is still inherited from the base
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedOverloadLeaf.greet"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedOverloadLeaf.greet [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedOverloadMiddle
            override fun greet(name: String): String
            // inherited from com.xemantic.kotlin.introspection.test.InheritedOverloadBase
            fun greet(name: String, times: Int): String
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited overloads told apart by nullability`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedNullableOverloadLeaf.f"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedNullableOverloadLeaf.f [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedNullableOverloadBase
            fun f(x: Int)
            fun f(x: Int?)
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited overload told apart by nullability from overridden one`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedNullableOverloadMiddleLeaf.f"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedNullableOverloadMiddleLeaf.f [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedNullableOverloadMiddle
            override fun f(x: Int)
            // inherited from com.xemantic.kotlin.introspection.test.InheritedNullableOverloadBase
            fun f(x: Int?)
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited overloads next to declared override of another overload`() {
        // InheritedOverloadMiddle declares only the override of greet(String), greet(String, Int) is inherited
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedOverloadMiddle.greet"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedOverloadMiddle.greet [page 1/1]
            override fun greet(name: String): String
            // inherited from com.xemantic.kotlin.introspection.test.InheritedOverloadBase
            fun greet(name: String, times: Int): String
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited overload next to declared overload of type parameter named like the one of supertype`() {
        // h(x: T) of InheritedAnyHolder does not override h(x: T) of InheritedNumberHolder<Int>, which takes an Int
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedAnyHolder.h"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedAnyHolder.h [page 1/1]
            fun h(x: T)
            // inherited from com.xemantic.kotlin.introspection.test.InheritedNumberHolder
            fun h(x: Int)
        """.trimIndent()
    }

    @Test
    fun `should resolve overloads inherited from unrelated supertypes`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedBothFoo.foo"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedBothFoo.foo [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedIntFoo
            fun foo(x: Int)
            // inherited from com.xemantic.kotlin.introspection.test.InheritedStringFoo
            fun foo(x: String)
        """.trimIndent()
    }

    @Test
    fun `should not resolve inherited JVM-only member of built-in type overridden with not nullable platform parameter`() {
        // printStackTrace(s: PrintStream) overrides printStackTrace(arg0: java.io.PrintStream?), of a PrintStream!
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedJvmOverridingException.printStackTrace"
        ) should {
            have("override fun printStackTrace(s: java.io.PrintStream)" in lines())
            have(lines().none { "printStackTrace(arg0: java.io.PrintStream" in it })
        }
    }

    @Test
    fun `should not resolve inherited JVM-only member of built-in type overridden with nullable platform parameter`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedJvmOverridingException.initCause"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedJvmOverridingException.initCause [page 1/1]
            override fun initCause(cause: Throwable?): Throwable
        """.trimIndent()
    }

    @Test
    fun `should not resolve inherited JVM-only member of built-in type overridden with substituted type argument`() {
        // forEach(action: Consumer<in String>) overrides forEach(arg0: java.util.function.Consumer<in T>?) of Iterable<T>
        listOf("InheritedJvmOverridingIterable", "InheritedJvmOverridingList").forEach { name ->
            resolver.resolve("com.xemantic.kotlin.introspection.test.$name.forEach") should {
                have(lines().none { "forEach(arg0: " in it })
            }
        }
    }

    @Test
    fun `should not resolve inherited JVM-only member of built-in type overridden by intermediate class`() {
        // the override of the superclass wins over the JVM member of kotlin.Throwable
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedJvmOverridingLeafException.printStackTrace"
        ) should {
            have("// inherited from com.xemantic.kotlin.introspection.test.InheritedJvmOverridingBaseException" in lines())
            have(lines().none { "printStackTrace(arg0: java.io.PrintStream" in it })
        }
    }

    @Test
    fun `should not resolve inherited JVM-only member of built-in type overridden by intermediate class with substituted type argument`() {
        // the override of the superclass wins over the JVM member of kotlin.collections.Iterable
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedJvmOverridingLeafIterable.forEach"
        ) should {
            have("// inherited from com.xemantic.kotlin.introspection.test.InheritedJvmOverridingBaseIterable" in lines())
            have(lines().none { "forEach(arg0: " in it })
        }
    }

    @Test
    fun `should resolve inherited JVM-only member of built-in type next to extension hiding it only for some arguments`() {
        // the @HidesMembers Throwable.addSuppressed is called instead of the addSuppressed of java.lang.Throwable,
        // but for null, which only the member accepts
        assumeNoParameterNames(Throwable::class.java.getMethod("addSuppressed", Throwable::class.java))
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedJvmException.addSuppressed"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedJvmException.addSuppressed [page 1/1]
            // inherited from kotlin.Throwable
            fun addSuppressed(arg0: Throwable?)
            // from kotlin
            fun Throwable.addSuppressed(exception: Throwable)
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited member before extension of the same name`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedWithExtension.extended"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedWithExtension.extended [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedWithExtensionBase
            fun extended(): Int
            // from com.xemantic.kotlin.introspection.test
            fun InheritedWithExtension.extended(times: Int): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve member inherited from superclass of superclass`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedLeaf.baseValue"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedLeaf.baseValue [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedBase
            val baseValue: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve protected member inherited by open class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenInheritedLeaf.protectedHelper"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenInheritedLeaf.protectedHelper [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedBase
            protected fun protectedHelper()
        """.trimIndent()
    }

    @Test
    fun `should not resolve protected member inherited by final class`() {
        // no subclass of the final class can call it
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.InheritedLeaf.protectedHelper") == null)
    }

    @Test
    fun `should not resolve internal member inherited from superclass`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.InheritedLeaf.internalHelper") == null)
    }

    @Test
    fun `should not resolve hidden member inherited from superclass`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.InheritedLeaf.hiddenHelper") == null)
    }

    @Test
    fun `should not resolve member declared by no supertype`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.InheritedLeaf.noSuchMember") == null)
    }

    @Test
    fun `should resolve member inherited from generic interface with its type argument substituted`() {
        // T is not in scope of the inheriting class, which gives String for it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedStringContainer.take"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedStringContainer.take [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedContainer
            fun take(): String
        """.trimIndent()
    }

    @Test
    fun `should resolve member inherited through chain of generic supertypes with type arguments substituted`() {
        // the T of InheritedContainer is the List<E> of InheritedContainerOf, whose E is the Int of the inheriting class
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedIntListContainer.put"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedIntListContainer.put [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedContainer
            fun put(item: List<Int>)
        """.trimIndent()
    }

    @Test
    fun `should resolve member inherited from generic supertype with type parameter of inheriting class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedContainerOf.take"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedContainerOf.take [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedContainer
            fun take(): List<E>
        """.trimIndent()
    }

    @Test
    fun `should resolve member with body of interface inherited by final class as open`() {
        // like within the interface, telling it apart from an abstract member
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedDefaultGreeterImpl.hello"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedDefaultGreeterImpl.hello [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedDefaultGreeter
            open fun hello(): String
        """.trimIndent()
    }

    @Test
    fun `should resolve members of unrelated supertypes of the same signature once its type argument is substituted`() {
        // accept(x: T) of InheritedGenericConsumer<String> is the same member as accept(x: String) of InheritedStringConsumer
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedBothConsumer.accept"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedBothConsumer.accept [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedGenericConsumer
            fun accept(x: String)
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited member of return type more specific through Java mapping`() {
        // java.util.ArrayList is seen in Kotlin as a subtype of MutableList
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedBothListReturn.items"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedBothListReturn.items [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedJavaListReturn
            fun items(): java.util.ArrayList<String>
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited overloads of inner class told apart by type parameters of outer class`() {
        // Middle overrides only f(x: T), f(x: U) is still inherited from Base
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedOuter.Leaf.f"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedOuter.Leaf.f [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedOuter.Middle
            override fun f(x: T)
            // inherited from com.xemantic.kotlin.introspection.test.InheritedOuter.Base
            fun f(x: U)
        """.trimIndent()
    }

    @Test
    fun `should resolve method declared only by Java superclass`() {
        // the JDK is compiled without parameter names
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.ensureCapacity"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.ensureCapacity [page 1/1]
            // inherited from java.util.ArrayList
            fun ensureCapacity(arg0: Int)
        """.trimIndent()
    }

    @Test
    fun `should resolve member of built-in type overridden by Java superclass as declared by built-in type`() {
        // java.util.ArrayList overrides get, but the declaration of the built-in type tells the parameter names
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.get"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.get [page 1/1]
            // inherited from kotlin.collections.List
            operator fun get(index: Int): String
        """.trimIndent()
    }

    @Test
    fun `should resolve members of built-in type overridden by Java superclasses with parameters of platform types once`() {
        // add(E) of java.util.AbstractList, taking an E!, overrides the add(element: E) of MutableList
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.add"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.add [page 1/1]
            // inherited from kotlin.collections.MutableList
            override fun add(element: String): Boolean
            fun add(index: Int, element: String)
        """.trimIndent()
    }

    @Test
    fun `should resolve Java method with own type parameter renamed apart from type parameter of inheriting class`() {
        // the T of CompletableFuture is the U of InheritedFuture, which the own U of thenApply would capture
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedFuture.thenApply"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedFuture.thenApply [page 1/1]
            // inherited from java.util.concurrent.CompletableFuture
            override fun <U1> thenApply(arg0: java.util.function.Function<in U, out U1>?): java.util.concurrent.CompletableFuture<U1>
        """.trimIndent()
    }

    @Test
    fun `should resolve member with own type parameter renamed apart from type parameter of inheriting class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedRenamingBox.map"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedRenamingBox.map [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedBox
            fun <T1> map(transform: (T) -> T1): InheritedBox<T1>
        """.trimIndent()
    }

    @Test
    fun `should resolve JVM-only member of mutable collection overridden by Java superclass as declared by built-in type`() {
        // removeIf of java.util.ArrayList overrides the one of java.util.Collection, a JVM-only member of MutableCollection
        assumeNoParameterNames(Collection::class.java.getMethod("removeIf", java.util.function.Predicate::class.java))
        resolver.resolve("com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.removeIf") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.removeIf [page 1/1]
            // inherited from kotlin.collections.MutableCollection
            open fun removeIf(arg0: java.util.function.Predicate<in String>): Boolean
        """.trimIndent()
    }

    @Test
    fun `should resolve JVM-only member of mutable map overridden by Java superclass as declared by built-in type`() {
        // putIfAbsent of java.util.HashMap overrides the one of java.util.Map, a JVM-only member of MutableMap
        assumeNoParameterNames(Map::class.java.getMethod("putIfAbsent", Any::class.java, Any::class.java))
        resolver.resolve("com.xemantic.kotlin.introspection.test.InheritedJavaHashMap.putIfAbsent") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedJavaHashMap.putIfAbsent [page 1/1]
            // inherited from kotlin.collections.MutableMap
            open fun putIfAbsent(arg0: String, arg1: Int): Int?
        """.trimIndent()
    }

    @Test
    fun `should resolve JVM-only member of mutable list of JDK 21 overridden by Java superclass as declared by built-in type`() {
        assumeTrue(runCatching { List::class.java.getMethod("addFirst", Any::class.java) }.isSuccess, "JDK 21+")
        resolver.resolve("com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.addFirst") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.addFirst [page 1/1]
            // inherited from kotlin.collections.MutableList
            open fun addFirst(arg0: String)
        """.trimIndent()
    }

    @Test
    fun `should not resolve Java method overriding JDK method which built-in type does not expose`() {
        // Kotlin calls the MutableList<T>.sort extension instead, the JDK sort of java.util.List is not a member of MutableList
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.sort") == null)
    }

    @Test
    fun `should not resolve method of Java superclass implemented by its Java subclass`() {
        // greet() of JavaGreeterMid implements the abstract one of JavaAbstractGreeterBase, and the one of InheritedGreeter
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedFromJavaGreeter.greet"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedFromJavaGreeter.greet [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedGreeter
            fun greet(): String
        """.trimIndent()
    }

    @Test
    fun `should not resolve method of Java superclass overridden by its Java subclass as member of Kotlin supertype`() {
        // size() of java.util.Dictionary is implemented by java.util.Hashtable, seen in Kotlin as the size of Map
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedHashtable.size"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedHashtable.size [page 1/1]
            // inherited from kotlin.collections.Map
            val size: Int
        """.trimIndent()
    }

    @Test
    fun `should not resolve method of Java superclass overridden by its Java subclass overriding member of Kotlin supertype`() {
        // get(Object) of java.util.Dictionary, returning V, is overridden by java.util.Hashtable, overriding the get of Map
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedHashtable.get"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedHashtable.get [page 1/1]
            // inherited from kotlin.collections.Map
            operator fun get(key: K): V?
        """.trimIndent()
    }

    @Test
    fun `should not resolve Java method overriding JDK method which Kotlin hides`() {
        // reversed() of java.util.LinkedList, returning a LinkedList, overrides the reversed() of java.util.List,
        // which Kotlin hides, see HIDDEN_JDK_METHODS, also in the subclasses,
        // so that linkedList.reversed() calls the Iterable<T>.reversed() extension, typed as a List
        assumeTrue(Runtime.version().feature() >= 21, "reversed() is a member of java.util.List since JDK 21")
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.InheritedLinkedList.reversed") == null)
    }

    @Test
    fun `should resolve member of Any overridden by Java superclass of mapped built-in type as declared by Any`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.equals"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.equals [page 1/1]
            // inherited from kotlin.Any
            operator fun equals(other: Any?): Boolean
        """.trimIndent()
    }

    @Test
    fun `should resolve member of Kotlin interface taking nullable parameter overridden by Java superclass as declared by interface`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedFromNullableTaker.take"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedFromNullableTaker.take [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.InheritedNullableTaker
            fun take(x: String?)
        """.trimIndent()
    }

    @Test
    fun `should resolve protected field of Java superclass as var`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenInheritedJavaArrayList.modCount"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenInheritedJavaArrayList.modCount [page 1/1]
            // inherited from java.util.AbstractList
            protected var modCount: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve method of Java superclass making member of Kotlin supertype more visible`() {
        // the public clone() of java.util.ArrayList overrides the protected one of kotlin.Cloneable
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.clone"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedJavaArrayList.clone [page 1/1]
            // inherited from java.util.ArrayList
            override fun clone(): Any
        """.trimIndent()
    }

    @Test
    fun `should resolve method of Java superclass of superclass with type argument substituted`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedFromJava.first"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedFromJava.first [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.other.JavaInheritedBase
            fun first(arg0: List<String>?): String
        """.trimIndent()
    }

    @Test
    fun `should resolve method of Java superclass overriding method of its own Java superclass`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedFromJava.accept"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedFromJava.accept [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.other.JavaInheritedMid
            override fun accept(arg0: String)
        """.trimIndent()
    }

    @Test
    fun `should resolve member of Any overridden by Java superclass as declared by Any`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedFromJava.toString"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedFromJava.toString [page 1/1]
            // inherited from kotlin.Any
            fun toString(): String
        """.trimIndent()
    }

    @Test
    fun `should resolve protected method of Java superclass inherited by open class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenInheritedFromJava.protectedHelper"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenInheritedFromJava.protectedHelper [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.other.JavaInheritedBase
            protected open fun protectedHelper()
        """.trimIndent()
    }

    @Test
    fun `should not resolve protected method of Java superclass inherited by final class`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.InheritedFromJava.protectedHelper") == null)
    }

    @Test
    fun `should not resolve package-private method of Java superclass of another package`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.InheritedFromJava.packagePrivateHelper") == null)
    }

    @Test
    fun `should not resolve static method of Java superclass as member`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.InheritedFromJava.staticHelper") == null)
    }

    @Test
    fun `should resolve public method of package-private Java superclass bridged by its public subclass`() {
        // javac generates a bridge of hello() in JavaPublicGreeterMid, which is not an override
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedThroughPublicJavaMid.hello"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedThroughPublicJavaMid.hello [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.other.JavaPackagePrivateGreeterBase
            fun hello(): String
        """.trimIndent()
    }

    @Test
    fun `should not resolve member of Any for Nothing`() {
        // Nothing has no instances, and no members, which the listing of its class does not show either
        assert(resolver.resolve("kotlin.Nothing.toString") == null)
    }

    @Test
    fun `should not resolve inherited member through name of missing nested class`() {
        // the qualified member name names no member, so the supertypes are not walked
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.InheritedLeaf.Missing.toString") == null)
    }

    @Test
    fun `should resolve declared override rather than inherited member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedStringContainer.put"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedStringContainer.put [page 1/1]
            override fun put(item: String)
        """.trimIndent()
    }

    @Test
    fun `should resolve member of Any inherited by open class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenInheritedLeaf.toString"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenInheritedLeaf.toString [page 1/1]
            // inherited from kotlin.Any
            open fun toString(): String
        """.trimIndent()
    }

    @Test
    fun `should resolve open member of Any inherited by final class as final`() {
        // like the own members of a final class, none of its members can be overridden
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedLeaf.toString"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedLeaf.toString [page 1/1]
            // inherited from kotlin.Any
            fun toString(): String
        """.trimIndent()
    }

    @Test
    fun `should resolve member of Any inherited by interface extending Java interface`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedJavaRunnable.hashCode"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedJavaRunnable.hashCode [page 1/1]
            // inherited from kotlin.Any
            open fun hashCode(): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve member with body inherited by sealed interface with open subclass as open`() {
        resolver.resolve("com.xemantic.kotlin.introspection.test.OpenSealedInheriting.f") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenSealedInheriting.f [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.SealedInheritedBase
            open fun f()
        """.trimIndent()
    }

    @Test
    fun `should resolve member with body inherited by sealed interface without open subclass as open`() {
        // within an interface, telling it apart from an abstract member, like the own members of an interface
        resolver.resolve("com.xemantic.kotlin.introspection.test.ClosedSealedInheriting.f") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ClosedSealedInheriting.f [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.SealedInheritedBase
            open fun f()
        """.trimIndent()
    }

    @Test
    fun `should resolve member of Any inherited by sealed interface as open`() {
        // rendered as within the interface, where a member with a body is open, telling it apart from an abstract one
        resolver.resolve("com.xemantic.kotlin.introspection.test.SealedAnyInheriting.hashCode") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedAnyInheriting.hashCode [page 1/1]
            // inherited from kotlin.Any
            open fun hashCode(): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve member of Any inherited by sealed class with open subclass as open`() {
        resolver.resolve("com.xemantic.kotlin.introspection.test.OpenSealedInheritingClass.hashCode") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenSealedInheritingClass.hashCode [page 1/1]
            // inherited from kotlin.Any
            open fun hashCode(): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve member of mapped built-in type inherited through Java superclass`() {
        // java.util.AbstractList is not rendered, but it implements java.util.List, seen in Kotlin as MutableList
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedJavaList.get"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedJavaList.get [page 1/1]
            // inherited from kotlin.collections.List
            operator fun get(index: Int): String
        """.trimIndent()
    }

    @Test
    fun `should not list inherited members of class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InheritedStringContainer"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InheritedStringContainer [page 1/1]
            abstract class InheritedStringContainer : InheritedContainer<String> {
                override fun put(item: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve member of nested class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SealedTestClass.SubClass1.name"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedTestClass.SubClass1.name [page 1/1]
            val name: String
        """.trimIndent()
    }

    @Test
    fun `should resolve nested class with companion object`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NestedCompanionTestClass.Inner"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NestedCompanionTestClass.Inner [page 1/1]
            class Inner {
                companion object {
                    fun create(): NestedCompanionTestClass.Inner
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should list nested classes in the outer class`() {
        // an LLM cannot discover a nested class otherwise, the companion object is rendered with its members,
        // while private, internal, and synthetic nested classes are not part of the API,
        // neither is the protected one, as the final outer class cannot be subclassed
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NestedClassesHolder",
            pageSize = 20
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NestedClassesHolder [page 1/1]
            class NestedClassesHolder {
                class NestedClass
                inner class NestedInnerClass
                interface NestedInterface
                object NestedObject
                enum class NestedEnum
                data class NestedData : NestedClassesHolder.NestedInterface
                class `Dollar${'$'}Nested`
                class `${'$'}Prefixed`
                fun anonymous(): Any
                fun whenMapping(nestedEnum: NestedClassesHolder.NestedEnum): Int
                companion object {
                    fun create(): NestedClassesHolder
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve nested class named with dollar sign`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NestedClassesHolder.Dollar\$Nested"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NestedClassesHolder.Dollar${'$'}Nested [page 1/1]
            class `Dollar${'$'}Nested`
        """.trimIndent()
    }

    @Test
    fun `should escape enum entries named like hard keywords`() {
        // a soft keyword, like data, is a valid identifier
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.KeywordEntryEnum"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.KeywordEntryEnum [page 1/1]
            enum class KeywordEntryEnum {
                `in`,
                `object`,
                data
            }
        """.trimIndent()
    }

    @Test
    fun `should escape declarations named like hard keywords or not as identifiers`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.KeywordMembersSample",
            pageSize = 20
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.KeywordMembersSample [page 1/1]
            class KeywordMembersSample<`in`>(val `is`: `in`, val value: Int) {
                class `interface`
                val `typeof`: String
                var `val`: Int
                fun `fun`(`if`: Int, `when`: (`in`: Int, `my name`: String) -> Unit)
                fun <`class`> `return`(value: `class`): `class`
                fun open(by: Int, get: String)
                fun `with space`()
                fun nested(): KeywordMembersSample.`interface`
                companion object `object` {
                    fun `throw`()
                }
            }
            // from com.xemantic.kotlin.introspection.test
            val KeywordMembersSample<*>.`null`: Int
            fun KeywordMembersSample<*>.`for`()
        """.trimIndent()
    }

    @Test
    fun `should resolve member named like hard keyword by its unescaped name`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.KeywordMembersSample.return"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.KeywordMembersSample.return [page 1/1]
            fun <`class`> `return`(value: `class`): `class`
        """.trimIndent()
    }

    @Test
    fun `should resolve nested class named like hard keyword by its unescaped name`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.KeywordMembersSample.interface"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.KeywordMembersSample.interface [page 1/1]
            class `interface`
        """.trimIndent()
    }

    @Test
    fun `should resolve member named like hard keyword by its escaped name`() {
        // the escaped name is the one rendered, and therefore the one an LLM is likely to ask for
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.KeywordMembersSample.`return`"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.KeywordMembersSample.`return` [page 1/1]
            fun <`class`> `return`(value: `class`): `class`
        """.trimIndent()
    }

    @Test
    fun `should resolve nested class named like hard keyword by its escaped name`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.KeywordMembersSample.`interface`"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.KeywordMembersSample.`interface` [page 1/1]
            class `interface`
        """.trimIndent()
    }

    @Test
    fun `should resolve nested class named with dollar sign by its escaped name`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NestedClassesHolder.`Dollar\$Nested`"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NestedClassesHolder.`Dollar${'$'}Nested` [page 1/1]
            class `Dollar${'$'}Nested`
        """.trimIndent()
    }

    @Test
    fun `should escape enum entry named like hard keyword in annotation default value`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.KeywordDefaultAnnotation"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.KeywordDefaultAnnotation [page 1/1]
            annotation class KeywordDefaultAnnotation(val `if`: KeywordEntryEnum = KeywordEntryEnum.`object`)
        """.trimIndent()
    }

    @Test
    fun `search should find nested class named like hard keyword by its unescaped name`() {
        // the search results are names to resolve, not Kotlin code
        resolver.search("interface", pageSize = 1000) should {
            have("com.xemantic.kotlin.introspection.test.KeywordMembersSample.interface" in lines())
        }
    }

    @Test
    fun `should escape where clause and inner class type named like hard keywords`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.KeywordPositionsSample"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.KeywordPositionsSample [page 1/1]
            class KeywordPositionsSample<`is`> {
                inner class `fun`
                fun <`in`> bounded(value: `in`): `in` where `in` : CharSequence, `in` : Comparable<`in`>
                fun inner(): KeywordPositionsSample<`is`>.`fun`
            }
        """.trimIndent()
    }

    @Test
    fun `should escape sealed subclass inner in other generic class named like hard keywords`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.KeywordSealedSample"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.KeywordSealedSample [page 1/1]
            sealed interface KeywordSealedSample<`is`> {
                inner class KeywordSealedOuter<`is`>.`as` : KeywordSealedSample<`is`>
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve class of package named like hard keyword by its unescaped name`() {
        // the types of the package of the class are referred to by their simple names,
        // while the package to import the extensions from is escaped, like in an import directive
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.in.KeywordPackageSample"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.in.KeywordPackageSample [page 1/1]
            class KeywordPackageSample(val value: Int) {
                class Nested
                fun nested(): KeywordPackageSample.Nested
            }
            // from com.xemantic.kotlin.introspection.test.`in`
            fun KeywordPackageSample.extension(): KeywordPackageSample
        """.trimIndent()
    }

    @Test
    fun `should resolve class of package named like hard keyword by its escaped name`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.`in`.KeywordPackageSample.Nested"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.`in`.KeywordPackageSample.Nested [page 1/1]
            class Nested
        """.trimIndent()
    }

    @Test
    fun `should resolve sealed class of package named like hard keyword`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.in.KeywordPackageSealed"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.in.KeywordPackageSealed [page 1/1]
            sealed interface KeywordPackageSealed {
                class KeywordPackageSealedImpl : KeywordPackageSealed
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve top-level function of package named like hard keyword`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.in.TestKeywordPackageKt.keywordPackageFunction"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.in.TestKeywordPackageKt.keywordPackageFunction [page 1/1]
            // from com.xemantic.kotlin.introspection.test.`in`
            fun keywordPackageFunction(sample: KeywordPackageSample): Int
        """.trimIndent()
    }

    @Test
    fun `should escape package named like hard keyword in type and extension source of other package`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.KeywordPackageClient"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.KeywordPackageClient [page 1/1]
            class KeywordPackageClient {
                fun sample(): com.xemantic.kotlin.introspection.test.`in`.KeywordPackageSample
            }
            // from com.xemantic.kotlin.introspection.test.`in`
            fun KeywordPackageClient.keywordPackageExtension(): com.xemantic.kotlin.introspection.test.`in`.KeywordPackageSample
        """.trimIndent()
    }

    @Test
    fun `search should find class of package named like hard keyword by its unescaped name`() {
        // the search results are names to resolve, not Kotlin code
        resolver.search("KeywordPackageSample") sameAsKotlin """
            // search results for "KeywordPackageSample" [page 1/1]
            com.xemantic.kotlin.introspection.test.in.KeywordPackageSample
        """.trimIndent()
    }

    @Test
    fun `should resolve inner class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NestedClassesHolder.NestedInnerClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NestedClassesHolder.NestedInnerClass [page 1/1]
            inner class NestedInnerClass
        """.trimIndent()
    }

    @Test
    fun `should list nested classes of the companion object`() {
        // an LLM cannot discover them otherwise, while the private and internal ones are not part of the API
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionNestedClassesHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionNestedClassesHolder [page 1/1]
            class CompanionNestedClassesHolder {
                fun answer(): Int
                companion object {
                    class Builder<T : CharSequence> : Iterable<T>
                    interface Factory
                    object Defaults : CompanionNestedClassesHolder.Companion.Factory
                    fun create(): CompanionNestedClassesHolder
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should render body of companion object with only nested classes`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionOnlyNestedClassHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionOnlyNestedClassHolder [page 1/1]
            class CompanionOnlyNestedClassHolder {
                companion object {
                    class Builder
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should not list sealed subclass nested in companion object twice`() {
        // it is already listed as a sealed subclass of the outer class
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionNestedSealedHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionNestedSealedHolder [page 1/1]
            sealed class CompanionNestedSealedHolder {
                class Companion.CompanionSub : CompanionNestedSealedHolder
                class Sub : CompanionNestedSealedHolder
                companion object {
                    class Unrelated
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should list nested classes of named companion object`() {
        // the sealed subclass nested in the companion object is listed only once, qualified with its name
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NamedCompanionNestedHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NamedCompanionNestedHolder [page 1/1]
            sealed class NamedCompanionNestedHolder {
                class Factory.FactorySub : NamedCompanionNestedHolder
                companion object Factory {
                    class Builder
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should list protected nested class of companion object of open class`() {
        // a subclass of the outer class can use it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenCompanionNestedHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenCompanionNestedHolder [page 1/1]
            open class OpenCompanionNestedHolder {
                companion object {
                    protected class ProtectedNested
                    class PublicNested
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should not list protected nested class of companion object of final class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.FinalCompanionNestedHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.FinalCompanionNestedHolder [page 1/1]
            class FinalCompanionNestedHolder {
                companion object {
                    class PublicNested
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should not split companion object with nested classes across pages`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionNestedClassesHolder",
            page = 2,
            pageSize = 1
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionNestedClassesHolder [page 2/2]
            class CompanionNestedClassesHolder {
                companion object {
                    // 4 members, resolve com.xemantic.kotlin.introspection.test.CompanionNestedClassesHolder.Companion to page through them
                    class Builder<T : CharSequence> : Iterable<T>
                    interface Factory
                    object Defaults : CompanionNestedClassesHolder.Companion.Factory
                    fun create(): CompanionNestedClassesHolder
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should list only importable extensions of the receiver type`() {
        // the member extensions of classes, private nested and local classes can only be called
        // within the scope of their dispatch receiver, while the ones of public objects can be imported
        // from the object named in the header
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ExtensionTarget"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ExtensionTarget [page 1/1]
            class ExtensionTarget
            // from com.xemantic.kotlin.introspection.test
            fun ExtensionTarget.topLevelExtension(): String
            // from com.xemantic.kotlin.introspection.test.ExtensionTargetUtils
            fun ExtensionTarget.fromObject(): String
            // from com.xemantic.kotlin.introspection.test.MemberExtensionHolder.Companion
            fun ExtensionTarget.fromCompanion(): String
        """.trimIndent()
    }

    @Test
    fun `should render modifiers of extensions listed in the body of built-in type like top-level ones`() {
        // String.filter is an inline extension function, which keeps its modifiers when listed as a member
        resolver.resolve("kotlin.String", pageSize = 10000) should {
            have("    inline fun filter(predicate: (Char) -> Boolean): String" in lines())
        }
    }

    @Test
    fun `should list extensions of built-in type from object outside of the class body`() {
        // Int.seconds is not a member of Int, it has to be imported from Duration.Companion
        resolver.resolve("kotlin.Int", pageSize = 10000) should {
            have("    val seconds: Duration" !in lines())
            have("// from kotlin.time.Duration.Companion" in lines())
            have("val Int.seconds: Duration" in lines())
        }
    }

    @Test
    fun `should resolve built-in unsigned type as value class`() {
        // the unsigned types have no declarations in the .kotlin_builtins resources, but their own Kotlin metadata,
        // with the primary constructor callable only from the inline functions of the standard library
        resolver.resolve("kotlin.UInt", pageSize = 1) should {
            have(lines()[1] == "@JvmInline value class UInt private constructor(internal val data: Int) : Comparable<UInt> {")
        }
    }

    @Test
    fun `should resolve standard library value class`() {
        resolver.resolve("kotlin.time.Duration", pageSize = 1) should {
            have(lines()[1] == "@JvmInline value class Duration private constructor(private val rawValue: Long) : Comparable<Duration> {")
        }
    }

    @Test
    fun `should list nested classes with type parameters of the outer class and where clause`() {
        // an inner class can refer to the type parameters of its outer class
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder [page 1/1]
            class TypeParametersNestedHolder<K> {
                inner class Cursor : Iterator<K>
                class Sorted<T> where T : Comparable<T>, T : CharSequence
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve inner class with type parameters of the outer class`() {
        // qualified with the outer class declaring them, like an inner class listed in another class
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder.Cursor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder.Cursor [page 1/1]
            inner class TypeParametersNestedHolder<K>.Cursor : Iterator<K> {
                override operator fun hasNext(): Boolean
                override operator fun next(): K
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve member of inner class with type parameters of the outer class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder.Cursor.next"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder.Cursor.next [page 1/1]
            override operator fun next(): K
        """.trimIndent()
    }

    @Test
    fun `should resolve function of inner class of inner class with type parameters of all outer classes`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InnerOfInnerHolder.Mid.Leaf.pair"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InnerOfInnerHolder.Mid.Leaf.pair [page 1/1]
            fun pair(): Pair<A, B>
        """.trimIndent()
    }

    @Test
    fun `should resolve property of inner class of inner class with type parameters of all outer classes`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InnerOfInnerHolder.Mid.Leaf.key"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InnerOfInnerHolder.Mid.Leaf.key [page 1/1]
            override val key: A
        """.trimIndent()
    }

    @Test
    fun `should separate enum entries from nested classes`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NestedClassEnum"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NestedClassEnum [page 1/1]
            enum class NestedClassEnum {
                RED,
                GREEN;
                class Palette
            }
        """.trimIndent()
    }

    @Test
    fun `should list generic nested class with its type parameters`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.GenericNestedHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.GenericNestedHolder [page 1/1]
            class GenericNestedHolder {
                class Entry<K, V> : Map.Entry<K, V>
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve annotation class with default values`() {
        // an LLM needs the actual defaults to know which parameters can be omitted
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.AnnotationDefaultsTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.AnnotationDefaultsTestClass [page 1/1]
            annotation class AnnotationDefaultsTestClass(val times: Int = 3, val label: String = "say \"hi\"", val enabled: Boolean = true, val ratio: Double = 0.5, val big: Long = 10L, val letter: Char = 'k', val level: DeprecationLevel = DeprecationLevel.ERROR, val type: KClass<*> = String::class, val tags: Array<String> = ["a", "b"], val required: String)
        """.trimIndent()
    }

    @Test
    fun `should resolve annotation class with default values referring to nested classes`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NestedClassReferenceAnnotation"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NestedClassReferenceAnnotation [page 1/1]
            annotation class NestedClassReferenceAnnotation(val nested: KClass<*> = NestedClassesHolder.NestedClass::class, val dollar: KClass<*> = NestedClassesHolder.`Dollar${'$'}Nested`::class)
        """.trimIndent()
    }

    @Test
    fun `should resolve class without body members together with its extensions`() {
        // the constructor must be visible even though the class has only extensions
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NoMembersTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NoMembersTestClass [page 1/1]
            class NoMembersTestClass(val value: Int)
            // from com.xemantic.kotlin.introspection.test.ext
            fun NoMembersTestClass.doubled(): Int
        """.trimIndent()
    }

    // TestSupertypeExtensions tests

    @Test
    fun `should resolve class with extensions of all its supertypes`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestSubtype"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestSubtype [page 1/1]
            class TestSubtype(id: String) : TestIntermediateType {
                fun label(): String
            }
            // from com.xemantic.kotlin.introspection.test
            fun TestIntermediateType.describeIntermediate(): String
            fun TestSubtype.describeSubtype(): String
            fun TestSupertype.describeSupertype(): String
        """.trimIndent()
    }

    // TestBoundedReceiverExtensions tests

    @Test
    fun `should resolve class with extensions on type parameters bounded by its supertype`() {
        // the extensions on an unbounded type parameter, like unboundedReceiver(), are not listed for any class
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverOnlyA"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverOnlyA [page 1/1]
            class BoundedReceiverOnlyA : BoundedReceiverA
            // from com.xemantic.kotlin.introspection.test
            val <T : BoundedReceiverA> T.boundedPropertyOfA: Int
            fun <T : BoundedReceiverA> T.boundedByA(): T
            fun <U : BoundedReceiverA, T : U> T.boundedByTypeParameter()
            fun <T : BoundedReceiverA?> T.nullableBoundedByA()
        """.trimIndent()
    }

    @Test
    fun `should resolve interface with extensions on type parameters bounded by it`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverA"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverA [page 1/1]
            interface BoundedReceiverA
            // from com.xemantic.kotlin.introspection.test
            val <T : BoundedReceiverA> T.boundedPropertyOfA: Int
            fun <T : BoundedReceiverA> T.boundedByA(): T
            fun <U : BoundedReceiverA, T : U> T.boundedByTypeParameter()
            fun <T : BoundedReceiverA?> T.nullableBoundedByA()
        """.trimIndent()
    }

    @Test
    fun `should resolve class with extension on type parameter with multiple bounds satisfied by its supertypes once`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverBoth"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverBoth [page 1/1]
            class BoundedReceiverBoth : BoundedReceiverA, BoundedReceiverB
            // from com.xemantic.kotlin.introspection.test
            val <T : BoundedReceiverA> T.boundedPropertyOfA: Int
            fun <T : BoundedReceiverA> T.boundedByA(): T
            fun <T> T.boundedByBoth() where T : BoundedReceiverA, T : BoundedReceiverB
            fun <U : BoundedReceiverA, T : U> T.boundedByTypeParameter()
            fun <T : BoundedReceiverA?> T.nullableBoundedByA()
        """.trimIndent()
    }

    @Test
    fun `should not list extension on type parameter with multiple bounds for interface satisfying only one of them`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverB"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverB [page 1/1]
            interface BoundedReceiverB
        """.trimIndent()
    }

    @Test
    fun `should not resolve extension on type parameter with multiple bounds as member of interface satisfying only one of them`() {
        // like in the listing of BoundedReceiverB
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.BoundedReceiverB.boundedByBoth") == null)
    }

    @Test
    fun `should not resolve extension on type parameter with multiple bounds as member of subtype satisfying only one of them`() {
        // looked up through the supertype BoundedReceiverA, under which it is indexed as well, but not listed
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.BoundedReceiverOnlyA.boundedByBoth") == null)
    }

    @Test
    fun `should resolve extension on type parameter with multiple bounds as member of subtype satisfying them through supertype`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverSubtypeOfAB.boundedByBoth"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverSubtypeOfAB.boundedByBoth [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun <T> T.boundedByBoth() where T : BoundedReceiverA, T : BoundedReceiverB
        """.trimIndent()
    }

    @Test
    fun `should resolve extension on type parameter bounded by supertype as member of subtype`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverOnlyA.boundedByA"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverOnlyA.boundedByA [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun <T : BoundedReceiverA> T.boundedByA(): T
        """.trimIndent()
    }

    @Test
    fun `should resolve built-in type member with stdlib extension on type parameter bounded by its supertype`() {
        // Char declares no coerceAtLeast of its own, like in the listing
        resolver.resolve(
            "kotlin.Char.coerceAtLeast"
        ) sameAsKotlin """
            // kotlin.Char.coerceAtLeast [page 1/1]
            // from kotlin.ranges
            fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T
        """.trimIndent()
    }

    @Test
    fun `should not resolve stdlib extension on type parameter shadowed by more specific overload as member of built-in type`() {
        // the one for any Comparable is never called on an Int, like in the listing
        resolver.resolve(
            "kotlin.Int.coerceAtLeast"
        ) sameAsKotlin """
            // kotlin.Int.coerceAtLeast [page 1/1]
            // from kotlin.ranges
            fun Int.coerceAtLeast(minimumValue: Int): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve stdlib extension on type parameter next to member of other parameter name as member of built-in type`() {
        // 'a'.rangeTo(that = 'z') calls the rangeTo(that: T) for any Comparable, as the member takes `other`
        resolver.resolve(
            "kotlin.Char.rangeTo"
        ) sameAsKotlin """
            // kotlin.Char.rangeTo [page 1/1]
            operator fun rangeTo(other: Char): CharRange
            // from kotlin.ranges
            operator fun <T : Comparable<T>> T.rangeTo(that: T): ClosedRange<T>
        """.trimIndent()
    }

    @Test
    fun `should resolve stdlib extension on type parameter next to members of other parameter name as member of Int`() {
        // 5.rangeTo(that = 6) calls the rangeTo(that: T) for any Comparable
        resolver.resolve("kotlin.Int.rangeTo") should {
            have("operator fun rangeTo(other: Int): IntRange" in lines())
            have("operator fun <T : Comparable<T>> T.rangeTo(that: T): ClosedRange<T>" in lines())
        }
    }

    @Test
    fun `should resolve stdlib extension on type parameter with multiple bounds next to more specific overload`() {
        // R.contains(element: T?) where R : ClosedRange<T>, R : Iterable<T> is not told to be shadowed
        // by IntRange.contains(element: Int?), as T is not the receiver, whose type argument is not inferred
        resolver.resolve("kotlin.ranges.IntRange.contains") should {
            have("inline operator fun IntRange.contains(element: Int?): Boolean" in lines())
            have(lines().count { "R.contains(" in it } == 2)
        }
    }

    @Test
    fun `should resolve built-in type member with stdlib extension on type parameter taking nullable parameters`() {
        // 5.coerceIn(null, 10) calls the one for any Comparable, as Int.coerceIn(minimumValue: Int, maximumValue: Int) takes no null
        resolver.resolve("kotlin.Int.coerceIn") should {
            have("fun Int.coerceIn(minimumValue: Int, maximumValue: Int): Int" in lines())
            have("fun <T : Comparable<T>> T.coerceIn(minimumValue: T?, maximumValue: T?): T" in lines())
            have(lines().none { "T.coerceIn(range: ClosedRange<T>)" in it })
        }
    }

    @Test
    fun `should resolve class with extension on type parameter next to extension on its supertype`() {
        // BoundedReceiverCircle().describe() calls the one on the type parameter, more specific than the one on BoundedReceiverShape
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverCircle"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverCircle [page 1/1]
            class BoundedReceiverCircle : BoundedReceiverShape, BoundedReceiverNamed
            // from com.xemantic.kotlin.introspection.test
            val BoundedReceiverShape.label: String
            val <T> T.label: String where T : BoundedReceiverShape, T : BoundedReceiverNamed
            fun BoundedReceiverShape.describe(): String
            fun <T> T.describe(): String where T : BoundedReceiverShape, T : BoundedReceiverNamed
        """.trimIndent()
    }

    @Test
    fun `should resolve extension on type parameter next to extension on supertype as member of class`() {
        // like in the listing of BoundedReceiverCircle
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverCircle.describe"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverCircle.describe [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun BoundedReceiverShape.describe(): String
            fun <T> T.describe(): String where T : BoundedReceiverShape, T : BoundedReceiverNamed
        """.trimIndent()
    }

    @Test
    fun `should resolve class with extensions on type parameters next to its overloads`() {
        // T is not pinned to BoundedReceiverShadowing, but can be inferred as BoundedReceiverD, so that,
        // like for BoundedReceiverShadowing().nextToExtension(object : BoundedReceiverD {}), the extension can be called
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverShadowing"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverShadowing [page 1/1]
            class BoundedReceiverShadowing : BoundedReceiverD {
                val nextToProperty: Int
                fun nextToMember(): BoundedReceiverShadowing
                fun nextToSubtypeParameter(value: BoundedReceiverShadowing)
                fun nextToNullableBound(value: BoundedReceiverShadowing)
            }
            // from com.xemantic.kotlin.introspection.test
            val <T : BoundedReceiverD> T.nextToProperty: Int
            fun BoundedReceiverShadowing.nextToExtension(value: BoundedReceiverShadowing): BoundedReceiverShadowing
            fun <T : BoundedReceiverD> T.nextToExtension(value: T): T
            fun <T : BoundedReceiverD> T.nextToMember(): T
            fun <T : BoundedReceiverD?> T.nextToNullableBound(value: T)
            fun <T : BoundedReceiverD> T.nextToSubtypeParameter(value: T)
        """.trimIndent()
    }

    @Test
    fun `should resolve interface with extensions on type parameters bounded by it next to overloads of its subtype`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverD"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverD [page 1/1]
            interface BoundedReceiverD
            // from com.xemantic.kotlin.introspection.test
            val <T : BoundedReceiverD> T.nextToProperty: Int
            fun <T : BoundedReceiverD> T.nextToExtension(value: T): T
            fun <T : BoundedReceiverD> T.nextToMember(): T
            fun <T : BoundedReceiverD?> T.nextToNullableBound(value: T)
            fun <T : BoundedReceiverD> T.nextToSubtypeParameter(value: T)
        """.trimIndent()
    }

    @Test
    fun `should resolve extension on type parameter next to member taking subtype as member of class`() {
        // BoundedReceiverShadowing().nextToSubtypeParameter(object : BoundedReceiverD {}) calls the extension
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverShadowing.nextToSubtypeParameter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverShadowing.nextToSubtypeParameter [page 1/1]
            fun nextToSubtypeParameter(value: BoundedReceiverShadowing)
            // from com.xemantic.kotlin.introspection.test
            fun <T : BoundedReceiverD> T.nextToSubtypeParameter(value: T)
        """.trimIndent()
    }

    @Test
    fun `should resolve extension on type parameter with nullable bound next to member as member of class`() {
        // BoundedReceiverShadowing().nextToNullableBound(null) calls the extension
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverShadowing.nextToNullableBound"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverShadowing.nextToNullableBound [page 1/1]
            fun nextToNullableBound(value: BoundedReceiverShadowing)
            // from com.xemantic.kotlin.introspection.test
            fun <T : BoundedReceiverD?> T.nextToNullableBound(value: T)
        """.trimIndent()
    }

    @Test
    fun `should resolve extension property on type parameter next to member property as member of class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverShadowing.nextToProperty"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverShadowing.nextToProperty [page 1/1]
            val nextToProperty: Int
            // from com.xemantic.kotlin.introspection.test
            val <T : BoundedReceiverD> T.nextToProperty: Int
        """.trimIndent()
    }

    @Test
    fun `should not resolve stdlib extension on Comparable shadowed by member of final class comparable only to itself`() {
        // T : Comparable<T> is pinned to BoundedReceiverVersion, so its member coerceAtMost is called instead
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverVersion.coerceAtMost"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverVersion.coerceAtMost [page 1/1]
            fun coerceAtMost(maximumValue: BoundedReceiverVersion): BoundedReceiverVersion
        """.trimIndent()
    }

    @Test
    fun `should resolve stdlib extension on Comparable next to member of class comparable through its supertype`() {
        // T can be inferred as BoundedReceiverVersionBase, like for patch.coerceAtMost(base)
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverPatchVersion.coerceAtMost"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverPatchVersion.coerceAtMost [page 1/1]
            fun coerceAtMost(maximumValue: BoundedReceiverPatchVersion): BoundedReceiverPatchVersion
            // from kotlin.ranges
            fun <T : Comparable<T>> T.coerceAtMost(maximumValue: T): T
        """.trimIndent()
    }

    @Test
    fun `should resolve built-in type member with stdlib extension on type parameter also bounded by unbounded type parameter`() {
        // C : R is a bound on an unbounded type parameter, satisfied by any type, so indexed only under CharSequence,
        // like in the listing of String
        resolver.resolve("kotlin.String.ifEmpty") should {
            have("inline fun <C, R> C.ifEmpty(defaultValue: () -> R): R where C : CharSequence, C : R" in lines())
        }
    }

    @Test
    fun `should not resolve stdlib extension on type parameter with multiple bounds as member of built-in type satisfying only one of them`() {
        // R.contains(element) where R : ClosedRange<T>, R : Iterable<T> is indexed under Iterable, a supertype of List,
        // but List is not a ClosedRange, so it is not listed, and not resolved either
        resolver.resolve("kotlin.collections.List.contains") should {
            have(lines().any { "fun contains(" in it })
            have(lines().none { "R.contains(" in it })
        }
    }

    @Test
    fun `should resolve class with deprecated extension in context on type parameter bounded by its supertype`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverOnlyC"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverOnlyC [page 1/1]
            class BoundedReceiverOnlyC : BoundedReceiverC
            // from com.xemantic.kotlin.introspection.test
            @Deprecated("Use boundedByA") context(log: Logger) fun <T : BoundedReceiverC> T.deprecatedInContext()
        """.trimIndent()
    }

    @Test
    fun `should resolve deprecated extension in context on type parameter bounded by supertype as member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverOnlyC.deprecatedInContext"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverOnlyC.deprecatedInContext [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            @Deprecated("Use boundedByA") context(log: Logger) fun <T : BoundedReceiverC> T.deprecatedInContext()
        """.trimIndent()
    }

    @Test
    fun `should resolve extension in context on function type as member of its function type class`() {
        // the same under the Kotlin name and the JVM interface name, with the types outside kotlin.* fully qualified,
        // as there is no package of the function type class to refer to them by their simple names from
        listOf("kotlin.Function9", "kotlin.jvm.functions.Function9").forEach { className ->
            resolver.resolve("$className.invokeInContext") sameAsKotlin """
                // $className.invokeInContext [page 1/1]
                // from com.xemantic.kotlin.introspection.test
                context(log: com.xemantic.kotlin.introspection.test.Logger) fun ((Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit).invokeInContext()
            """.trimIndent()
        }
    }

    @Test
    fun `should resolve class with extension on type parameter with multiple bounds satisfied through Java superclass`() {
        // java.io.Closeable is implemented by java.io.FilterInputStream, a Java class without the Kotlin metadata
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverStream"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverStream [page 1/1]
            class BoundedReceiverStream : java.io.FilterInputStream, BoundedReceiverA
            // from com.xemantic.kotlin.introspection.test
            val <T : BoundedReceiverA> T.boundedPropertyOfA: Int
            fun <T : BoundedReceiverA> T.boundedByA(): T
            fun <T> T.boundedByAAndCloseable() where T : BoundedReceiverA, T : java.io.Closeable
            fun <U : BoundedReceiverA, T : U> T.boundedByTypeParameter()
            fun <T : BoundedReceiverA?> T.nullableBoundedByA()
        """.trimIndent()
    }

    @Test
    fun `should resolve extension on type parameter with multiple bounds satisfied through Java superclass as member`() {
        // like in the listing of BoundedReceiverStream
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverStream.boundedByAAndCloseable"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverStream.boundedByAAndCloseable [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun <T> T.boundedByAAndCloseable() where T : BoundedReceiverA, T : java.io.Closeable
        """.trimIndent()
    }

    @Test
    fun `should resolve class with extensions on type parameters bounded by Kotlin interfaces of its Java superclass`() {
        // BoundedReceiverB and BoundedReceiverC are implemented only by the Java class JavaBoundedReceiverBase
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverThroughJava"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverThroughJava [page 1/1]
            class BoundedReceiverThroughJava : com.xemantic.kotlin.introspection.test.other.JavaBoundedReceiverBase, BoundedReceiverA
            // from com.xemantic.kotlin.introspection.test
            val <T : BoundedReceiverA> T.boundedPropertyOfA: Int
            fun <T : BoundedReceiverA> T.boundedByA(): T
            fun <T> T.boundedByBoth() where T : BoundedReceiverA, T : BoundedReceiverB
            fun <U : BoundedReceiverA, T : U> T.boundedByTypeParameter()
            @Deprecated("Use boundedByA") context(log: Logger) fun <T : BoundedReceiverC> T.deprecatedInContext()
            fun <T : BoundedReceiverA?> T.nullableBoundedByA()
        """.trimIndent()
    }

    @Test
    fun `should resolve extension on type parameter bounded by Kotlin interface of Java superclass as member`() {
        // like in the listing of BoundedReceiverThroughJava
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverThroughJava.boundedByBoth"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverThroughJava.boundedByBoth [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun <T> T.boundedByBoth() where T : BoundedReceiverA, T : BoundedReceiverB
        """.trimIndent()
    }

    @Test
    fun `should resolve class implementing function type with the extensions of the function type`() {
        // like kotlin.jvm.functions.Function1, also with the ones of kotlin.Function
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverHandler",
            pageSize = 1000
        ) should {
            have(lines()[1] == "class BoundedReceiverHandler : (String) -> Unit {")
            have("fun <P, R> ((P) -> R).handleAll(events: List<P>): List<R>" in lines())
            have("fun (context(String) () -> Unit).runInContext()" in lines())
            have("fun <R> Function<R>.reflect(): KFunction<R>?" in lines())
            have("fun (() -> Unit).repeat()" !in lines())
        }
    }

    @Test
    fun `should resolve class with the extensions of function type implemented by its Java superclass`() {
        // JavaFunctionBase implements kotlin.jvm.functions.Function0, which is () -> Unit in Kotlin
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverJavaFunction",
            pageSize = 1000
        ) should {
            have("fun (() -> Unit).repeat()" in lines())
            have("fun <R> Function<R>.reflect(): KFunction<R>?" in lines())
        }
    }

    @Test
    fun `should resolve extension of function type as member of class implementing it`() {
        // like in the listing of BoundedReceiverHandler
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverHandler.handleAll"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverHandler.handleAll [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun <P, R> ((P) -> R).handleAll(events: List<P>): List<R>
        """.trimIndent()
    }

    @Test
    fun `should resolve class implementing suspend function type with extension on type parameter bounded by it`() {
        // suspend () -> Unit is stored in the metadata as Function1<Continuation<Unit>, Any?>
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverSuspendHandler",
            pageSize = 1000
        ) should {
            have(lines()[1] == "class BoundedReceiverSuspendHandler : BoundedReceiverA, suspend () -> Unit {")
            have("fun <T> T.boundedByAAndSuspendFunction() where T : BoundedReceiverA, T : suspend () -> Unit" in lines())
            have("fun (suspend () -> Unit).retry()" in lines())
            have("fun <R> Function<R>.reflect(): KFunction<R>?" in lines())
            have("fun (context(String) () -> Unit).runInContext()" !in lines())
        }
    }

    @Test
    fun `should resolve extension on type parameter bounded by suspend function type as member of class implementing it`() {
        // like in the listing of BoundedReceiverSuspendHandler
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverSuspendHandler.boundedByAAndSuspendFunction"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverSuspendHandler.boundedByAAndSuspendFunction [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun <T> T.boundedByAAndSuspendFunction() where T : BoundedReceiverA, T : suspend () -> Unit
        """.trimIndent()
    }

    @Test
    fun `should resolve class comparable to itself with stdlib extensions on Comparable`() {
        // unlike the other generic extensions of the standard library, few and widely used,
        // next to the ones of its own package, but not the ones of another package, like isAtLeast
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverVersion"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverVersion [page 1/1]
            class BoundedReceiverVersion(val major: Int) : Comparable<BoundedReceiverVersion> {
                override operator fun compareTo(other: BoundedReceiverVersion): Int
                fun coerceAtMost(maximumValue: BoundedReceiverVersion): BoundedReceiverVersion
            }
            // from com.xemantic.kotlin.introspection.test
            fun <E : Comparable<in E>> E.isBetween(low: E, high: E): Boolean
            // from kotlin
            inline infix fun <T> Comparable<T>.compareTo(other: T): Int
            // from kotlin.ranges
            fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T
            fun <T : Comparable<T>> T.coerceIn(minimumValue: T?, maximumValue: T?): T
            fun <T : Comparable<T>> T.coerceIn(range: ClosedFloatingPointRange<T>): T
            fun <T : Comparable<T>> T.coerceIn(range: ClosedRange<T>): T
            operator fun <T : Comparable<T>> T.rangeTo(that: T): ClosedRange<T>
            operator fun <T : Comparable<T>> T.rangeUntil(that: T): OpenEndRange<T>
        """.trimIndent()
    }

    @Test
    fun `should resolve stdlib extension on Comparable as member of class comparable to itself`() {
        // like in the listing of BoundedReceiverVersion
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverVersion.coerceAtLeast"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverVersion.coerceAtLeast [page 1/1]
            // from kotlin.ranges
            fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T
        """.trimIndent()
    }

    @Test
    fun `should resolve class comparable to its supertype with stdlib extensions on Comparable`() {
        // T is inferred as BoundedReceiverVersionBase
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverPatchVersion"
        ) should {
            have("fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T" in lines())
        }
    }

    @Test
    fun `should resolve class comparable to another type without stdlib extensions on Comparable`() {
        // BoundedReceiverComparableToOther does not satisfy T : Comparable<T>
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverComparableToOther"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverComparableToOther [page 1/1]
            class BoundedReceiverComparableToOther : Comparable<BoundedReceiverVersion> {
                override operator fun compareTo(other: BoundedReceiverVersion): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve class comparable to itself through F-bounded supertype with stdlib extensions on Comparable`() {
        // BoundedReceiverEntity<BoundedReceiverUser> is Comparable<BoundedReceiverUser>,
        // pinning T : Comparable<T> to the final class, so that its member coerceAtMost is called instead
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverUser",
            pageSize = 1000
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverUser [page 1/1]
            class BoundedReceiverUser : BoundedReceiverEntity<BoundedReceiverUser> {
                override operator fun compareTo(other: BoundedReceiverUser): Int
                fun coerceAtMost(maximumValue: BoundedReceiverUser): BoundedReceiverUser
            }
            // from com.xemantic.kotlin.introspection.test
            fun <E : Comparable<in E>> E.isBetween(low: E, high: E): Boolean
            fun <E : BoundedReceiverEntity<E>> E.save(): E
            // from kotlin
            inline infix fun <T> Comparable<T>.compareTo(other: T): Int
            // from kotlin.ranges
            fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T
            fun <T : Comparable<T>> T.coerceIn(minimumValue: T?, maximumValue: T?): T
            fun <T : Comparable<T>> T.coerceIn(range: ClosedFloatingPointRange<T>): T
            fun <T : Comparable<T>> T.coerceIn(range: ClosedRange<T>): T
            operator fun <T : Comparable<T>> T.rangeTo(that: T): ClosedRange<T>
            operator fun <T : Comparable<T>> T.rangeUntil(that: T): OpenEndRange<T>
        """.trimIndent()
    }

    @Test
    fun `should resolve generic class with extensions on type parameter with bound satisfied by its F-bounded type parameter`() {
        // N : BoundedReceiverTitled<N> stands for the subclass itself, a BoundedReceiverEntity<N> and a Comparable<N>
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverTitled"
        ) should {
            have("fun <E : BoundedReceiverEntity<E>> E.save(): E" in lines())
            have("fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T" in lines())
        }
    }

    @Test
    fun `should resolve Enum with stdlib extensions on type parameter bounded by Comparable`() {
        // E : Enum<E> stands for the enum class itself, like for e.coerceAtLeast(e) of fun <E : Enum<E>> f(e: E)
        resolver.resolve(
            "kotlin.Enum",
            pageSize = 1000
        ) should {
            have("fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T" in lines())
            have("fun <T : Comparable<T>> T.coerceAtMost(maximumValue: T): T" in lines())
            have("fun <T : Comparable<T>> T.coerceIn(minimumValue: T?, maximumValue: T?): T" in lines())
            have("fun <T : Comparable<T>> T.coerceIn(range: ClosedFloatingPointRange<T>): T" in lines())
            have("fun <T : Comparable<T>> T.coerceIn(range: ClosedRange<T>): T" in lines())
            have("operator fun <T : Comparable<T>> T.rangeTo(that: T): ClosedRange<T>" in lines())
            have("operator fun <T : Comparable<T>> T.rangeUntil(that: T): OpenEndRange<T>" in lines())
        }
    }

    @Test
    fun `should resolve class without extension on contravariant supertype of type argument Any`() {
        // a BoundedReceiverSink<Number> is a BoundedReceiverSink<Int>, but not a BoundedReceiverSink<Any>
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverNumberSink"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverNumberSink [page 1/1]
            class BoundedReceiverNumberSink : BoundedReceiverSink<Number>
            // from com.xemantic.kotlin.introspection.test
            fun BoundedReceiverSink<Int>.sinkInt()
            fun BoundedReceiverSink<Nothing>.sinkNothing()
        """.trimIndent()
    }

    @Test
    fun `should resolve class without extension on covariant supertype of other type argument than Java class`() {
        // java.io.File, without the Kotlin metadata, is not a String, but a java.io.Serializable
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverFiles"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverFiles [page 1/1]
            class BoundedReceiverFiles : BoundedReceiverSource<java.io.File>
            // from com.xemantic.kotlin.introspection.test
            fun BoundedReceiverSource<java.io.Serializable>.firstSerializable()
        """.trimIndent()
    }

    @Test
    fun `should not resolve extension on Comparable of other type argument as member of class comparable to itself`() {
        // fun Comparable<Int>.comparedToZero() can neither be called on BoundedReceiverVersion, nor is of the standard library
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.BoundedReceiverVersion.comparedToZero") == null)
    }

    @Test
    fun `should resolve class with extensions on type parameter with self-referential bound satisfied by its supertype`() {
        // E is inferred as BoundedReceiverMember, a BoundedReceiverEntity<BoundedReceiverMember>, and T as well,
        // while BoundedReceiverAdmin, a Comparable<BoundedReceiverMember>, is not a BoundedReceiverEntity<BoundedReceiverAdmin>
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverAdmin"
        ) should {
            have("fun <E : BoundedReceiverEntity<E>> E.save(): E" in lines())
            have("fun <T : Comparable<T>> T.coerceAtMost(maximumValue: T): T" in lines())
        }
    }

    @Test
    fun `should resolve class comparable to itself through generic interface with stdlib extensions on Comparable`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverRank"
        ) should {
            have("fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T" in lines())
        }
    }

    @Test
    fun `should not resolve extension on Comparable of other package as member of class comparable to itself`() {
        // like in the listing of BoundedReceiverVersion, listing only the ones of the standard library, and of its package
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.BoundedReceiverVersion.isAtLeast") == null)
    }

    @Test
    fun `should resolve class with extension on supertype of Java type argument of Kotlin class mapped to Java class`() {
        // java.io.IOException is a java.io.Serializable through kotlin.Throwable, mapped to java.lang.Throwable
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverIoErrors"
        ) should {
            have("fun BoundedReceiverSource<java.io.Serializable>.firstSerializable()" in lines())
        }
    }

    @Test
    fun `should resolve class with extension on JVM supertype of built-in type argument`() {
        // String is java.io.Serializable on the JVM, unlike told by the built-in declarations,
        // but not a java.lang.constant.Constable, as Kotlin does not see it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverStrings"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverStrings [page 1/1]
            class BoundedReceiverStrings : BoundedReceiverSource<String>
            // from com.xemantic.kotlin.introspection.test
            fun BoundedReceiverSource<java.io.Serializable>.firstSerializable()
            fun BoundedReceiverSource<String>.firstString(): String
        """.trimIndent()
    }

    @Test
    fun `should resolve class with extensions on JVM supertypes of array type argument`() {
        // Array is also Cloneable on the JVM
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverArrays"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverArrays [page 1/1]
            class BoundedReceiverArrays : BoundedReceiverSource<Array<String>>
            // from com.xemantic.kotlin.introspection.test
            fun BoundedReceiverSource<Cloneable>.firstCloneable()
            fun BoundedReceiverSource<java.io.Serializable>.firstSerializable()
        """.trimIndent()
    }

    @Test
    fun `should resolve class without extension on JVM supertype of built-in type argument which Kotlin does not see`() {
        // java.lang.Integer is a java.lang.constant.Constable, not seen by Kotlin
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverInts"
        ) should {
            have("fun BoundedReceiverSource<Number>.firstNumber(): Number" in lines())
            have(lines().none { "firstConstable" in it })
        }
    }

    @Test
    fun `should resolve class without extension on mutable JVM supertype of read-only built-in type argument`() {
        // java.util.List is a java.util.Collection, seen as MutableCollection, unlike the read-only List
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverLists"
        ) should {
            have(lines().none { "addRow" in it })
        }
    }

    @Test
    fun `should resolve object of Nothing type argument with extensions on supertypes of any type argument`() {
        // Nothing is a subtype of any type, like of String, for the BoundedReceiverSource<out T>
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverNone"
        ) should {
            have("fun BoundedReceiverSource<String>.firstString(): String" in lines())
            have("fun BoundedReceiverSource<Number>.firstNumber(): Number" in lines())
        }
    }

    @Test
    fun `should resolve class with extension on contravariant supertype of Nothing type argument`() {
        // a BoundedReceiverSink<Int> is a BoundedReceiverSink<Nothing>
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverIntSink"
        ) should {
            have("fun BoundedReceiverSink<Nothing>.sinkNothing()" in lines())
        }
    }

    @Test
    fun `should resolve class with extension on contravariant supertype of built-in type argument of JVM supertype`() {
        // a BoundedReceiverSink<java.io.Serializable> is a BoundedReceiverSink<Int>, but not a BoundedReceiverSink<Any>
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverSerializableSink"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverSerializableSink [page 1/1]
            class BoundedReceiverSerializableSink : BoundedReceiverSink<java.io.Serializable>
            // from com.xemantic.kotlin.introspection.test
            fun BoundedReceiverSink<Int>.sinkInt()
            fun BoundedReceiverSink<Nothing>.sinkNothing()
        """.trimIndent()
    }

    @Test
    fun `should resolve class without extensions on supertypes of other type arguments than Java interface`() {
        // java.lang.Runnable, a Java interface without superinterfaces, is neither a String, nor a java.io.Serializable
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverRunnables"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverRunnables [page 1/1]
            class BoundedReceiverRunnables : BoundedReceiverSource<java.lang.Runnable>
        """.trimIndent()
    }

    @Test
    fun `should resolve generic class without stdlib extensions on Comparable of type parameter bounded by class of other type arguments`() {
        // T : BoundedReceiverStarred<String, T> stands for a subclass of other type arguments than BoundedReceiverStarred<X, T>
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverStarred"
        ) should {
            have(lines().none { "coerceAtLeast" in it })
        }
    }

    @Test
    fun `should resolve class without extensions on type parameter pinned by self-referential bound shadowed by its members`() {
        // N : BoundedReceiverNode<N> is pinned to the final class, so that its members linkTo and linkSuspending
        // are called instead, unlike the other ones, see BoundedReceiverLeaf
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverLeaf"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverLeaf [page 1/1]
            class BoundedReceiverLeaf : BoundedReceiverNode<BoundedReceiverLeaf> {
                fun linkTo(next: BoundedReceiverLeaf): BoundedReceiverLeaf
                fun linkAll(nodes: BoundedReceiverLeaf): BoundedReceiverLeaf
                fun linkOrSelf(next: BoundedReceiverLeaf?): BoundedReceiverLeaf
                context(log: Logger) fun linkInContext(next: BoundedReceiverLeaf): BoundedReceiverLeaf
                suspend fun linkSuspending(next: BoundedReceiverLeaf): BoundedReceiverLeaf
                fun linkMany(vararg next: BoundedReceiverLeaf): BoundedReceiverLeaf
            }
            // from com.xemantic.kotlin.introspection.test
            fun <N : BoundedReceiverNode<N>> N.linkAll(vararg nodes: N): N
            fun <N : BoundedReceiverNode<N>> N.linkInContext(next: N): N
            fun <N : BoundedReceiverNode<N>> N.linkMany(next: N): N
            fun <N : BoundedReceiverNode<N>> N.linkOrSelf(next: N? = …): N
        """.trimIndent()
    }

    @Test
    fun `should not resolve extension on type parameter pinned by self-referential bound shadowed by suspend member`() {
        // the member is called also outside a coroutine, failing to compile then
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverLeaf.linkSuspending"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverLeaf.linkSuspending [page 1/1]
            suspend fun linkSuspending(next: BoundedReceiverLeaf): BoundedReceiverLeaf
        """.trimIndent()
    }

    @Test
    fun `should not resolve extension on type parameter pinned by self-referential bound shadowed by inherited member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverTwig.linkTo"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverTwig.linkTo [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.BoundedReceiverTwigBase
            fun linkTo(next: BoundedReceiverTwig): BoundedReceiverTwig
        """.trimIndent()
    }

    @Test
    fun `should resolve class with extensions on supertypes of matching type arguments`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverIntBox"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverIntBox [page 1/1]
            class BoundedReceiverIntBox : BoundedReceiverBox<Int>, BoundedReceiverSource<Int>
            // from com.xemantic.kotlin.introspection.test
            fun <T> BoundedReceiverBox<T>.anyBox()
            fun BoundedReceiverSource<Number>.firstNumber(): Number
            fun BoundedReceiverSource<java.io.Serializable>.firstSerializable()
            fun <T, B> B.sameElements() where B : BoundedReceiverBox<T>, B : BoundedReceiverSource<T>
            fun BoundedReceiverBox<Int>.sumOfInts(): Int
            fun BoundedReceiverBox<out Number>.sumOfNumbers(): Double
        """.trimIndent()
    }

    @Test
    fun `should resolve class without extensions on supertypes of other type arguments`() {
        // B : BoundedReceiverSource<T> needs T = String, of which BoundedReceiverSource is the only one,
        // while B : BoundedReceiverBox<T> needs T = Int
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverMixedBox"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverMixedBox [page 1/1]
            class BoundedReceiverMixedBox : BoundedReceiverBox<Int>, BoundedReceiverSource<String>
            // from com.xemantic.kotlin.introspection.test
            fun <T> BoundedReceiverBox<T>.anyBox()
            fun BoundedReceiverSource<java.io.Serializable>.firstSerializable()
            fun BoundedReceiverSource<String>.firstString(): String
            fun BoundedReceiverBox<Int>.sumOfInts(): Int
            fun BoundedReceiverBox<out Number>.sumOfNumbers(): Double
        """.trimIndent()
    }

    @Test
    fun `should resolve generic interface with extensions on it of any type arguments`() {
        // the type parameter T of BoundedReceiverBox stands for any type argument
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverBox"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverBox [page 1/1]
            interface BoundedReceiverBox<T>
            // from com.xemantic.kotlin.introspection.test
            fun <T> BoundedReceiverBox<T>.anyBox()
            fun BoundedReceiverBox<String>.joinStrings(): String
            fun BoundedReceiverBox<Int>.sumOfInts(): Int
            fun BoundedReceiverBox<out Number>.sumOfNumbers(): Double
        """.trimIndent()
    }

    @Test
    fun `should resolve class comparable to itself with extension on type parameter bounded by Comparable and its supertype`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverComparableE"
        ) should {
            have("fun <T> T.comparableE() where T : Comparable<T>, T : BoundedReceiverE" in lines())
        }
    }

    @Test
    fun `should resolve class comparable to another type without extension on type parameter bounded by Comparable`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverComparableToOtherE"
        ) should {
            have(lines().none { "comparableE" in it })
        }
    }

    @Test
    fun `should resolve class with stdlib extension on type parameter with multiple bounds of the standard library satisfied by it`() {
        // the generic extensions on a single bound of the standard library are not listed for a user class,
        // unlike the ones on multiple bounds satisfied by the class, which are not generic in the same way
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverRange",
            pageSize = 1000
        ) should {
            have(lines().count { "R.contains(" in it } == 1)
            have(lines().none { "Iterable<T>." in it })
        }
    }

    @Test
    fun `should resolve stdlib extension on type parameter with multiple bounds of the standard library as member of class satisfying them`() {
        // like in the listing of BoundedReceiverRange, after the member contains(value: Int) inherited from ClosedRange<Int>,
        // whose body makes it open, like within the interface
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverRange.contains"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverRange.contains [page 1/1]
            // inherited from kotlin.ranges.ClosedRange
            open operator fun contains(value: Int): Boolean
            // from kotlin.ranges
            inline operator fun <T : Comparable<T>, R> R.contains(element: T?): Boolean where R : ClosedRange<T>, R : Iterable<T>
        """.trimIndent()
    }

    @Test
    fun `should resolve extension on type parameter bounded by interface as its member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.BoundedReceiverA.boundedByA"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.BoundedReceiverA.boundedByA [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun <T : BoundedReceiverA> T.boundedByA(): T
        """.trimIndent()
    }

    @Test
    fun `should resolve built-in type with stdlib extensions on type parameters bounded by its supertype`() {
        // Char declares no coerceAtLeast of its own, while its member rangeTo takes `other`, not `that`
        resolver.resolve(
            "kotlin.Char",
            pageSize = 10000
        ) should {
            have("fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T" in lines())
            have("    operator fun rangeTo(other: Char): CharRange" in lines())
            have("operator fun <T : Comparable<T>> T.rangeTo(that: T): ClosedRange<T>" in lines())
            have(lines().none { "T.also(" in it || "T.let(" in it })
        }
    }

    @Test
    fun `should resolve built-in type without stdlib extensions on type parameters shadowed by more specific overloads`() {
        // coerceAtLeast is declared both for Int, shown in the class body, and for any Comparable,
        // whose T is pinned to Int, final and comparable only to itself, called only with an explicit type argument
        resolver.resolve(
            "kotlin.Int",
            pageSize = 10000
        ) should {
            have("    fun coerceAtLeast(minimumValue: Int): Int" in lines())
            have(lines().none { "T.coerceAtLeast(" in it })
            have("operator fun <T : Comparable<T>> T.rangeTo(that: T): ClosedRange<T>" in lines())
            // not on a type parameter, and the member compareTo is not infix anyway
            have("inline infix fun <T> Comparable<T>.compareTo(other: T): Int" in lines())
        }
    }

    @Test
    fun `should resolve built-in type with stdlib extensions on type parameters bounded by it outside of its body`() {
        // the receiver type parameter cannot be omitted, as the class body would make of it
        resolver.resolve(
            "kotlin.Comparable",
            pageSize = 10000
        ) should {
            have("fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T" in lines())
            have(lines().none { "coerceAtLeast" in it && it.startsWith("    ") })
        }
    }

    @Test
    fun `should resolve String with stdlib extensions on type parameters with multiple bounds`() {
        // C : R is a bound on an unbounded type parameter, satisfied by any type
        resolver.resolve(
            "kotlin.String",
            pageSize = 10000
        ) should {
            have("inline fun <C, R> C.ifEmpty(defaultValue: () -> R): R where C : CharSequence, C : R" in lines())
        }
    }

    @Test
    fun `should resolve extension of supertype as class member`() {
        // listed with the class, so it can be called on it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestSubtype.describeSupertype"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestSubtype.describeSupertype [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun TestSupertype.describeSupertype(): String
        """.trimIndent()
    }

    @Test
    fun `should resolve extension of intermediate supertype as class member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestSubtype.describeIntermediate"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestSubtype.describeIntermediate [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun TestIntermediateType.describeIntermediate(): String
        """.trimIndent()
    }

    @Test
    fun `should not resolve extension of subtype as member of its supertype`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.TestIntermediateType.describeSubtype") == null)
    }

    @Test
    fun `should resolve class with extensions sorted by name regardless of declaration and scan order`() {
        // the extensions are spread over 2 files, in each declared in reverse order,
        // and the order in which files are scanned depends on the file system;
        // same-named extensions are sorted by the declaring file, also across the class and its supertype
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ExtensionOrderSubject"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ExtensionOrderSubject [page 1/1]
            class ExtensionOrderSubject(val value: Int) : ExtensionOrderSupertype
            // from com.xemantic.kotlin.introspection.test
            val ExtensionOrderSubject.bravo: Int
            val ExtensionOrderSubject.charlie: Int
            val ExtensionOrderSubject.yankee: Int
            fun ExtensionOrderSupertype.alpha(): Int
            fun ExtensionOrderSubject.alpha(): Int
            fun ExtensionOrderSubject.foxtrot(factor: Int): Int
            fun ExtensionOrderSubject.foxtrot(): Int
            fun ExtensionOrderSubject.mike(): Int
            fun ExtensionOrderSubject.zulu(): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve same-named extensions as class member sorted by declaring file regardless of scan order`() {
        // the overloads are declared in 2 files, scanned in the order depending on the file system
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ExtensionOrderSubject.foxtrot"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ExtensionOrderSubject.foxtrot [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun ExtensionOrderSubject.foxtrot(factor: Int): Int
            fun ExtensionOrderSubject.foxtrot(): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve class with extensions from the default package under the facade class name`() {
        // the default package has no name, so the header names the file facade class instead
        resolver.resolve(
            "DefaultPackageSubject"
        ) sameAsKotlin """
            // DefaultPackageSubject [page 1/1]
            class DefaultPackageSubject
            // from DefaultPackageExtensionsKt
            fun DefaultPackageSubject.greet(): String
        """.trimIndent()
    }

    @Test
    fun `should resolve function of file facade in the default package under the facade class name`() {
        // like in the listing of the receiver, see above
        resolver.resolve(
            "DefaultPackageExtensionsKt.greet"
        ) sameAsKotlin """
            // DefaultPackageExtensionsKt.greet [page 1/1]
            // from DefaultPackageExtensionsKt
            fun DefaultPackageSubject.greet(): String
        """.trimIndent()
    }

    @Test
    fun `should not list extensions on a type parameter receiver for a user class named TypeParameter`() {
        // fun <T> T.let(), T.apply(), … are not extensions of a class named TypeParameter
        resolver.resolve(
            "TypeParameter"
        ) sameAsKotlin """
            // TypeParameter [page 1/1]
            class TypeParameter
        """.trimIndent()
    }

    @Test
    fun `should not resolve extensions of a kotlin type for a user class of a matching name`() {
        // collections.Iterator must not get the extensions of kotlin.collections.Iterator
        resolver.resolve(
            "collections.Iterator"
        ) sameAsKotlin """
            // collections.Iterator [page 1/1]
            class Iterator(val value: Int)
        """.trimIndent()
    }

    // TestGenerics tests

    @Test
    fun `should resolve generic class with type parameters of class and functions`() {
        // distinct type parameter names, bounds, variance and reified are preserved
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.GenericTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.GenericTestClass [page 1/1]
            class GenericTestClass<K, out V>(val key: K, val value: V) {
                fun <R : Any> transform(block: (K, V) -> R): R
                fun withKey(newKey: K): GenericTestClass<K, V>
            }
            // from com.xemantic.kotlin.introspection.test
            fun <T> GenericTestClass<T, T>.longest(): T where T : CharSequence, T : Comparable<T>
            fun <K, V> GenericTestClass<K, V>.swap(): GenericTestClass<V, K>
            inline fun <reified T> GenericTestClass<*, *>.valueAs(): T?
        """.trimIndent()
    }

    @Test
    fun `should resolve class member referring to a type of the same package by its simple name`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.GenericTestClass.withKey"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.GenericTestClass.withKey [page 1/1]
            fun withKey(newKey: K): GenericTestClass<K, V>
        """.trimIndent()
    }

    @Test
    fun `should resolve extension referring to types of the same package by their simple names as class member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.GenericTestClass.swap"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.GenericTestClass.swap [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun <K, V> GenericTestClass<K, V>.swap(): GenericTestClass<V, K>
        """.trimIndent()
    }

    @Test
    fun `should render type arguments of outer class of inner class type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.GenericInnerTypes"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.GenericInnerTypes [page 1/1]
            class GenericInnerTypes(val cursor: GenericInnerOuter<String>.Inner<Int>) {
                fun cursor(): GenericInnerOuter<String>.Inner<Int>
                fun nullableCursor(): GenericInnerOuter<String>.Inner<Int>?
                fun accept(cursor: GenericInnerOuter<Long>.Inner<GenericInnerOuter<Int>.Inner<String>>)
            }
        """.trimIndent()
    }

    @Test
    fun `should render inner class type of non-generic outer class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NonGenericInnerTypes"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NonGenericInnerTypes [page 1/1]
            class NonGenericInnerTypes {
                fun cursor(): NonGenericInnerOuter.In<String>
                fun accept(cursor: NonGenericInnerOuter.In<String>?)
            }
        """.trimIndent()
    }

    @Test
    fun `should render type arguments of outer class of Java inner class supertype`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaGenericOuterString.Sub"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaGenericOuterString.Sub [page 1/1]
            inner class Sub : com.xemantic.kotlin.introspection.test.other.JavaGenericOuter<String>.Inner<Int>
        """.trimIndent()
    }

    // TestExtensionVisibility tests

    @Test
    fun `should list only public extensions of a class`() {
        // private and internal extensions cannot be called from the user code,
        // neither can @PublishedApi internal ones, which are meant only for public inline functions
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ExtensionVisibilityTarget"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ExtensionVisibilityTarget [page 1/1]
            class ExtensionVisibilityTarget(val value: Int)
            // from com.xemantic.kotlin.introspection.test
            val ExtensionVisibilityTarget.publicCache: Int
            fun ExtensionVisibilityTarget.helper(): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve only public extension overloads as class member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ExtensionVisibilityTarget.helper"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ExtensionVisibilityTarget.helper [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun ExtensionVisibilityTarget.helper(): Int
        """.trimIndent()
    }

    @Test
    fun `should not resolve non-public extension property as class member`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.ExtensionVisibilityTarget.internalCache") == null)
    }

    @Test
    fun `should not resolve non-public extension of built-in type as its member`() {
        assert(resolver.resolve("kotlin.String.internalStringHelper") == null)
    }

    @Test
    fun `should resolve Java-only member of built-in type with synthetic parameter names`() {
        // the JDK is compiled without parameter names, which Kotlin does not let a call to a Java method name anyway
        assumeNoParameterNames(Throwable::class.java.getMethod("initCause", Throwable::class.java))
        resolver.resolve("kotlin.Throwable.initCause") sameAsKotlin """
            // kotlin.Throwable.initCause [page 1/1]
            open fun initCause(arg0: Throwable?): Throwable
        """.trimIndent()
    }

    @Test
    fun `should not resolve JVM-only member of built-in type which the compiler cannot call`() {
        // kotlin-reflect tells the JDK members newer than the ones mapped to kotlin.String, like String.indent,
        // deprecated as "not fully supported by Kotlin compiler", which K2 does not resolve at all
        assert(resolver.resolve("kotlin.String.indent") == null)
        assert(resolver.resolve("kotlin.String.strip") == null)
        assert(resolver.resolve("kotlin.CharSequence.getChars") == null)
    }

    @Test
    fun `should list no JVM-only member of built-in type which the compiler cannot call`() {
        // when
        val listings = DefaultKotlinIntrospector.builtinTypes.keys.associateWith { type ->
            resolver.resolve(type, pageSize = 100_000).orEmpty()
        }

        // then
        val uncallable = listings.filterValues { "This member is not fully supported by Kotlin compiler" in it }
        assert(uncallable.keys.isEmpty())
    }

    @Test
    fun `should list Java-only members of built-in type with synthetic parameter names`() {
        assumeNoParameterNames(Throwable::class.java.getMethod("initCause", Throwable::class.java))
        resolver.resolve("kotlin.Throwable", pageSize = 10000) should {
            have("    open fun initCause(arg0: Throwable?): Throwable" in lines())
            have("    open fun printStackTrace(arg0: java.io.PrintStream?)" in lines())
            have("    open fun printStackTrace(arg0: java.io.PrintWriter?)" in lines())
        }
    }

    @Test
    fun `should list Java-only member of built-in type next to extension hiding the members of another signature`() {
        // the @HidesMembers Iterable.forEach is called for a lambda, the member forEach of java.lang.Iterable for a Consumer
        assumeNoParameterNames(Iterable::class.java.getMethod("forEach", java.util.function.Consumer::class.java))
        resolver.resolve("kotlin.collections.Iterable", pageSize = 10000) should {
            have("    inline fun forEach(action: (T) -> Unit)" in lines())
            have("    open fun forEach(arg0: java.util.function.Consumer<in T>?)" in lines())
        }
    }

    @Test
    fun `should list extension of built-in type hiding Java-only member only for some arguments next to it in its body`() {
        // the @HidesMembers Throwable.addSuppressed is called instead of the member addSuppressed of java.lang.Throwable,
        // but for addSuppressed(null), which only the member, taking the platform type Throwable!, accepts
        assumeNoParameterNames(Throwable::class.java.getMethod("addSuppressed", Throwable::class.java))
        resolver.resolve("kotlin.Throwable", pageSize = 10000) should {
            have("    fun addSuppressed(exception: Throwable)" in lines())
            have("    fun addSuppressed(arg0: Throwable?)" in lines())
            have(lines().none { "Throwable.addSuppressed" in it })
        }
    }

    @Test
    fun `should resolve Java-only member of built-in type next to extension hiding it only for some arguments`() {
        assumeNoParameterNames(Throwable::class.java.getMethod("addSuppressed", Throwable::class.java))
        resolver.resolve("kotlin.Throwable.addSuppressed") sameAsKotlin """
            // kotlin.Throwable.addSuppressed [page 1/1]
            fun addSuppressed(arg0: Throwable?)
            // from kotlin
            fun Throwable.addSuppressed(exception: Throwable)
        """.trimIndent()
    }

    @Test
    fun `should resolve only the members of built-in type which its listing shows`() {
        // given - the names of the members of the JVM class, like String.indent, too
        val memberNames = DefaultKotlinIntrospector.builtinTypes.mapValues { (_, kClass) ->
            kClass.members.mapTo(sortedSetOf()) { it.name }
        }

        // the listing of each type, as the trimmed lines, and the class header, the first line declaring the class,
        // telling the properties declared in the primary constructor, like `open val cause: Throwable?` of Throwable
        class Listing(val lines: Set<String>, val header: String) {
            fun shows(line: String) = line in lines || "($line, " in header || ", $line)" in header || "($line)" in header
        }
        val listings = HashMap<String, Listing>()
        fun listingOf(type: String) = listings.getOrPut(type) {
            val lines = resolver.resolve(type, pageSize = 100_000).orEmpty().lines().drop(1)
            Listing(lines.mapTo(HashSet()) { it.trim() }, lines.firstOrNull { !it.startsWith("//") }.orEmpty())
        }
        // an inherited member of a class which cannot be subclassed is rendered without the open modifier,
        // following its annotations, like the @Deprecated of a JDK member
        fun withoutModality(line: String) = line.replaceFirst(Regex("^((?:@.*\\) )?)open "), "$1")
        // an inherited member is rendered with the type arguments which the inheriting type gives
        // for the type parameters of its supertype substituted, like `fun forEach(arg0: Consumer<in UByte>?)`
        // of `fun forEach(arg0: Consumer<in T>?)` of `Iterable<T>`, so that they are substituted in the listing line too,
        // as told by kotlin-reflect, and named like in the listings, without their `kotlin.*` packages
        fun substituted(line: String, type: String, supertype: String): String {
            val superClass = DefaultKotlinIntrospector.builtinTypes[supertype] ?: return line
            val inherited = DefaultKotlinIntrospector.builtinTypes[type]?.allSupertypes
                ?.firstOrNull { it.classifier == superClass }
                ?: return line
            val arguments = superClass.typeParameters.zip(inherited.arguments).associate { (typeParameter, argument) ->
                typeParameter.name to (argument.type?.toString() ?: "*")
                    .replace(Regex("\\bkotlin\\.(?:[a-z]\\w*\\.)*"), "")
                    .replace("!", "")
            }
            return line.replace(Regex("\\b\\w+\\b")) { arguments[it.value] ?: it.value }
        }

        // when
        val unlisted = memberNames.flatMap { (type, names) ->
            names.flatMap { name ->
                // the members and the companion object members, followed by the extensions under their `// from` headers,
                // which a built-in type can list in its body without their receivers,
                // and the members inherited from the supertypes, each under its `// inherited from` header,
                // which the listing of that supertype shows
                var listedType = type
                resolver.resolve("$type.$name", pageSize = 100_000)?.lines().orEmpty()
                    .drop(1)
                    .takeWhile { !it.startsWith("// from ") }
                    .map { it.trim() }
                    .mapNotNull { line ->
                        if (line.startsWith("// inherited from ")) listedType = line.removePrefix("// inherited from ")
                        // the companion object members, following the inherited ones, are listed by the type itself
                        if (line == "companion object {") listedType = type
                        (listedType to line).takeIf { !line.startsWith("//") && line != "companion object {" && line != "}" }
                    }
                    .filter { (listedType, line) ->
                        val listing = listingOf(listedType)
                        !listing.shows(line) && listing.lines.none {
                            withoutModality(substituted(it, type, listedType)) == withoutModality(line)
                        }
                    }
                    .map { (_, line) -> "$type.$name: $line" }
            }
        }

        // then
        assert(unlisted.isEmpty())
    }

    @Test
    fun `should resolve only public function overloads through file facade`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestExtensionVisibilityKt.helper"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestExtensionVisibilityKt.helper [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun ExtensionVisibilityTarget.helper(): Int
        """.trimIndent()
    }

    @Test
    fun `should not resolve private property through file facade`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.TestExtensionVisibilityKt.privateCache") == null)
    }

    @Test
    fun `should not resolve internal property through file facade`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.TestExtensionVisibilityKt.internalCache") == null)
    }

    @Test
    fun `should not resolve internal function through file facade`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.TestExtensionVisibilityKt.useVisibilityTestExtensions") == null)
    }

    // TestMemberVisibility tests

    @Test
    fun `should list protected members of open class`() {
        // a subclass can use them, unlike the private and internal ones
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenVisibilityHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenVisibilityHolder [page 1/1]
            open class OpenVisibilityHolder {
                protected class NestedProtected
                val publicProperty: String
                protected val protectedProperty: String
                fun publicFunction(): String
                protected fun protectedFunction(): String
                fun usePrivate(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve private member`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.OpenVisibilityHolder.privateFunction") == null)
    }

    @Test
    fun `should not resolve internal member`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.OpenVisibilityHolder.internalProperty") == null)
    }

    @Test
    fun `should not resolve protected member of final class`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.VisibilityTestClass.protectedFunction") == null)
    }

    @Test
    fun `should resolve protected member of open class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenVisibilityHolder.protectedFunction"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenVisibilityHolder.protectedFunction [page 1/1]
            protected fun protectedFunction(): String
        """.trimIndent()
    }

    @Test
    fun `should search protected nested class of open class`() {
        resolver.search("NestedProtected") sameAsKotlin """
            // search results for "NestedProtected" [page 1/1]
            com.xemantic.kotlin.introspection.test.OpenVisibilityHolder.NestedProtected
        """.trimIndent()
    }

    @Test
    fun `should render properties of private constructor in class body`() {
        // the constructor cannot be called, while its properties are still public
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PrivateConstructorHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PrivateConstructorHolder [page 1/1]
            class PrivateConstructorHolder private constructor() {
                val id: Int
                val label: String
                companion object {
                    fun create(id: Int): PrivateConstructorHolder
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should render properties of internal constructor in class body`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InternalConstructorHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InternalConstructorHolder [page 1/1]
            class InternalConstructorHolder private constructor() {
                val id: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should render protected constructor of abstract class`() {
        // a subclass can call it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedConstructorHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedConstructorHolder [page 1/1]
            abstract class ProtectedConstructorHolder protected constructor(val id: Int)
        """.trimIndent()
    }

    @Test
    fun `should mark class with only internal secondary constructor as not constructible`() {
        // a bare class header would read as having the implicit public constructor without parameters
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InternalSecondaryConstructorOnly"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InternalSecondaryConstructorOnly [page 1/1]
            class InternalSecondaryConstructorOnly private constructor() {
                val x: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should mark abstract class with internal constructor as not subclassable`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InternalConstructorAbstractClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InternalConstructorAbstractClass [page 1/1]
            abstract class InternalConstructorAbstractClass private constructor() {
                abstract fun f()
            }
        """.trimIndent()
    }

    @Test
    fun `should mark open class with private constructor as not constructible`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PrivateConstructorOpenClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PrivateConstructorOpenClass [page 1/1]
            open class PrivateConstructorOpenClass private constructor() {
                companion object {
                    val instance: PrivateConstructorOpenClass
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should mark final class with protected constructor as not constructible`() {
        // no subclass can call it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedConstructorFinalClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedConstructorFinalClass [page 1/1]
            class ProtectedConstructorFinalClass private constructor()
        """.trimIndent()
    }

    @Test
    fun `should mark inner class with private constructor as not constructible`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InnerPrivateConstructorOuter.Inner"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InnerPrivateConstructorOuter.Inner [page 1/1]
            inner class Inner private constructor()
        """.trimIndent()
    }

    @Test
    fun `should render protected constructor without parameters of open class`() {
        // without it, the class would read as having the implicit public constructor
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedEmptyConstructorOpenClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedEmptyConstructorOpenClass [page 1/1]
            open class ProtectedEmptyConstructorOpenClass protected constructor()
        """.trimIndent()
    }

    @Test
    fun `should render protected constructor without parameters of abstract class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedEmptyConstructorAbstractClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedEmptyConstructorAbstractClass [page 1/1]
            abstract class ProtectedEmptyConstructorAbstractClass protected constructor()
        """.trimIndent()
    }

    @Test
    fun `should render parameters of value class constructor which cannot be called`() {
        // a value class cannot be declared without its single parameter
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InternalConstructorValueClass", pageSize = 1
        ) should {
            have(lines()[1] == "@JvmInline value class InternalConstructorValueClass private constructor(val raw: Long) {")
        }
    }

    @Test
    fun `should render parameters of data class constructor which cannot be called`() {
        // a data class cannot be declared without parameters, each declaring a property, also a private one
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PrivateConstructorDataClass", pageSize = 1
        ) should {
            have(lines()[1] == "data class PrivateConstructorDataClass private constructor(val a: Int, private val b: Int) {")
        }
    }

    @Test
    fun `should render private property of data class constructor`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PrivatePropertyDataClass", pageSize = 1
        ) should {
            have(lines()[1] == "data class PrivatePropertyDataClass(val a: Int, private val b: Int) {")
        }
    }

    @Test
    fun `should not render constructor of data object`() {
        // an object cannot have a constructor, unlike a data class
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SealedTestClass.SubDataObject", pageSize = 1
        ) should {
            have(lines()[1].startsWith("data object SubDataObject : SealedTestClass"))
        }
    }

    @Test
    fun `should mark stdlib class with private constructor as not constructible`() {
        resolver.resolve("kotlin.io.encoding.Base64") should {
            have(lines()[1] == "open class Base64 private constructor() {")
        }
    }

    @Test
    fun `should render secondary constructors after properties`() {
        // following the conventional order of class members: properties, secondary constructors, functions
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SecondaryConstructors"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SecondaryConstructors [page 1/1]
            class SecondaryConstructors(val name: String) {
                val tags: MutableList<String>
                constructor(name: String, vararg tags: String)
                constructor(id: Int, prefix: String = …)
                fun describe(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should paginate secondary constructors one per item`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SecondaryConstructors",
            page = 2,
            pageSize = 2
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SecondaryConstructors [page 2/2]
            class SecondaryConstructors(val name: String) {
                constructor(id: Int, prefix: String = …)
                fun describe(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should render secondary constructor of class with private primary constructor`() {
        // the secondary constructor is the only way of creating the class
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SecondaryConstructorOfPrivatePrimary"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SecondaryConstructorOfPrivatePrimary [page 1/1]
            class SecondaryConstructorOfPrivatePrimary {
                val a: Int
                constructor(s: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should render secondary constructors of class without primary constructor`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SecondaryConstructorsWithoutPrimary"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SecondaryConstructorsWithoutPrimary [page 1/1]
            class SecondaryConstructorsWithoutPrimary {
                val x: Int
                constructor(x: Int)
                constructor(s: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should render primary constructor without parameters next to secondary constructor`() {
        // without the parentheses, the class would have no constructor without parameters
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SecondaryConstructorOfEmptyPrimary"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SecondaryConstructorOfEmptyPrimary [page 1/1]
            class SecondaryConstructorOfEmptyPrimary() {
                constructor(x: Int)
            }
        """.trimIndent()
    }

    @Test
    fun `should render protected secondary constructor of open class`() {
        // the internal and private constructors cannot be called from the user code
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SecondaryConstructorsOfOpenClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SecondaryConstructorsOfOpenClass [page 1/1]
            open class SecondaryConstructorsOfOpenClass(val id: Int) {
                protected constructor(s: String)
                fun useDouble(): SecondaryConstructorsOfOpenClass
            }
        """.trimIndent()
    }

    @Test
    fun `should not render protected secondary constructor of final class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedSecondaryConstructorOfFinalClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedSecondaryConstructorOfFinalClass [page 1/1]
            class ProtectedSecondaryConstructorOfFinalClass(val id: Int)
        """.trimIndent()
    }

    @Test
    fun `should not render secondary constructor of sealed class`() {
        // it is protected, and the user code cannot declare a subclass calling it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SecondaryConstructorOfSealedClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SecondaryConstructorOfSealedClass [page 1/1]
            sealed class SecondaryConstructorOfSealedClass {
                class Sub : SecondaryConstructorOfSealedClass
                val id: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not render secondary constructor of enum class`() {
        // it is private, only for declaring the entries
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SecondaryConstructorOfEnum"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SecondaryConstructorOfEnum [page 1/1]
            enum class SecondaryConstructorOfEnum(val code: Int) {
                A,
                B
            }
        """.trimIndent()
    }

    @Test
    fun `should render secondary constructor of data class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SecondaryConstructorOfDataClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SecondaryConstructorOfDataClass [page 1/1]
            data class SecondaryConstructorOfDataClass(val x: Int, val y: Int) {
                constructor(xy: Int)
                operator fun component1(): Int
                operator fun component2(): Int
                fun copy(x: Int = …, y: Int = …): SecondaryConstructorOfDataClass
                override operator fun equals(other: Any?): Boolean
                override fun hashCode(): Int
                override fun toString(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should render secondary constructor of value class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SecondaryConstructorOfValueClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SecondaryConstructorOfValueClass [page 1/1]
            @JvmInline value class SecondaryConstructorOfValueClass(val value: Int) {
                constructor(s: String)
                override operator fun equals(other: Any?): Boolean
                override fun hashCode(): Int
                override fun toString(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should render secondary constructor with type parameter of the class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SecondaryConstructorOfGenericClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SecondaryConstructorOfGenericClass [page 1/1]
            class SecondaryConstructorOfGenericClass<T>(val value: T) {
                constructor(values: List<T>)
            }
        """.trimIndent()
    }

    @Test
    fun `should not render constructor parameter as body property of the same name and another type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByProperty"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByProperty [page 1/1]
            class ConstructorParameterShadowedByProperty(x: Int) {
                val x: Long
            }
        """.trimIndent()
    }

    @Test
    fun `should not render constructor parameter as computed body property of the same name and type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByComputedProperty"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByComputedProperty [page 1/1]
            class ConstructorParameterShadowedByComputedProperty(x: Int) {
                val initial: Int
                val x: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should render constructor parameter assigned to body property of the same name and type as constructor property`() {
        // the metadata and the bytecode are the same as of `class ConstructorParameterAssignedToProperty(val x: Int)`,
        // and so is the API
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ConstructorParameterAssignedToProperty"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ConstructorParameterAssignedToProperty [page 1/1]
            class ConstructorParameterAssignedToProperty(val x: Int)
        """.trimIndent()
    }

    @Test
    fun `should not render constructor parameter as abstract body property of the same name and type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByAbstractProperty"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByAbstractProperty [page 1/1]
            abstract class ConstructorParameterShadowedByAbstractProperty(x: Int, y: String) {
                abstract val x: Int
                abstract var y: String
            }
        """.trimIndent()
    }

    @Test
    fun `should not render constructor parameter as constant initialized body property of the same name and type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByConstantProperty"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByConstantProperty [page 1/1]
            class ConstructorParameterShadowedByConstantProperty(x: Int, y: String) {
                val x: Int
                val y: String
            }
        """.trimIndent()
    }

    @Test
    fun `should render constructor properties with accessors renamed for the JVM`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ConstructorPropertiesWithJvmNames"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ConstructorPropertiesWithJvmNames [page 1/1]
            class ConstructorPropertiesWithJvmNames(val x: Int, var y: Int)
        """.trimIndent()
    }

    @Test
    fun `should render IntRange bounds overriding body properties as in the source`() {
        // the parameters are passed to the IntProgression constructor, while the overriding properties are computed
        resolver.resolve("kotlin.ranges.IntRange") should {
            have(lines().contains("class IntRange(start: Int, endInclusive: Int) : IntProgression, ClosedRange<Int>, OpenEndRange<Int> {"))
            have(lines().contains("    override val start: Int"))
            have(lines().contains("    override val endInclusive: Int"))
        }
    }

    @Test
    fun `should render member extension property named like constructor property in class body`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CtorClash"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CtorClash [page 1/1]
            class CtorClash(val measure: Int) : MeasureX {
                override val String.measure: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not render constructor parameter as body property of the same name and type with private setter`() {
        // the property cannot be assigned from the user code, so it is rendered as val
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByPrivateSetterProperty"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByPrivateSetterProperty [page 1/1]
            class ConstructorParameterShadowedByPrivateSetterProperty(x: Int) {
                val x: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not render constructor parameter as delegated body property of the same name and type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByDelegatedProperty"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByDelegatedProperty [page 1/1]
            class ConstructorParameterShadowedByDelegatedProperty(x: Int) {
                val x: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not render constructor parameter as lateinit body property of the same name and type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByLateinitProperty"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByLateinitProperty [page 1/1]
            class ConstructorParameterShadowedByLateinitProperty(x: String) {
                var x: String
            }
        """.trimIndent()
    }

    @Test
    fun `should not render constructor parameter as property delegated to interface implementation`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByInterfaceDelegation"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ConstructorParameterShadowedByInterfaceDelegation [page 1/1]
            class ConstructorParameterShadowedByInterfaceDelegation(x: Int, delegate: ConstructorParameterDelegate) : ConstructorParameterDelegate {
                override val x: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should render name of named companion object`() {
        // an LLM needs the name to qualify the companion object members, like NamedCompanionHolder.Factory.of()
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NamedCompanionHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NamedCompanionHolder [page 1/1]
            class NamedCompanionHolder {
                companion object Factory {
                    fun of(): NamedCompanionHolder
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should not list private companion object`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PrivateCompanionHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PrivateCompanionHolder [page 1/1]
            class PrivateCompanionHolder {
                fun answer(): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve private companion object`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.PrivateCompanionHolder.Companion") == null)
    }

    @Test
    fun `should not resolve member of private companion object`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.PrivateCompanionHolder.Companion.helper") == null)
    }

    @Test
    fun `should not resolve member of private companion object as class member`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.PrivateCompanionHolder.helper") == null)
    }

    @Test
    fun `should resolve protected companion object member of open class as class member`() {
        // a subclass of the outer class can call it, like in the listing
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedCompanionMembersHolder.protectedFactory"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedCompanionMembersHolder.protectedFactory [page 1/1]
            companion object {
                protected fun protectedFactory(): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve private nested class`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.OpenVisibilityHolder.NestedPrivate") == null)
    }

    @Test
    fun `should not resolve protected nested class of final class`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.NestedClassesHolder.NestedProtectedClass") == null)
    }

    @Test
    fun `should resolve protected nested class of open class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenVisibilityHolder.NestedProtected"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenVisibilityHolder.NestedProtected [page 1/1]
            protected class NestedProtected
        """.trimIndent()
    }

    @Test
    fun `should not resolve internal class`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.InternalHolder") == null)
    }

    @Test
    fun `should not resolve member of internal class`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.InternalHolder.member") == null)
    }

    @Test
    fun `should not resolve nested class of internal class`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.InternalHolder.Nested") == null)
    }

    @Test
    fun `should list protected companion object members of open class`() {
        // a subclass of the outer class can call them
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedCompanionMembersHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedCompanionMembersHolder [page 1/1]
            open class ProtectedCompanionMembersHolder {
                companion object {
                    protected fun protectedFactory(): Int
                    fun publicFactory(): Int
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve protected companion object member of open class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedCompanionMembersHolder.Companion.protectedFactory"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedCompanionMembersHolder.Companion.protectedFactory [page 1/1]
            protected fun protectedFactory(): Int
        """.trimIndent()
    }

    @Test
    fun `should render properties of sealed class constructor in class body`() {
        // the constructor of a sealed class is protected, and the user code cannot declare a subclass calling it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SealedShape"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedShape [page 1/1]
            sealed class SealedShape {
                class Square : SealedShape
                val code: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should render inner sealed subclass of another class with its outer type parameters`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.GenericSealedHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.GenericSealedHolder [page 1/1]
            sealed class GenericSealedHolder<A> {
                inner class GenericSealedOuter<T>.Sub : GenericSealedHolder<T>
            }
        """.trimIndent()
    }

    @Test
    fun `should render inner subclass of sealed interface with its outer type parameters`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InnerSealedInterface"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InnerSealedInterface [page 1/1]
            sealed interface InnerSealedInterface {
                inner class InnerSealedInterfaceHolder<X>.Sub : InnerSealedInterface, Comparable<X>
            }
        """.trimIndent()
    }

    @Test
    fun `should list sealed subclasses by their displayed names`() {
        // and not in the order of the metadata, by their fully qualified names
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SealedOrderHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedOrderHolder [page 1/1]
            sealed class SealedOrderHolder {
                class A : SealedOrderHolder
                class BSealedOrderSub : SealedOrderHolder
            }
        """.trimIndent()
    }

    @Test
    fun `should not list protected members of open class without constructor callable from subclass`() {
        // no user subclass can be declared to use them
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PrivateConstructorProtectedHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PrivateConstructorProtectedHolder [page 1/1]
            open class PrivateConstructorProtectedHolder private constructor() {
                fun f()
                companion object {
                    fun create(): PrivateConstructorProtectedHolder
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve protected member of open class without constructor callable from subclass`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.PrivateConstructorProtectedHolder.p") == null)
    }

    @Test
    fun `should not resolve protected nested class of open class without constructor callable from subclass`() {
        assert(
            resolver.resolve("com.xemantic.kotlin.introspection.test.PrivateConstructorProtectedHolder.ProtectedNested") == null
        )
    }

    @Test
    fun `should list protected members of sealed class with open subclass`() {
        // a user subclass of the open subclass can use them, while it still cannot call the constructor of the sealed class
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SealedWithOpenSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedWithOpenSubclass [page 1/1]
            sealed class SealedWithOpenSubclass {
                open class OpenSealedSubclass : SealedWithOpenSubclass
                protected class ProtectedNested
                protected val code: Int
                protected fun h()
                companion object {
                    protected fun protectedFactory(): Int
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve protected member of sealed class with open subclass`() {
        resolver.resolve("com.xemantic.kotlin.introspection.test.SealedWithOpenSubclass.h") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedWithOpenSubclass.h [page 1/1]
            protected fun h()
        """.trimIndent()
    }

    @Test
    fun `should list protected members of sealed class with open subclass of nested sealed subclass`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SealedWithNestedOpenSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedWithNestedOpenSubclass [page 1/1]
            sealed class SealedWithNestedOpenSubclass {
                sealed class Mid : SealedWithNestedOpenSubclass
                protected fun h()
            }
        """.trimIndent()
    }

    @Test
    fun `should not list protected members of sealed class without open subclass of the API`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SealedWithInternalOpenSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedWithInternalOpenSubclass [page 1/1]
            sealed class SealedWithInternalOpenSubclass {
                class Final : SealedWithInternalOpenSubclass
                // sealed subclasses not part of the API omitted
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve member of protected companion object within its context`() {
        // it can be used only by the subclasses of the outer class, like in the listing of the outer class,
        // where it is rendered within the protected companion object
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.Named.f"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.Named.f [page 1/1]
            // in protected companion object ProtectedNamedCompanionHolder.Named
            fun f()
        """.trimIndent()
    }

    @Test
    fun `should resolve property of protected companion object within its context`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.Named.counter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.Named.counter [page 1/1]
            // in protected companion object ProtectedNamedCompanionHolder.Named
            var counter: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve override in protected companion object with its declared visibility`() {
        // an override cannot narrow the visibility of the overridden function
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.Named.make"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.Named.make [page 1/1]
            // in protected companion object ProtectedNamedCompanionHolder.Named
            override fun make(): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve member of protected interface with its declared visibility`() {
        // a member of an interface cannot be protected
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.ProtectedInterface.h"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.ProtectedInterface.h [page 1/1]
            // in protected interface ProtectedNamedCompanionHolder.ProtectedInterface
            fun h()
        """.trimIndent()
    }

    @Test
    fun `should resolve class nested in protected companion object within its context`() {
        // the same context as the one of its members resolved directly
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.Named.InCompanion"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.Named.InCompanion [page 1/1]
            // in protected companion object ProtectedNamedCompanionHolder.Named
            class InCompanion {
                fun g()
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve member of class nested in protected companion object within its context`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.Named.InCompanion.g"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.Named.InCompanion.g [page 1/1]
            // in protected companion object ProtectedNamedCompanionHolder.Named
            fun g()
        """.trimIndent()
    }

    @Test
    fun `should resolve member of protected companion object through outer class within its declaration`() {
        // like in the listing of the outer class, the declaration of the companion object tells its visibility
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.f"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.f [page 1/1]
            protected companion object Named : NamedFactory {
                fun f()
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve companion object member of protected class through it within its context`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.ProtectedNested.q"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.ProtectedNested.q [page 1/1]
            // in protected class ProtectedNamedCompanionHolder.ProtectedNested
            companion object {
                fun q()
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve member of private companion object through outer class`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.PrivateCompanionHolder.helper") == null)
    }

    @Test
    fun `should resolve protected companion object with its visibility`() {
        // its own header tells its visibility, so no context is needed
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.Named"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedNamedCompanionHolder.Named [page 1/1]
            protected companion object Named : NamedFactory {
                class InCompanion
                var counter: Int
                fun f()
                override fun make(): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should list protected members of abstract class with internal constructor`() {
        // a subclass in its own module, which the user code can subclass, can call the internal constructor
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InternalConstructorBase"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InternalConstructorBase [page 1/1]
            abstract class InternalConstructorBase private constructor() {
                protected abstract fun mustOverride(): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve protected member of abstract class with internal constructor`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InternalConstructorBase.mustOverride"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InternalConstructorBase.mustOverride [page 1/1]
            protected abstract fun mustOverride(): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve companion object with default name without its name`() {
        resolver.resolve("com.xemantic.kotlin.introspection.test.ProtectedCompanionMembersHolder.Companion") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedCompanionMembersHolder.Companion [page 1/1]
            companion object {
                protected fun protectedFactory(): Int
                fun publicFactory(): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve inner class of inner class with outer type parameters`() {
        resolver.resolve("com.xemantic.kotlin.introspection.test.InnerOfInnerHolder.Mid.Leaf") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InnerOfInnerHolder.Mid.Leaf [page 1/1]
            inner class InnerOfInnerHolder<A>.Mid<B>.Leaf(override val key: A, override val value: B) : Map.Entry<A, B> {
                fun pair(): Pair<A, B>
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve inner class qualified with outer type parameters of its listed inner class`() {
        // the type parameter of the outermost class, which the listed inner class refers to, is declared by the header
        resolver.resolve("com.xemantic.kotlin.introspection.test.InnerOfInnerHolder.Mid") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InnerOfInnerHolder.Mid [page 1/1]
            inner class InnerOfInnerHolder<A>.Mid<B> {
                inner class Leaf : Map.Entry<A, B>
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve inner sealed subclass qualified with type parameters of its outer class`() {
        resolver.resolve("com.xemantic.kotlin.introspection.test.GenericSealedOuter.Sub") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.GenericSealedOuter.Sub [page 1/1]
            inner class GenericSealedOuter<T>.Sub : GenericSealedHolder<T>
        """.trimIndent()
    }

    @Test
    fun `should render private property of enum constructor parameter as parameter`() {
        // the parameter is passed by every entry, while the property is not part of the API
        resolver.resolve("com.xemantic.kotlin.introspection.test.PrivatePropertyEnum") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PrivatePropertyEnum [page 1/1]
            enum class PrivatePropertyEnum(code: Int, val label: String) {
                A;
                fun codeOf(): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should list companion object of another class as sealed subclass`() {
        // the own companion object is rendered as such, while the one of another class is a branch of a when expression,
        // referred to as an object, as a companion object can only be declared in its own class
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionSealedHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionSealedHolder [page 1/1]
            sealed class CompanionSealedHolder {
                object CompanionSealedSubclassHolder.Companion : CompanionSealedHolder
                class Sub : CompanionSealedHolder
                companion object : CompanionSealedHolder
            }
        """.trimIndent()
    }

    @Test
    fun `should list only sealed subclasses of the API`() {
        // the private and internal ones, also the ones nested in an internal class, cannot be used in a when expression
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SealedVisibilityHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedVisibilityHolder [page 1/1]
            sealed class SealedVisibilityHolder {
                class PublicSealedSubclassHolder.NestedSubclass : SealedVisibilityHolder
                class PublicSubclass : SealedVisibilityHolder
                class PublicTopLevelSealedSubclass : SealedVisibilityHolder
                // sealed subclasses not part of the API omitted
                fun usePrivate(): SealedVisibilityHolder
            }
        """.trimIndent()
    }

    @Test
    fun `should tell own internal companion object subclass as omitted`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.InternalCompanionSealed"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.InternalCompanionSealed [page 1/1]
            sealed class InternalCompanionSealed {
                class A : InternalCompanionSealed
                // sealed subclasses not part of the API omitted
            }
        """.trimIndent()
    }

    @Test
    fun `should tell own hidden companion object subclass as omitted`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenCompanionSealed"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenCompanionSealed [page 1/1]
            sealed class HiddenCompanionSealed {
                class A : HiddenCompanionSealed
                // sealed subclasses not part of the API omitted
            }
        """.trimIndent()
    }

    @Test
    fun `should tell sealed subclasses omitted when no subclass is part of the API`() {
        // a when expression of the user code cannot name any of them, and still needs an else branch
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenSubclassesSealed"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenSubclassesSealed [page 1/1]
            sealed class HiddenSubclassesSealed {
                // sealed subclasses not part of the API omitted
            }
        """.trimIndent()
    }

    @Test
    fun `should render sealed subclasses with all their supertypes`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SealedSupertypesHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedSupertypesHolder [page 1/1]
            sealed class SealedSupertypesHolder {
                class Box<T : CharSequence> : SealedSupertypesHolder, Iterable<T>
                class Circle : SealedSupertypesHolder, Comparable<SealedSupertypesHolder.Circle>
                data object Empty : SealedSupertypesHolder
            }
        """.trimIndent()
    }

    @Test
    fun `should list companion object without public members`() {
        // the companion object itself is still a public value
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenCompanionMembersHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenCompanionMembersHolder [page 1/1]
            class HiddenCompanionMembersHolder {
                companion object
            }
        """.trimIndent()
    }

    @Test
    fun `should list empty companion object`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.EmptyCompanionHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.EmptyCompanionHolder [page 1/1]
            class EmptyCompanionHolder {
                companion object
            }
        """.trimIndent()
    }

    @Test
    fun `should list named companion object with supertype and no members`() {
        // an LLM can tell that the companion object is a key, like in coroutineContext[CompanionKeyElement]
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionKeyElement"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionKeyElement [page 1/1]
            class CompanionKeyElement : CoroutineContext.Element {
                override val key: CoroutineContext.Key<*>
                companion object Key : CoroutineContext.Key<CompanionKeyElement>
            }
        """.trimIndent()
    }

    @Test
    fun `should render visibility of protected constructor property`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedConstructorPropertyHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedConstructorPropertyHolder [page 1/1]
            open class ProtectedConstructorPropertyHolder(protected val secret: Int, val open: Int)
        """.trimIndent()
    }

    @Test
    fun `should resolve extension named like private nested class`() {
        // the private nested class is not part of the API, so the name is resolved as a member of the outer class
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ShadowingHolder.X"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ShadowingHolder.X [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            val ShadowingHolder.X: Int
        """.trimIndent()
    }

    @Test
    fun `should not resolve extension of built-in type from outside of the standard library as its member`() {
        // String.wordCount() of the test package is not listed with kotlin.String either
        assert(resolver.resolve("kotlin.String.wordCount") == null)
    }

    @Test
    fun `should resolve extension of built-in type as its member with the object to import it from`() {
        // without the header, the reader would not know to import kotlin.time.Duration.Companion.seconds
        resolver.resolve(
            "kotlin.Int.seconds"
        ) sameAsKotlin """
            // kotlin.Int.seconds [page 1/1]
            // from kotlin.time.Duration.Companion
            val Int.seconds: Duration
        """.trimIndent()
    }

    @Test
    fun `should resolve same-named extensions as class member grouped by the package to import them from`() {
        // both the standard library and kotlin-reflect declare KClass.cast, two functions imported differently
        resolver.resolve(
            "kotlin.reflect.KClass.cast"
        ) sameAsKotlin """
            // kotlin.reflect.KClass.cast [page 1/1]
            // from kotlin.reflect
            fun <T : Any> KClass<T>.cast(value: Any?): T
            // from kotlin.reflect.full
            fun <T : Any> KClass<T>.cast(value: Any?): T
        """.trimIndent()
    }

    @Test
    fun `should resolve extension of built-in type from public kotlin package of another library as its member`() {
        // kotlin-reflect, a library the user depends on, extends KClass in kotlin.reflect.full
        resolver.resolve(
            "kotlin.reflect.KClass.memberFunctions"
        ) sameAsKotlin """
            // kotlin.reflect.KClass.memberFunctions [page 1/1]
            // from kotlin.reflect.full
            val KClass<*>.memberFunctions: Collection<KFunction<*>>
        """.trimIndent()
    }

    @Test
    fun `should not resolve extension of built-in type from kotlin package of library other than stdlib and kotlin-reflect`() {
        // kotlin-metadata-jvm declares fun ClassName.isLocalClassName() in kotlin.metadata, ClassName being a typealias of String
        assert(resolver.resolve("kotlin.String.isLocalClassName") == null)
    }

    @Test
    fun `should not list extensions of built-in type from kotlin package of library other than stdlib and kotlin-reflect`() {
        // which would be listed in the body of String, like its member
        resolver.resolve("kotlin.String", pageSize = 10000) should {
            have(lines().none { "isLocalClassName" in it })
        }
    }

    @Test
    fun `should list extensions of built-in type from public kotlin package of another library`() {
        resolver.resolve("kotlin.reflect.KClass", pageSize = 10000) should {
            have(lines().any { "val memberFunctions: Collection<KFunction<*>>" in it })
            have(lines().any { "val companionObject: KClass<*>?" in it })
        }
    }

    @Test
    fun `should resolve extension of supertype of built-in type as its member`() {
        // listed with String, as declared on CharSequence
        resolver.resolve(
            "kotlin.String.isBlank"
        ) sameAsKotlin """
            // kotlin.String.isBlank [page 1/1]
            // from kotlin.text
            fun CharSequence.isBlank(): Boolean
        """.trimIndent()
    }

    @Test
    fun `should resolve extension of supertype of built-in type returning a collection as its member`() {
        resolver.resolve(
            "kotlin.String.lines"
        ) sameAsKotlin """
            // kotlin.String.lines [page 1/1]
            // from kotlin.text
            fun CharSequence.lines(): List<String>
        """.trimIndent()
    }

    @Test
    fun `should resolve extension of supertype of mutable built-in collection as its member`() {
        resolver.resolve("kotlin.collections.MutableList.map") should {
            have("inline fun <T, R> Iterable<T>.map(transform: (T) -> R): List<R>" in lines())
        }
    }

    @Test
    fun `should not resolve extension of built-in type from outside of the standard library declared on its supertype`() {
        // CharSequence.charCount() of the test package is not listed with kotlin.String either
        assert(resolver.resolve("kotlin.String.charCount") == null)
    }

    // TestSetterVisibility tests

    @Test
    fun `should render var with setter not part of API as val`() {
        // the property cannot be assigned from the user code, like the protected setter of a final class
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NarrowedSetterHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NarrowedSetterHolder [page 1/1]
            class NarrowedSetterHolder {
                val privateSetter: Int
                val internalSetter: Int
                val protectedSetter: Int
                var publicSetter: Int
            }
            // from com.xemantic.kotlin.introspection.test
            val NarrowedSetterHolder.extensionPrivateSetter: Int
            // from com.xemantic.kotlin.introspection.test.NarrowedSetterExtensions
            val NarrowedSetterHolder.objectExtensionPrivateSetter: Int
        """.trimIndent()
    }

    @Test
    fun `should render protected setter of open class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenNarrowedSetterHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenNarrowedSetterHolder [page 1/1]
            open class OpenNarrowedSetterHolder {
                val privateSetter: Int
                var protectedSetter: Int
                    protected set
                protected val protectedPropertyPrivateSetter: Int
                protected var protectedProperty: Int
                companion object {
                    var companionProtectedSetter: Int
                        protected set
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve member with protected setter of open class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenNarrowedSetterHolder.protectedSetter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenNarrowedSetterHolder.protectedSetter [page 1/1]
            var protectedSetter: Int
                protected set
        """.trimIndent()
    }

    @Test
    fun `should resolve companion object member with protected setter through outer class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenNarrowedSetterHolder.companionProtectedSetter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenNarrowedSetterHolder.companionProtectedSetter [page 1/1]
            companion object {
                var companionProtectedSetter: Int
                    protected set
            }
        """.trimIndent()
    }

    @Test
    fun `should keep setter line with its property on a page of member lookup`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PagedSetterHolder.value",
            pageSize = 1
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PagedSetterHolder.value [page 1/2]
            var value: Int
                protected set
        """.trimIndent()
    }

    @Test
    fun `should keep setter line with its property on a page of class listing`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OpenNarrowedSetterHolder",
            page = 2,
            pageSize = 1
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OpenNarrowedSetterHolder [page 2/5]
            open class OpenNarrowedSetterHolder {
                var protectedSetter: Int
                    protected set
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve member var with private setter as val`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NarrowedSetterHolder.privateSetter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NarrowedSetterHolder.privateSetter [page 1/1]
            val privateSetter: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve extension var with private setter as val`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.NarrowedSetterHolder.extensionPrivateSetter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.NarrowedSetterHolder.extensionPrivateSetter [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            val NarrowedSetterHolder.extensionPrivateSetter: Int
        """.trimIndent()
    }

    @Test
    fun `should render override of var with setter not part of API as var with setter visibility`() {
        // a val cannot override a var, so the override is rendered with the setter it inherits
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedSetterOverride"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedSetterOverride [page 1/1]
            class ProtectedSetterOverride : ProtectedSetterBase {
                override var overridden: Int
                    protected set
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve override of var with setter not part of API as var with setter visibility`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedSetterOverride.overridden"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedSetterOverride.overridden [page 1/1]
            override var overridden: Int
                protected set
        """.trimIndent()
    }

    @Test
    fun `should render constructor override of var with setter not part of API as var with setter visibility`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedSetterConstructorOverride"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedSetterConstructorOverride [page 1/1]
            class ProtectedSetterConstructorOverride(overridden: Int) : ProtectedSetterBase {
                override var overridden: Int
                    protected set
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve top-level var with private setter as val`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestSetterVisibilityKt.topLevelPrivateSetter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestSetterVisibilityKt.topLevelPrivateSetter [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            val topLevelPrivateSetter: Int
        """.trimIndent()
    }

    // TestOverloads tests

    @Test
    fun `should resolve all overloads of member function`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OverloadTestClass.overloaded"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OverloadTestClass.overloaded [page 1/1]
            fun overloaded(): String
            fun overloaded(x: Int): String
        """.trimIndent()
    }

    @Test
    fun `should resolve property and function of the same name as class member in the order of the listing`() {
        // the listing renders the properties before the functions
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SameNamedPropertyAndFunction.size"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SameNamedPropertyAndFunction.size [page 1/1]
            val size: Int
            fun size(x: Int): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve all overloads of top-level function`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestOverloadsKt.topLevelOverloaded"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestOverloadsKt.topLevelOverloaded [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun topLevelOverloaded(): Int
            fun topLevelOverloaded(x: Int): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve all overloads of built-in type member`() {
        // also the ones declared on CharSequence, listed with String too
        resolver.resolve(
            "kotlin.String.substring"
        ) sameAsKotlin """
            // kotlin.String.substring [page 1/1]
            // from kotlin.text
            inline fun String.substring(startIndex: Int): String
            inline fun String.substring(startIndex: Int, endIndex: Int): String
            fun String.substring(range: IntRange): String
            inline fun CharSequence.substring(startIndex: Int, endIndex: Int = …): String
            fun CharSequence.substring(range: IntRange): String
        """.trimIndent()
    }

    @Test
    fun `should resolve function of multi-file class facade`() {
        resolver.resolve(
            "kotlin.collections.CollectionsKt.listOf"
        ) sameAsKotlin """
            // kotlin.collections.CollectionsKt.listOf [page 1/1]
            // from kotlin.collections
            fun <T> listOf(element: T): List<T>
            fun <T> listOf(vararg elements: T): List<T>
            inline fun <T> listOf(): List<T>
        """.trimIndent()
    }

    @Test
    fun `should resolve function of file facade with JVM package name under its Kotlin package`() {
        // the facade is compiled with @file:JvmPackageName("kotlin.collections.jdk8"), but imported from kotlin.collections
        resolver.resolve(
            "kotlin.collections.jdk8.CollectionsJDK8Kt.getOrDefault"
        ) sameAsKotlin """
            // kotlin.collections.jdk8.CollectionsJDK8Kt.getOrDefault [page 1/1]
            // from kotlin.collections
            inline fun <K, V> Map<out K, V>.getOrDefault(key: K, defaultValue: V): V
        """.trimIndent()
    }

    @Test
    fun `should list extension of file facade with JVM package name under its Kotlin package`() {
        resolver.resolve(
            "kotlin.collections.Map.getOrDefault"
        ) sameAsKotlin """
            // kotlin.collections.Map.getOrDefault [page 1/1]
            open fun getOrDefault(key: K, defaultValue: V): V
            // from kotlin.collections
            inline fun <K, V> Map<out K, V>.getOrDefault(key: K, defaultValue: V): V
        """.trimIndent()
    }

    // built-in collection tests

    @Test
    fun `should resolve List with extensions of all its supertypes`() {
        resolver.resolve(
            "kotlin.collections.List",
            pageSize = 10000
        ) should {
            have(lines()[1] == "interface List<out E> : Collection<E> {")
            have("inline fun <T, R> Iterable<T>.map(transform: (T) -> R): List<R>" in this)
            have("fun <T> Collection<T>.toMutableList(): MutableList<T>" in this)
        }
    }

    @Test
    fun `should resolve MutableList with its mutating members`() {
        // the first page also lists the addFirst and addLast of java.util.List since JDK 21, see the test below
        resolver.resolve("kotlin.collections.MutableList", pageSize = 10000) should {
            have(lines()[1] == "interface MutableList<E> : List<E>, MutableCollection<E> {")
            have("    override fun add(element: E): Boolean" in lines())
            have("    fun add(index: Int, element: E)" in lines())
            have("    override fun addAll(elements: Collection<E>): Boolean" in lines())
            have("    fun addAll(index: Int, elements: Collection<E>): Boolean" in lines())
            have("    override fun clear()" in lines())
            have("    inline fun fill(value: E)" in lines())
            have("    override fun remove(element: E): Boolean" in lines())
            have("    fun removeAt(index: Int): E" in lines())
            have("    operator fun set(index: Int, element: E): E" in lines())
        }
    }

    @Test
    fun `should resolve MutableList with sequenced members of JDK 21 taking its type parameter`() {
        // the parameter of type E! accepts no null, unlike one of a concrete platform type, like Throwable!
        assumeTrue(runCatching { List::class.java.getMethod("addFirst", Any::class.java) }.isSuccess, "JDK 21+")
        resolver.resolve(
            "kotlin.collections.MutableList"
        ) sameAsKotlin """
            // kotlin.collections.MutableList [page 1/35]
            interface MutableList<E> : List<E>, MutableCollection<E> {
                override fun add(element: E): Boolean
                fun add(index: Int, element: E)
                override fun addAll(elements: Collection<E>): Boolean
                fun addAll(index: Int, elements: Collection<E>): Boolean
                open fun addFirst(arg0: E)
                open fun addLast(arg0: E)
                fun asReversed(): MutableList<E>
                override fun clear()
                inline fun fill(value: E)
                override fun listIterator(): MutableListIterator<E>
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve MutableList member overriding a MutableCollection member`() {
        // rendered like the same member of a user class implementing MutableList
        resolver.resolve(
            "kotlin.collections.MutableList.add"
        ) sameAsKotlin """
            // kotlin.collections.MutableList.add [page 1/1]
            override fun add(element: E): Boolean
            fun add(index: Int, element: E)
        """.trimIndent()
    }

    @Test
    fun `should resolve MutableCollection member overriding nothing`() {
        resolver.resolve(
            "kotlin.collections.MutableCollection.add"
        ) sameAsKotlin """
            // kotlin.collections.MutableCollection.add [page 1/1]
            fun add(element: E): Boolean
        """.trimIndent()
    }

    @Test
    fun `should resolve JVM-only member of mutable collection type`() {
        // the mutating methods of java.util.Collection, which only the mutable collection types declare in Kotlin
        assumeNoParameterNames(Collection::class.java.getMethod("removeIf", java.util.function.Predicate::class.java))
        resolver.resolve("kotlin.collections.MutableCollection.removeIf") sameAsKotlin """
            // kotlin.collections.MutableCollection.removeIf [page 1/1]
            open fun removeIf(arg0: java.util.function.Predicate<in E>): Boolean
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited JVM-only member of mutable list taking its type parameter`() {
        assumeTrue(runCatching { List::class.java.getMethod("addFirst", Any::class.java) }.isSuccess, "JDK 21+")
        resolver.resolve("com.xemantic.kotlin.introspection.test.JdkMutableList.addFirst") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JdkMutableList.addFirst [page 1/1]
            // inherited from kotlin.collections.MutableList
            open fun addFirst(arg0: String)
        """.trimIndent()
    }

    @Test
    fun `should resolve JVM-only member of mutable list`() {
        assumeNoParameterNames(List::class.java.getMethod("replaceAll", java.util.function.UnaryOperator::class.java))
        resolver.resolve("kotlin.collections.MutableList.replaceAll") sameAsKotlin """
            // kotlin.collections.MutableList.replaceAll [page 1/1]
            open fun replaceAll(arg0: java.util.function.UnaryOperator<E>)
        """.trimIndent()
    }

    @Test
    fun `should resolve JVM-only member of mutable collection type inherited by mutable list`() {
        assumeNoParameterNames(Collection::class.java.getMethod("removeIf", java.util.function.Predicate::class.java))
        resolver.resolve("kotlin.collections.MutableList.removeIf") sameAsKotlin """
            // kotlin.collections.MutableList.removeIf [page 1/1]
            // inherited from kotlin.collections.MutableCollection
            open fun removeIf(arg0: java.util.function.Predicate<in E>): Boolean
        """.trimIndent()
    }

    @Test
    fun `should list JVM-only members of mutable list`() {
        // the removeFirst() of java.util.List since JDK 21 is called instead of the MutableList<T>.removeFirst() extension
        assumeTrue(runCatching { List::class.java.getMethod("removeFirst") }.isSuccess, "JDK 21+")
        resolver.resolve("kotlin.collections.MutableList", pageSize = 10000) should {
            have("    open fun removeFirst(): E" in lines())
            have("    fun removeFirst(): E" !in lines())
            have(lines().any { it.startsWith("    open fun replaceAll(") })
        }
    }

    @Test
    fun `should list JVM-only members of mutable map`() {
        // the remove(Object, Object) of java.util.Map is the remove(key: K, value: V) which MutableMap declares
        assumeNoParameterNames(Map::class.java.getMethod("putIfAbsent", Any::class.java, Any::class.java))
        resolver.resolve("kotlin.collections.MutableMap", pageSize = 10000) should {
            // the parameters of the types K! and V! accept no null
            have("    open fun putIfAbsent(arg0: K, arg1: V): V?" in lines())
            have("    open fun replace(arg0: K, arg1: V, arg2: V): Boolean" in lines())
            have("    open fun remove(key: K, value: V): Boolean" in lines())
            have(lines().none { it.startsWith("    open fun remove(key: K?") })
        }
    }

    @Test
    fun `should not list JVM member of mutable list which the compiler hides`() {
        // the sort(Comparator) of java.util.List is hidden by Kotlin, the deprecated MutableList<T>.sort extension is called,
        // while replaceAll(UnaryOperator) of java.util.List is not
        assumeNoParameterNames(List::class.java.getMethod("replaceAll", java.util.function.UnaryOperator::class.java))
        resolver.resolve("kotlin.collections.MutableList", pageSize = 10000) should {
            have(lines().none { "sort(arg0" in it })
            have("    open fun replaceAll(arg0: java.util.function.UnaryOperator<E>)" in lines())
        }
    }

    @Test
    fun `should resolve List members overriding Collection members`() {
        resolver.resolve("kotlin.collections.List", pageSize = 10000) should {
            have("    override val size: Int" in lines())
            have("    override fun isEmpty(): Boolean" in lines())
            have("    override operator fun contains(element: E): Boolean" in lines())
            have("    override operator fun iterator(): Iterator<E>" in lines())
            have("    operator fun get(index: Int): E" in lines())
        }
    }

    @Test
    fun `should resolve List member overriding a Collection member`() {
        resolver.resolve(
            "kotlin.collections.List.size"
        ) sameAsKotlin """
            // kotlin.collections.List.size [page 1/1]
            override val size: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve String members overriding CharSequence and Comparable members`() {
        resolver.resolve("kotlin.String", pageSize = 10000) should {
            have("    override val length: Int" in lines())
            have("    override operator fun get(index: Int): Char" in lines())
            have("    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence" in lines())
            have("    override operator fun compareTo(other: String): Int" in lines())
            have("    override operator fun equals(other: Any?): Boolean" in lines())
            have("    override fun toString(): String" in lines())
            // not redeclared by String, just inherited from Any, like any inherited member not listed
            have(lines().none { it.trim() == "fun hashCode(): Int" })
            have("    fun compareTo(other: String, ignoreCase: Boolean = …): Int" in lines())
            have("    operator fun plus(other: Any?): String" in lines())
        }
    }

    @Test
    fun `should resolve Int compareTo overriding only Comparable compareTo`() {
        resolver.resolve(
            "kotlin.Int.compareTo"
        ) sameAsKotlin """
            // kotlin.Int.compareTo [page 1/1]
            operator fun compareTo(other: Byte): Int
            operator fun compareTo(other: Short): Int
            override operator fun compareTo(other: Int): Int
            operator fun compareTo(other: Long): Int
            operator fun compareTo(other: Float): Int
            operator fun compareTo(other: Double): Int
            // from kotlin
            inline infix fun <T> Comparable<T>.compareTo(other: T): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve Any members as open and not overriding`() {
        resolver.resolve("kotlin.Any") should {
            have(lines()[1] == "open class Any {")
            have("    open operator fun equals(other: Any?): Boolean" in lines())
            have("    open fun hashCode(): Int" in lines())
            have("    open fun toString(): String" in lines())
        }
    }

    // pagination tests

    @Test
    fun `resolve should reject non-positive pageSize`() {
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve("kotlin.String", pageSize = 0)
        } should {
            have(message == "pageSize must be positive, but was: 0")
        }
    }

    @Test
    fun `search should reject non-positive pageSize`() {
        assertFailsWith<IllegalArgumentException> {
            resolver.search("TestClass", pageSize = -1)
        } should {
            have(message == "pageSize must be positive, but was: -1")
        }
    }

    @Test
    fun `resolve should return everything on one page when pageSize is Int MAX_VALUE`() {
        // "all on one page" must not overflow the page count calculation
        resolver.resolve(
            "kotlin.String",
            pageSize = Int.MAX_VALUE
        ) should {
            have(this == resolver.resolve("kotlin.String", pageSize = 10000))
            have(startsWith("// kotlin.String [page 1/1]\n"))
        }
    }

    @Test
    fun `resolve should coerce page Int MAX_VALUE to the last page when pageSize is Int MAX_VALUE`() {
        resolver.resolve(
            "kotlin.String",
            page = Int.MAX_VALUE,
            pageSize = Int.MAX_VALUE
        ) should {
            have(this == resolver.resolve("kotlin.String", pageSize = 10000))
            have(startsWith("// kotlin.String [page 1/1]\n"))
        }
    }

    @Test
    fun `search should return everything on one page when pageSize is Int MAX_VALUE`() {
        resolver.search("TestClass", pageSize = Int.MAX_VALUE) should {
            have(this == resolver.search("TestClass", pageSize = 1000))
            have(startsWith("// search results for \"TestClass\" [page 1/1]\n"))
        }
    }

    @Test
    fun `search should coerce page Int MAX_VALUE to the last page when pageSize is Int MAX_VALUE`() {
        resolver.search(
            "TestClass",
            page = Int.MAX_VALUE,
            pageSize = Int.MAX_VALUE
        ) should {
            have(this == resolver.search("TestClass", pageSize = 1000))
            have(startsWith("// search results for \"TestClass\" [page 1/1]\n"))
        }
    }

    @Test
    fun `resolve should coerce page past the end to the only page when pageSize is half of Int MAX_VALUE`() {
        // a large page size which is not Int.MAX_VALUE, for which 2 * pageSize overflows an Int,
        // pins that a page past the end is coerced to the only one, however the start index is computed
        resolver.resolve(
            "kotlin.String",
            page = 3,
            pageSize = Int.MAX_VALUE / 2 + 1
        ) should {
            have(this == resolver.resolve("kotlin.String", pageSize = 10000))
            have(startsWith("// kotlin.String [page 1/1]\n"))
        }
    }

    @Test
    fun `search should coerce page past the end to the only page when pageSize is half of Int MAX_VALUE`() {
        resolver.search(
            "TestClass",
            page = 2,
            pageSize = Int.MAX_VALUE / 2 + 1
        ) should {
            have(this == resolver.search("TestClass", pageSize = 1000))
            have(startsWith("// search results for \"TestClass\" [page 1/1]\n"))
        }
    }

    @Test
    fun `should paginate member lookup by signatures - the first page`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PagedMemberHolder.paged",
            pageSize = 2
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PagedMemberHolder.paged [page 1/5]
            val paged: Int
            fun paged(x: Int): Int
        """.trimIndent()
    }

    @Test
    fun `should paginate member lookup by signatures - the page with members of the companion object`() {
        // the declaration of the companion object is repeated on every page with its members
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PagedMemberHolder.paged",
            page = 2,
            pageSize = 2
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PagedMemberHolder.paged [page 2/5]
            fun paged(x: String): String
            companion object {
                fun paged(): PagedMemberHolder
            }
        """.trimIndent()
    }

    @Test
    fun `should paginate member lookup by signatures - the page with members of the companion object and extensions`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PagedMemberHolder.paged",
            page = 3,
            pageSize = 2
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PagedMemberHolder.paged [page 3/5]
            companion object {
                fun paged(x: Long): PagedMemberHolder
            }
            // from com.xemantic.kotlin.introspection.test
            fun PagedMemberHolder.paged(x: Double): Double
        """.trimIndent()
    }

    @Test
    fun `should paginate member lookup by signatures - the page continuing extensions of a source`() {
        // the `// from` header is repeated, so that every page tells where to import the extensions from
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PagedMemberHolder.paged",
            page = 5,
            pageSize = 2
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PagedMemberHolder.paged [page 5/5]
            // from com.xemantic.kotlin.introspection.test.ext
            fun PagedMemberHolder.paged(x: Byte): Byte
        """.trimIndent()
    }

    @Test
    fun `should paginate package member lookup by signatures`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestOverloadsKt.topLevelOverloaded",
            page = 2,
            pageSize = 1
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestOverloadsKt.topLevelOverloaded [page 2/2]
            // from com.xemantic.kotlin.introspection.test
            fun topLevelOverloaded(x: Int): Int
        """.trimIndent()
    }

    @Test
    fun `should paginate built-in type member lookup by signatures`() {
        resolver.resolve("kotlin.collections.MutableList.plus", pageSize = 5) should {
            have(startsWith("// kotlin.collections.MutableList.plus [page 1/"))
            have(!startsWith("// kotlin.collections.MutableList.plus [page 1/1]"))
            have(lines().count { !it.startsWith("//") } == 5)
        }
    }

    @Test
    fun `should hint resolving the companion object directly on a page holding it when it is larger than the page`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PaginatedCompanionClass",
            page = 10,
            pageSize = 1
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PaginatedCompanionClass [page 10/10]
            class PaginatedCompanionClass {
                companion object {
                    // 2 members, resolve com.xemantic.kotlin.introspection.test.PaginatedCompanionClass.Companion to page through them
                    const val CONSTANT: String
                    fun create(): PaginatedCompanionClass
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve companion object of built-in type directly`() {
        // without its default name, like any companion object resolved directly
        resolver.resolve("kotlin.Int.Companion", pageSize = 1000) should {
            have(startsWith("// kotlin.Int.Companion [page 1/1]\ncompanion object {\n"))
            have("    const val MAX_VALUE: Int" in lines())
            have("    const val MIN_VALUE: Int" in lines())
        }
    }

    @Test
    fun `should resolve companion object of built-in type directly with its extensions`() {
        resolver.resolve("kotlin.String.Companion", pageSize = 1000) should {
            have(startsWith("// kotlin.String.Companion [page 1/1]\n"))
            have("// from kotlin.text" in lines())
            have(lines().any { it.startsWith("inline fun String.Companion.format(") })
        }
    }

    @Test
    fun `should resolve companion object of built-in type with the extensions of any library`() {
        // a library extends the companion object of a built-in type deliberately, like kotlinx.serialization,
        // unlike the built-in type itself, whose extensions of the other libraries are not listed
        resolver.resolve("kotlin.Int.Companion", pageSize = 1000) should {
            have("// from kotlinx.serialization.builtins" in lines())
            have("fun Int.Companion.serializer(): kotlinx.serialization.KSerializer<Int>" in lines())
        }
    }

    @Test
    fun `should not resolve companion object of built-in type with the extensions of an internal package`() {
        resolver.resolve("kotlin.Int.Companion", pageSize = 1000) should {
            have(lines().none { "internalPackageHelper" in it })
        }
    }

    @Test
    fun `should resolve extension of companion object of built-in type from any library`() {
        resolver.resolve("kotlin.UInt.Companion.serializer") sameAsKotlin """
            // kotlin.UInt.Companion.serializer [page 1/1]
            // from kotlinx.serialization.builtins
            fun UInt.Companion.serializer(): kotlinx.serialization.KSerializer<UInt>
        """.trimIndent()
    }

    @Test
    fun `should resolve member of companion object of built-in type`() {
        resolver.resolve(
            "kotlin.Int.Companion.MAX_VALUE"
        ) sameAsKotlin """
            // kotlin.Int.Companion.MAX_VALUE [page 1/1]
            const val MAX_VALUE: Int
        """.trimIndent()
    }

    // search() tests

    @Test
    fun `search should find class by partial name match`() {
        // should find our test classes
        resolver.search("TestClass") sameAsKotlin """
            // search results for "TestClass" [page 1/3]
            com.xemantic.kotlin.introspection.test.AbstractTestClass
            com.xemantic.kotlin.introspection.test.AnnotationDefaultsTestClass
            com.xemantic.kotlin.introspection.test.AnnotationTestClass
            com.xemantic.kotlin.introspection.test.CompanionTestClass
            com.xemantic.kotlin.introspection.test.DataTestClass
            com.xemantic.kotlin.introspection.test.EnumTestClass
            com.xemantic.kotlin.introspection.test.FunctionModifiersTestClass
            com.xemantic.kotlin.introspection.test.GenericTestClass
            com.xemantic.kotlin.introspection.test.NestedCompanionTestClass
            com.xemantic.kotlin.introspection.test.NoMembersTestClass
        """.trimIndent()
    }

    @Test
    fun `search should be case-insensitive`() {
        // should still find test classes despite uppercase query
        resolver.search("TESTCLASS") sameAsKotlin """
            // search results for "TESTCLASS" [page 1/3]
            com.xemantic.kotlin.introspection.test.AbstractTestClass
            com.xemantic.kotlin.introspection.test.AnnotationDefaultsTestClass
            com.xemantic.kotlin.introspection.test.AnnotationTestClass
            com.xemantic.kotlin.introspection.test.CompanionTestClass
            com.xemantic.kotlin.introspection.test.DataTestClass
            com.xemantic.kotlin.introspection.test.EnumTestClass
            com.xemantic.kotlin.introspection.test.FunctionModifiersTestClass
            com.xemantic.kotlin.introspection.test.GenericTestClass
            com.xemantic.kotlin.introspection.test.NestedCompanionTestClass
            com.xemantic.kotlin.introspection.test.NoMembersTestClass
        """.trimIndent()
    }

    @Test
    fun `search should return null when no matches found`() {
        assert(resolver.search("XyzNonExistentClass12345") == null)
    }

    @Test
    fun `search should support pagination with pageSize`() {
        // should show first 3 results (22 total classes, 8 pages)
        resolver.search("TestClass", pageSize = 3) sameAsKotlin """
            // search results for "TestClass" [page 1/8]
            com.xemantic.kotlin.introspection.test.AbstractTestClass
            com.xemantic.kotlin.introspection.test.AnnotationDefaultsTestClass
            com.xemantic.kotlin.introspection.test.AnnotationTestClass
        """.trimIndent()
    }

    @Test
    fun `search should support pagination with page parameter`() {
        // should show results 4-6 (22 total classes, 8 pages)
        resolver.search("TestClass", page = 2, pageSize = 3) sameAsKotlin """
            // search results for "TestClass" [page 2/8]
            com.xemantic.kotlin.introspection.test.CompanionTestClass
            com.xemantic.kotlin.introspection.test.DataTestClass
            com.xemantic.kotlin.introspection.test.EnumTestClass
        """.trimIndent()
    }

    @Test
    fun `search should find sealed subclass by its Kotlin name`() {
        resolver.search("SubClass1") sameAsKotlin """
            // search results for "SubClass1" [page 1/1]
            com.xemantic.kotlin.introspection.test.SealedTestClass.SubClass1
        """.trimIndent()
    }

    @Test
    fun `search should find nested class`() {
        resolver.search("NestedInnerClass") sameAsKotlin """
            // search results for "NestedInnerClass" [page 1/1]
            com.xemantic.kotlin.introspection.test.NestedClassesHolder.NestedInnerClass
        """.trimIndent()
    }

    @Test
    fun `search should find nested class by a common name`() {
        resolver.search("Inner", pageSize = 1000) should {
            have("com.xemantic.kotlin.introspection.test.NestedCompanionTestClass.Inner" in lines())
        }
    }

    @Test
    fun `search should not find private nested class`() {
        assert(resolver.search("NestedPrivateClass") == null)
    }

    @Test
    fun `search should not find protected nested class of final class`() {
        assert(resolver.search("NestedProtectedClass") == null)
    }

    @Test
    fun `search should not find internal nested class`() {
        assert(resolver.search("NestedInternalClass") == null)
    }

    @Test
    fun `search should not find companion objects`() {
        // they are already rendered within their outer class
        resolver.search("Companion", pageSize = 1000) should {
            have(!contains("CompanionTestClass.Companion"))
        }
    }

    @Test
    fun `search should find nested class named with dollar sign`() {
        // only the synthetic classes are excluded, not the ones legitimately named with a $
        resolver.search("Dollar\$Nested") sameAsKotlin """
            // search results for "Dollar${'$'}Nested" [page 1/1]
            com.xemantic.kotlin.introspection.test.NestedClassesHolder.Dollar${'$'}Nested
        """.trimIndent()
    }

    @Test
    fun `search should find nested class named with dollar sign prefix`() {
        resolver.search("\$Prefixed") sameAsKotlin """
            // search results for "${'$'}Prefixed" [page 1/1]
            com.xemantic.kotlin.introspection.test.NestedClassesHolder.${'$'}Prefixed
        """.trimIndent()
    }

    @Test
    fun `search should not find internal top-level class`() {
        assert(resolver.search("InternalNestedHolder") == null)
    }

    @Test
    fun `search should not find internal top-level object`() {
        assert(resolver.search("InternalExtensionObject") == null)
    }

    @Test
    fun `search should not find private top-level class`() {
        assert(resolver.search("PrivateTopLevelHolder") == null)
    }

    @Test
    fun `search should not find hidden deprecated top-level class`() {
        assert(resolver.search("HiddenTopLevelHolder") == null)
    }

    @Test
    fun `search should not find nested class of hidden deprecated class`() {
        assert(resolver.search("NestedInHiddenClass") == null)
    }

    @Test
    fun `search should not find generated serializers`() {
        // the $serializer of kotlinx.serialization, deprecated with the HIDDEN level,
        // or not, if generated by an older plugin, while listed in the metadata of the outer class
        assert(resolver.search("\$serializer") == null)
    }

    @Test
    fun `search should find hand-written nested serializer`() {
        resolver.search("HandWrittenSerializer") sameAsKotlin """
            // search results for "HandWrittenSerializer" [page 1/1]
            com.xemantic.kotlin.introspection.test.HandWrittenSerializerHolder
            com.xemantic.kotlin.introspection.test.HandWrittenSerializerHolder.HandWrittenSerializer
        """.trimIndent()
    }

    @Test
    fun `should not list generated serializer not deprecated with hidden level in outer class`() {
        resolver.resolve("com.xemantic.kotlin.introspection.test.LegacySerializableHolder") sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.LegacySerializableHolder [page 1/1]
            class LegacySerializableHolder(val value: Int)
        """.trimIndent()
    }

    @Test
    fun `search should not find nested class of internal class`() {
        assert(resolver.search("NestedInInternalClass") == null)
    }

    @Test
    fun `search should find enum class`() {
        resolver.search("EnumTestClass") sameAsKotlin """
            // search results for "EnumTestClass" [page 1/1]
            com.xemantic.kotlin.introspection.test.EnumTestClass
            com.xemantic.kotlin.introspection.test.RichEnumTestClass
        """.trimIndent()
    }

    @Test
    fun `search should find annotation class`() {
        resolver.search("AnnotationTestClass") sameAsKotlin """
            // search results for "AnnotationTestClass" [page 1/1]
            com.xemantic.kotlin.introspection.test.AnnotationTestClass
        """.trimIndent()
    }

    @Test
    fun `search should find value class`() {
        resolver.search("ValueTestClass") sameAsKotlin """
            // search results for "ValueTestClass" [page 1/1]
            com.xemantic.kotlin.introspection.test.ValueTestClass
        """.trimIndent()
    }

    @Test
    fun `should not list declarations deprecated with hidden level`() {
        // the hidden overloads, kept only for the binary compatibility, cannot be called from the source code,
        // unlike the ones deprecated with the ERROR level
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenDeprecationTarget"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenDeprecationTarget [page 1/1]
            class HiddenDeprecationTarget(val value: Int) {
                constructor(value: Long)
                fun member(): Int
                @Deprecated("replaced", level = DeprecationLevel.ERROR) fun errorMember(): Int
                companion object {
                    fun create(): HiddenDeprecationTarget
                }
            }
            // from com.xemantic.kotlin.introspection.test
            fun HiddenDeprecationTarget.extension(): Int
            // from com.xemantic.kotlin.introspection.test.HiddenDeprecationExtensions
            fun HiddenDeprecationTarget.objectExtension(): Int
        """.trimIndent()
    }

    @Test
    fun `should render deprecation of class members, constructors and extensions`() {
        // the declarations deprecated with the ERROR level can still be referred to, but not called
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DeprecationTarget"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DeprecationTarget [page 1/1]
            class DeprecationTarget @Deprecated("Use create()") constructor(val value: Int) {
                @Deprecated("No \"replacement\" for \${'$'}value") val deprecatedProperty: Int
                @Deprecated("Getter") val deprecatedGetter: Int
                @Deprecated("Use the Int constructor", level = DeprecationLevel.ERROR) constructor(value: String)
                @Deprecated("Use newMember()", ReplaceWith("newMember()")) fun warningMember(): Int
                @Deprecated("Use newMember()", ReplaceWith("newMember()"), level = DeprecationLevel.ERROR) fun errorMember(): Int
                fun newMember(): Int
                companion object {
                    @Deprecated("Use the constructor") fun create(): DeprecationTarget
                }
            }
            // from com.xemantic.kotlin.introspection.test
            @Deprecated("Gone", level = DeprecationLevel.ERROR) val DeprecationTarget.deprecatedExtensionProperty: Int
            @Deprecated("Use newMember()", ReplaceWith("newMember()")) fun DeprecationTarget.deprecatedExtension(): Int
        """.trimIndent()
    }

    @Test
    fun `should render deprecation of class and nested class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DeprecatedClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DeprecatedClass [page 1/1]
            @Deprecated("Use DeprecationTarget instead", ReplaceWith("DeprecationTarget")) class DeprecatedClass {
                @Deprecated("Nested in deprecated") class Nested
            }
        """.trimIndent()
    }

    @Test
    fun `should render only first line of deprecation message`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.MultilineDeprecationTarget.f"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.MultilineDeprecationTarget.f [page 1/1]
            @Deprecated("First line. …") fun f()
        """.trimIndent()
    }

    @Test
    fun `should render first non-blank line of deprecation message`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.MultilineDeprecationTarget.g"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.MultilineDeprecationTarget.g [page 1/1]
            @Deprecated("Use f() instead.") fun g()
        """.trimIndent()
    }

    @Test
    fun `should not list property with getter deprecated with hidden level`() {
        // even though the property itself is deprecated with the WARNING level
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenGetterHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenGetterHolder [page 1/1]
            class HiddenGetterHolder {
                val visible: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not list companion object deprecated with hidden level`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenCompanionHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenCompanionHolder [page 1/1]
            class HiddenCompanionHolder {
                fun f()
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve member of companion object deprecated with hidden level through outer class`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenCompanionHolder.create") == null)
    }

    @Test
    fun `should render deprecation of companion object member found by outer class member lookup`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DeprecationTarget.create"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DeprecationTarget.create [page 1/1]
            companion object {
                @Deprecated("Use the constructor") fun create(): DeprecationTarget
            }
        """.trimIndent()
    }

    @Test
    fun `should render deprecation of companion object declaration found by outer class member lookup`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DeprecatedCompanionHolder.create"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DeprecatedCompanionHolder.create [page 1/1]
            @Deprecated("Use the constructor") companion object {
                fun create(): DeprecatedCompanionHolder
            }
        """.trimIndent()
    }

    @Test
    fun `should render deprecation of extension property found by file facade member lookup`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestDeprecationKt.deprecatedExtensionProperty"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestDeprecationKt.deprecatedExtensionProperty [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            @Deprecated("Gone", level = DeprecationLevel.ERROR) val DeprecationTarget.deprecatedExtensionProperty: Int
        """.trimIndent()
    }

    @Test
    fun `should render deprecation of extension found by outer class member lookup`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DeprecationTarget.deprecatedExtension"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DeprecationTarget.deprecatedExtension [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            @Deprecated("Use newMember()", ReplaceWith("newMember()")) fun DeprecationTarget.deprecatedExtension(): Int
        """.trimIndent()
    }

    @Test
    fun `should not resolve class deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenTopLevelHolder") == null)
    }

    @Test
    fun `should not resolve nested class of class deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenTopLevelHolder.NestedInHiddenClass") == null)
    }

    @Test
    fun `should not resolve member of object nested in class deprecated with hidden level`() {
        assert(
            resolver.resolve(
                "com.xemantic.kotlin.introspection.test.HiddenTopLevelHolder.NestedObjectInHiddenClass.fromObjectInHiddenClass"
            ) == null
        )
    }

    @Test
    fun `should not resolve generated serializer`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.LegacySerializableHolder.\$serializer") == null)
    }

    @Test
    fun `should not resolve companion object deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenCompanionHolder.Companion") == null)
    }

    @Test
    fun `should not resolve member of companion object deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenCompanionHolder.Companion.create") == null)
    }

    @Test
    fun `should not list named companion object and nested class deprecated with hidden level`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenNamedCompanionHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenNamedCompanionHolder [page 1/1]
            class HiddenNamedCompanionHolder {
                fun f()
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve named companion object deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenNamedCompanionHolder.Factory") == null)
    }

    @Test
    fun `should not resolve member of named companion object deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenNamedCompanionHolder.Factory.create") == null)
    }

    @Test
    fun `should not resolve member of named companion object deprecated with hidden level through outer class`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenNamedCompanionHolder.create") == null)
    }

    @Test
    fun `should not resolve nested class deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenNamedCompanionHolder.HiddenNested") == null)
    }

    @Test
    fun `should not resolve member of nested class deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenNamedCompanionHolder.HiddenNested.g") == null)
    }

    @Test
    fun `should not render deprecation of enum class constructor`() {
        // it is always private, called only by the entries
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DeprecatedConstructorEnum"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DeprecatedConstructorEnum [page 1/1]
            enum class DeprecatedConstructorEnum(val v: Int) {
                A
            }
        """.trimIndent()
    }

    @Test
    fun `should render deprecation of property declared in primary constructor`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DeprecatedConstructorProperty"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DeprecatedConstructorProperty [page 1/1]
            class DeprecatedConstructorProperty(@Deprecated("Use baz", level = DeprecationLevel.ERROR) val bar: Int) {
                val baz: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should render hidden property of data class constructor with its deprecation`() {
        // the parameter must declare a property, which cannot be used
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenPropertyDataClass", pageSize = 1
        ) should {
            have(
                lines()[1] == "data class HiddenPropertyDataClass(" +
                    "@Deprecated(\"Replaced\", level = DeprecationLevel.HIDDEN) val a: Int, val b: Int) {"
            )
        }
    }

    @Test
    fun `should render hidden property of value class constructor with its deprecation`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenPropertyValueClass", pageSize = 1
        ) should {
            have(
                lines()[1] == "@JvmInline value class HiddenPropertyValueClass(" +
                    "@Deprecated(\"Replaced\", level = DeprecationLevel.HIDDEN) val raw: Int) {"
            )
        }
    }

    @Test
    fun `should resolve deprecated member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DeprecationTarget.errorMember"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DeprecationTarget.errorMember [page 1/1]
            @Deprecated("Use newMember()", ReplaceWith("newMember()"), level = DeprecationLevel.ERROR) fun errorMember(): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve deprecated top-level function`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestDeprecationKt.deprecationTopLevel"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestDeprecationKt.deprecationTopLevel [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            @Deprecated("Use deprecationTopLevel(value)", ReplaceWith("deprecationTopLevel(0)")) fun deprecationTopLevel(): Int
            fun deprecationTopLevel(value: Int): Int
        """.trimIndent()
    }

    @Test
    fun `should render deprecation of interface properties`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.DeprecationInterface"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.DeprecationInterface [page 1/1]
            interface DeprecationInterface {
                @Deprecated("Abstract") val abstractProperty: Int
                @Deprecated("With getter") open val propertyWithGetter: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should render deprecation of interface properties compiled without jvm default`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsDeprecatedProperties"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsDeprecatedProperties [page 1/1]
            interface DefaultImplsDeprecatedProperties {
                @Deprecated("Abstract") val abstractProperty: Int
                @Deprecated("With getter") open val propertyWithGetter: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should render deprecation level of stdlib extension deprecated since Kotlin version`() {
        // @DeprecatedSinceKotlin(warningSince = "1.5", errorSince = "2.1") tells the level for the stdlib of the classpath
        resolver.resolve("kotlin.String.toUpperCase") sameAsKotlin """
            // kotlin.String.toUpperCase [page 1/1]
            // from kotlin.text
            @Deprecated("Use uppercase() instead.", ReplaceWith("uppercase(Locale.getDefault())"), level = DeprecationLevel.ERROR) inline fun String.toUpperCase(): String
            @Deprecated("Use uppercase() instead.", ReplaceWith("uppercase(locale)"), level = DeprecationLevel.ERROR) inline fun String.toUpperCase(locale: java.util.Locale): String
        """.trimIndent()
    }

    @Test
    fun `should render deprecation of built-in member`() {
        resolver.resolve("kotlin.Long.toChar") sameAsKotlin """
            // kotlin.Long.toChar [page 1/1]
            @Deprecated("Direct conversion to Char is deprecated. Use toInt().toChar() or Char constructor instead.", ReplaceWith("this.toInt().toChar()"), level = DeprecationLevel.ERROR) override fun toChar(): Char
        """.trimIndent()
    }

    @Test
    fun `should not render primary constructor deprecated with hidden level`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenOnlyConstructor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenOnlyConstructor [page 1/1]
            class HiddenOnlyConstructor private constructor() {
                val a: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve only member overloads not deprecated with hidden level`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenDeprecationTarget.member"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenDeprecationTarget.member [page 1/1]
            fun member(): Int
        """.trimIndent()
    }

    @Test
    fun `should not resolve member function deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenDeprecationTarget.hiddenMember") == null)
    }

    @Test
    fun `should not resolve member property deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenDeprecationTarget.hiddenProperty") == null)
    }

    @Test
    fun `should resolve only companion object overloads not deprecated with hidden level as class member`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenDeprecationTarget.create"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenDeprecationTarget.create [page 1/1]
            companion object {
                fun create(): HiddenDeprecationTarget
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve companion object constant deprecated with hidden level as class member`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenDeprecationTarget.HIDDEN_CONSTANT") == null)
    }

    @Test
    fun `should not resolve companion object field deprecated with hidden level as class member`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenDeprecationTarget.hiddenField") == null)
    }

    @Test
    fun `should not resolve extension property deprecated with hidden level`() {
        assert(
            resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenDeprecationTarget.hiddenExtensionProperty") == null
        )
    }

    @Test
    fun `should resolve only extension overloads not deprecated with hidden level`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenDeprecationTarget.extension"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenDeprecationTarget.extension [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun HiddenDeprecationTarget.extension(): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve only top-level overloads not deprecated with hidden level`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestHiddenDeprecationKt.hiddenDeprecationTopLevel"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestHiddenDeprecationKt.hiddenDeprecationTopLevel [page 1/1]
            // from com.xemantic.kotlin.introspection.test
            fun hiddenDeprecationTopLevel(value: Int): Int
        """.trimIndent()
    }

    @Test
    fun `should not resolve member of built-in type deprecated with hidden level`() {
        // the getFirst() of java.util.List since JDK 21, hidden by Kotlin in favor of the first() extension
        assert(resolver.resolve("kotlin.collections.List.getFirst") == null)
    }

    @Test
    fun `should not list members of built-in type deprecated with hidden level`() {
        resolver.resolve("kotlin.collections.List", pageSize = 10000) should {
            have(lines().none { "fun getFirst()" in it })
            have(lines().none { "fun getLast()" in it })
        }
    }

    @Test
    fun `should not resolve JVM member of built-in type which the compiler hides`() {
        // the getDeclaringClass() of java.lang.Enum is hidden by Kotlin in favor of the declaringJavaClass extension,
        // while kotlin-reflect lists it without telling
        assert(resolver.resolve("kotlin.Enum.getDeclaringClass") == null)
    }

    @Test
    fun `should not list JVM member of built-in type which the compiler hides`() {
        resolver.resolve("kotlin.Enum", pageSize = 10000) should {
            have(lines().none { "getDeclaringClass" in it })
        }
    }

    @Test
    fun `should list extension in body of built-in type in place of JVM member which the compiler hides`() {
        // the isEmpty() of java.lang.CharSequence since JDK 15, charSequence.isEmpty() calls the CharSequence.isEmpty() extension
        assumeTrue(runCatching { CharSequence::class.java.getMethod("isEmpty") }.isSuccess, "JDK 15+")
        resolver.resolve("kotlin.CharSequence", pageSize = 10000) should {
            have("    inline fun isEmpty(): Boolean" in lines())
            have(lines().none { it.endsWith(" fun isEmpty(): Boolean") && "inline" !in it })
        }
    }

    @Test
    fun `should list extension of built-in type in place of JVM member which the compiler hides`() {
        // the reversed() of java.util.List since JDK 21, list.reversed() calls the Iterable<T>.reversed() extension
        assumeTrue(runCatching { List::class.java.getMethod("reversed") }.isSuccess, "JDK 21+")
        resolver.resolve("kotlin.collections.List", pageSize = 10000) should {
            have("    open fun reversed(): List<E>" !in lines())
            have("fun <T> Iterable<T>.reversed(): List<T>" in lines())
        }
    }

    @Test
    fun `should not resolve stdlib extensions hidden since an older Kotlin version`() {
        // the CharSequence.max() returning Char? is @DeprecatedSinceKotlin(hiddenSince = "1.6"),
        // replaced by maxOrNull(), and by the max() returning Char
        resolver.resolve("kotlin.CharSequence.max") sameAsKotlin """
            // kotlin.CharSequence.max [page 1/1]
            // from kotlin.text
            fun CharSequence.max(): Char
        """.trimIndent()
    }

    @Test
    fun `should resolve built-in type with members deprecated without explicit level`() {
        // kotlin-reflect cannot tell the default level of the @Deprecated of the built-in Long.toChar()
        resolver.resolve("kotlin.Long", pageSize = 10000) should {
            have(lines().any { it.endsWith(" override fun toChar(): Char") })
        }
    }

    @Test
    fun `should resolve member of built-in type deprecated without explicit level`() {
        resolver.resolve("kotlin.Number.toChar") sameAsKotlin """
            // kotlin.Number.toChar [page 1/1]
            @Deprecated("Direct conversion to Char is deprecated. Use toInt().toChar() or Char constructor instead. …", ReplaceWith("this.toInt().toChar()"), level = DeprecationLevel.ERROR) open fun toChar(): Char
        """.trimIndent()
    }

    @Test
    fun `should resolve every built-in type and all its members`() {
        DefaultKotlinIntrospector.builtinTypes.forEach { (name, kClass) ->
            // when
            val signature = resolver.resolve(name)
            val memberSignatures = kClass.members.map { it.name }.distinct().associateWith { member ->
                resolver.resolve("$name.$member")
            }

            // then
            assert(signature != null)
            assert(memberSignatures.isNotEmpty())
        }
    }

    // built-in types rendered from their declarations in the .kotlin_builtins resources

    @Test
    fun `should resolve Unit as object`() {
        resolver.resolve("kotlin.Unit") sameAsKotlin """
            // kotlin.Unit [page 1/1]
            object Unit {
                override fun toString(): String
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve abstract built-in class with its modality`() {
        resolver.resolve("kotlin.Number", pageSize = 1) should {
            have(lines()[1] == "abstract class Number {")
        }
    }

    @Test
    fun `should resolve Enum as not constructible, with its properties in the class body`() {
        // its constructor is called only by the enum classes, a class cannot extend it directly
        resolver.resolve("kotlin.Enum", pageSize = 10000) should {
            have(lines()[1] == "abstract class Enum<E : Enum<E>> private constructor() : Comparable<E> {")
            have("    val name: String" in lines())
            have("    val ordinal: Int" in lines())
        }
    }

    @Test
    fun `should resolve built-in type without constructor callable from user code as not constructible`() {
        resolver.resolve("kotlin.Int", pageSize = 1) should {
            have(lines()[1] == "class Int private constructor() : Number, Comparable<Int> {")
        }
    }

    @Test
    fun `should resolve Nothing as not constructible`() {
        resolver.resolve("kotlin.Nothing") sameAsKotlin """
            // kotlin.Nothing [page 1/1]
            class Nothing private constructor()
        """.trimIndent()
    }

    @Test
    fun `should resolve built-in array type with its constructors`() {
        // the property declared in the class body cannot be told apart from a constructor property
        resolver.resolve("kotlin.IntArray", pageSize = 10000) should {
            have(lines()[1] == "class IntArray(val size: Int) {")
            have("    constructor(size: Int, init: (Int) -> Int)" in lines())
        }
    }

    @Test
    fun `should resolve built-in open class with its constructors`() {
        resolver.resolve("kotlin.Throwable", pageSize = 10000) should {
            have(lines()[1] == "open class Throwable(open val message: String?, open val cause: Throwable?) {")
            have("    constructor(message: String?)" in lines())
            have("    constructor(cause: Throwable?)" in lines())
            have("    constructor()" in lines())
        }
    }

    @Test
    fun `should list companion object of built-in type`() {
        resolver.resolve("kotlin.Int", pageSize = 10000) should {
            have("    companion object {" in lines())
            have("        const val MAX_VALUE: Int" in lines())
        }
    }

    @Test
    fun `should resolve companion object member of built-in type by the name of the type`() {
        resolver.resolve("kotlin.Int.MAX_VALUE") sameAsKotlin """
            // kotlin.Int.MAX_VALUE [page 1/1]
            companion object {
                const val MAX_VALUE: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve member of unsigned type overriding Comparable member`() {
        // the unsigned types have no declarations in the .kotlin_builtins resources, but their own Kotlin metadata
        resolver.resolve("kotlin.UInt.compareTo") should {
            have("override inline operator fun compareTo(other: UInt): Int" in lines())
        }
    }

    @Test
    fun `should resolve JVM-only member of built-in type overriding JVM member of its supertype`() {
        // String.chars() is declared only by java.lang.String, overriding the one of java.lang.CharSequence
        resolver.resolve("kotlin.String.chars") sameAsKotlin """
            // kotlin.String.chars [page 1/1]
            override fun chars(): java.util.stream.IntStream
        """.trimIndent()
    }

    @Test
    fun `should not list in body of built-in type extension shadowed by JVM member accepting null`() {
        // printStackTrace(PrintStream) of java.lang.Throwable, taking the platform type PrintStream!, is called instead
        resolver.resolve("kotlin.Throwable", pageSize = 10000) should {
            have("inline fun Throwable.printStackTrace(stream: java.io.PrintStream)" in lines())
            have("    inline fun printStackTrace(stream: java.io.PrintStream)" !in lines())
        }
    }

    @Test
    fun `should resolve JVM-only members of open built-in class as open`() {
        resolver.resolve("kotlin.Throwable", pageSize = 10000) should {
            have("    open fun fillInStackTrace(): Throwable" in lines())
        }
    }

    @Test
    fun `should list each declaration of every built-in type once`() {
        DefaultKotlinIntrospector.builtinTypes.keys.forEach { name ->
            // when
            val lines = resolver.resolve(name, pageSize = 10000)!!.lines()

            // then
            // the same signature might be declared in two packages, like KClass.cast() of kotlin.reflect.full
            var section = "body"
            val declarations = lines.drop(1).mapNotNull { line ->
                if (line.startsWith("// from ")) section = line
                if (line == "}" || line.startsWith("// ")) null else section to line
            }
            val duplicates = declarations.groupBy { it }.filterValues { it.size > 1 }.keys
            assert(duplicates.isEmpty())
        }
    }

    @Test
    fun `should list extensions of built-in type with nullable receiver outside of the class body`() {
        resolver.resolve("kotlin.String", pageSize = 10000) should {
            have("    inline fun orEmpty(): String" !in lines())
            have("inline fun String?.orEmpty(): String" in lines())
        }
    }

    @Test
    fun `should list extensions of built-in type with type arguments of their own outside of the class body`() {
        resolver.resolve("kotlin.collections.Iterable", pageSize = 10000) should {
            have("    fun average(): Double" !in lines())
            have("fun Iterable<Int>.average(): Double" in lines())
        }
    }

    @Test
    fun `should list extensions of built-in type with star-projected and own type arguments outside of the class body`() {
        resolver.resolve("kotlin.collections.Map", pageSize = 10000) should {
            have("inline fun <K> Map<out K, *>.containsKey(key: K): Boolean" in lines())
        }
    }

    @Test
    fun `should list extensions of built-in type on in-projected receiver in the class body only when accepting the same arguments`() {
        // T of MutableCollection<in T> stands for any subtype of E, which Iterable<T> accepts as Iterable<E> does,
        // unlike the invariant Array<T>, rendered as Array<E>, narrower than it is
        resolver.resolve("kotlin.collections.MutableCollection", pageSize = 10000) should {
            have("    inline operator fun plusAssign(element: E)" in lines())
            have("    inline operator fun plusAssign(elements: Iterable<E>)" in lines())
            have("    inline operator fun plusAssign(elements: Array<E>)" !in lines())
            have("inline operator fun <T> MutableCollection<in T>.plusAssign(elements: Array<T>)" in lines())
        }
    }

    @Test
    fun `should list extensions of built-in type on out-projected receiver in the class body only when accepting the same arguments`() {
        // K of Map<out K, V> stands for any supertype of the K of the class, which a function taking it accepts,
        // unlike a parameter of the type K itself, rendered as K, narrower than it is
        resolver.resolve("kotlin.collections.Map", pageSize = 10000) should {
            have("    inline fun forEach(action: (Map.Entry<K, V>) -> Unit)" in lines())
            have("    operator fun minus(key: K): Map<K, V>" !in lines())
            have("operator fun <K, V> Map<out K, V>.minus(key: K): Map<K, V>" in lines())
        }
    }

    @Test
    fun `should resolve member of built-in type inherited from its supertype`() {
        // MutableList.get is List.get, rendered as declared there, with the type parameter of List
        resolver.resolve(
            "kotlin.collections.MutableList.get"
        ) sameAsKotlin """
            // kotlin.collections.MutableList.get [page 1/1]
            // inherited from kotlin.collections.List
            operator fun get(index: Int): E
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited member of the most specific return type among unrelated supertypes`() {
        // both List.iterator() and MutableCollection.iterator() override Collection.iterator(),
        // the one of MutableCollection returns the more specific MutableIterator, like the intersection override
        resolver.resolve(
            "kotlin.collections.MutableList.iterator"
        ) sameAsKotlin """
            // kotlin.collections.MutableList.iterator [page 1/1]
            // inherited from kotlin.collections.MutableCollection
            override operator fun iterator(): MutableIterator<E>
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited overload next to declared overload of standard library class`() {
        // AbstractMutableList declares add(index, element), add(element) is inherited from MutableList
        resolver.resolve("kotlin.collections.AbstractMutableList.add", pageSize = 10000) should {
            have("abstract override fun add(index: Int, element: E)" in lines())
            have("// inherited from kotlin.collections.MutableList" in lines())
            have("override fun add(element: E): Boolean" in lines())
        }
    }

    @Test
    fun `should resolve inherited member of built-in type before its extensions of the same name`() {
        resolver.resolve("kotlin.collections.MutableList.contains", pageSize = 10000) should {
            have(lines()[1] == "// inherited from kotlin.collections.List")
            have(lines()[2] == "override operator fun contains(element: E): Boolean")
            have(lines().any { it.startsWith("// from ") })
        }
    }

    @Test
    fun `should resolve member of built-in type inherited from its supertype of another type parameter`() {
        resolver.resolve(
            "kotlin.collections.MutableIterator.next"
        ) sameAsKotlin """
            // kotlin.collections.MutableIterator.next [page 1/1]
            // inherited from kotlin.collections.Iterator
            operator fun next(): T
        """.trimIndent()
    }

    @Test
    fun `should resolve member of Any inherited by built-in type`() {
        resolver.resolve(
            "kotlin.String.hashCode"
        ) sameAsKotlin """
            // kotlin.String.hashCode [page 1/1]
            // inherited from kotlin.Any
            fun hashCode(): Int
        """.trimIndent()
    }

    @Test
    fun `should not list inherited members of built-in type`() {
        resolver.resolve("kotlin.collections.MutableList", pageSize = 10000) should {
            have("operator fun get(index: Int): E" !in lines().map { it.trim() })
        }
    }

    @Test
    fun `should resolve member of built-in companion object through the outer type`() {
        resolver.resolve(
            "kotlin.Int.MAX_VALUE"
        ) sameAsKotlin """
            // kotlin.Int.MAX_VALUE [page 1/1]
            companion object {
                const val MAX_VALUE: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should list extensions of built-in type on star-projected receiver in the class body`() {
        resolver.resolve("kotlin.collections.Collection", pageSize = 10000) should {
            have("    val indices: IntRange" in lines())
        }
    }

    @Test
    fun `should list extensions of built-in type with type parameters bounded like the ones of the class in the class body`() {
        resolver.resolve("kotlin.Enum", pageSize = 10000) should {
            have("    val declaringJavaClass: java.lang.Class<E>" in lines())
        }
    }

    @Test
    fun `should list extensions of built-in type shadowed by its members outside of the class body`() {
        // the member is called instead, the extension would read like its duplicate in the class body
        resolver.resolve("kotlin.collections.Map", pageSize = 10000) should {
            have("    operator fun get(key: K): V?" in lines())
            have("    inline operator fun get(key: K): V?" !in lines())
            have("inline operator fun <K, V> Map<out K, V>.get(key: K): V?" in lines())
        }
    }

    @Test
    fun `should list same extensions of built-in type from two packages outside of the class body`() {
        // only the source tells them apart, which the class body does not show
        resolver.resolve("kotlin.reflect.KClass", pageSize = 10000) should {
            have("    fun cast(value: Any?): T" !in lines())
            have(lines().count { it == "fun <T : Any> KClass<T>.cast(value: Any?): T" } == 2)
        }
    }

    @Test
    fun `should list extensions of generic built-in type in the class body with the type parameters of the class`() {
        resolver.resolve("kotlin.collections.MutableList", pageSize = 10000) should {
            have("    inline fun fill(value: E)" in lines())
            have("    fun asReversed(): MutableList<E>" in lines())
        }
    }

    @Test
    fun `should not list interface properties deprecated with hidden level`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenDeprecationInterface"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenDeprecationInterface [page 1/1]
            interface HiddenDeprecationInterface {
                val visible: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve interface property with getter deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenDeprecationInterface.hiddenWithGetter") == null)
    }

    @Test
    fun `should not list interface properties deprecated with hidden level compiled without jvm default`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsHiddenProperties"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsHiddenProperties [page 1/1]
            interface DefaultImplsHiddenProperties {
                val visible: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should render var with setter deprecated with hidden level as val`() {
        // the property can still be read, but it cannot be assigned from the source code
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenSetterHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenSetterHolder [page 1/1]
            class HiddenSetterHolder(val inConstructor: Int) {
                val inBody: Int
            }
            // from com.xemantic.kotlin.introspection.test
            val HiddenSetterHolder.hiddenSetterExtension: Int
        """.trimIndent()
    }

    @Test
    fun `should render override of var with setter deprecated with hidden level as var with its deprecation`() {
        // a val cannot override a var, so the annotation tells it cannot be assigned
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenSetterOverride"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenSetterOverride [page 1/1]
            class HiddenSetterOverride : HiddenSetterOverrideBase {
                @set:Deprecated("replaced", level = DeprecationLevel.HIDDEN) override var value: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should render protected override of var with setter deprecated with hidden level without setter line`() {
        // the setter has the visibility of the property, so no line implies a setter wider than the property
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProtectedHiddenSetterOverride"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProtectedHiddenSetterOverride [page 1/1]
            open class ProtectedHiddenSetterOverride : ProtectedHiddenSetterOverrideBase {
                @set:Deprecated("replaced", level = DeprecationLevel.HIDDEN) protected override var value: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve member var with setter deprecated with hidden level as val`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenSetterHolder.inBody"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenSetterHolder.inBody [page 1/1]
            val inBody: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve constructor var with setter deprecated with hidden level as val`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenSetterHolder.inConstructor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenSetterHolder.inConstructor [page 1/1]
            val inConstructor: Int
        """.trimIndent()
    }

    @Test
    fun `should render var with getter deprecated with hidden level as assignable only`() {
        // the property cannot be read, but it can still be assigned from the source code, like holder.inBody = 1,
        // unless its setter is not part of the API, or hidden too
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenGetterVarHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenGetterVarHolder [page 1/1]
            class HiddenGetterVarHolder(@get:Deprecated("replaced", level = DeprecationLevel.HIDDEN) var inConstructor: Int) {
                @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN) var inBody: Int
                @Deprecated("deprecated") @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN) var deprecatedWithHiddenGetter: Int
                @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN) @set:Deprecated("Assign y", level = DeprecationLevel.ERROR) var withErrorSetter: Int
            }
            // from com.xemantic.kotlin.introspection.test
            @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN) var HiddenGetterVarHolder.hiddenGetterExtension: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve var with getter deprecated with hidden level and setter deprecated with error level`() {
        // still listed, like any declaration deprecated with the ERROR level, which can be referred to, but not called
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenGetterVarHolder.withErrorSetter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenGetterVarHolder.withErrorSetter [page 1/1]
            @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN) @set:Deprecated("Assign y", level = DeprecationLevel.ERROR) var withErrorSetter: Int
        """.trimIndent()
    }

    @Test
    fun `should render deprecation of accessors of var apart from property`() {
        // only the reading, or only the assigning, is deprecated, while a var which cannot be assigned
        // from the outside reads as a val, whose reading is deprecated as a whole
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.AccessorDeprecationHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.AccessorDeprecationHolder [page 1/1]
            class AccessorDeprecationHolder(@get:Deprecated("Read y", level = DeprecationLevel.ERROR) var inConstructor: Int) {
                @get:Deprecated("Read y", level = DeprecationLevel.ERROR) var errorGetter: Int
                @get:Deprecated("Read y") var warningGetter: Int
                @set:Deprecated("Assign y") var warningSetter: Int
                @set:Deprecated("Assign y", level = DeprecationLevel.ERROR) var errorSetter: Int
                @get:Deprecated("Read y") @set:Deprecated("Assign y", level = DeprecationLevel.ERROR) var bothAccessors: Int
                @Deprecated("Use y") @set:Deprecated("Assign y", level = DeprecationLevel.ERROR) var deprecatedWithErrorSetter: Int
                @Deprecated("Read y", level = DeprecationLevel.ERROR) val withPrivateSetter: Int
                @Deprecated("Use z", ReplaceWith("z")) @get:Deprecated("Read y", level = DeprecationLevel.ERROR) val deprecatedWithPrivateSetter: Int
                @Deprecated("Use z", ReplaceWith("z")) @get:Deprecated("Read y", level = DeprecationLevel.ERROR) val deprecatedVal: Int
            }
            // from com.xemantic.kotlin.introspection.test
            @set:Deprecated("Assign y", level = DeprecationLevel.ERROR) var AccessorDeprecationHolder.errorSetterExtension: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve var whose setter cannot be called deprecated itself and with getter as val with both deprecations`() {
        // the hint of the property deprecation to use z is kept next to the one of reading it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.AccessorDeprecationHolder.deprecatedWithPrivateSetter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.AccessorDeprecationHolder.deprecatedWithPrivateSetter [page 1/1]
            @Deprecated("Use z", ReplaceWith("z")) @get:Deprecated("Read y", level = DeprecationLevel.ERROR) val deprecatedWithPrivateSetter: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve val deprecated itself and with getter with both deprecations`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.AccessorDeprecationHolder.deprecatedVal"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.AccessorDeprecationHolder.deprecatedVal [page 1/1]
            @Deprecated("Use z", ReplaceWith("z")) @get:Deprecated("Read y", level = DeprecationLevel.ERROR) val deprecatedVal: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve var with setter deprecated with error level`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.AccessorDeprecationHolder.errorSetter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.AccessorDeprecationHolder.errorSetter [page 1/1]
            @set:Deprecated("Assign y", level = DeprecationLevel.ERROR) var errorSetter: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited var with setter deprecated with error level`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.AccessorDeprecationSub.inheritedErrorSetter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.AccessorDeprecationSub.inheritedErrorSetter [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.AccessorDeprecationBase
            @set:Deprecated("Assign y", level = DeprecationLevel.ERROR) var inheritedErrorSetter: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve inherited var whose protected setter final subclass cannot call as val with deprecation of reading`() {
        // the setter, and its deprecation, are not part of the API of the final subclass
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.AccessorDeprecationSub.inheritedProtectedSetter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.AccessorDeprecationSub.inheritedProtectedSetter [page 1/1]
            // inherited from com.xemantic.kotlin.introspection.test.AccessorDeprecationBase
            @Deprecated("Read y", level = DeprecationLevel.ERROR) val inheritedProtectedSetter: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve member var with getter deprecated with hidden level`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenGetterVarHolder.inBody"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenGetterVarHolder.inBody [page 1/1]
            @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN) var inBody: Int
        """.trimIndent()
    }

    @Test
    fun `should resolve var deprecated itself and with getter deprecated with hidden level`() {
        // both annotations are rendered, as they are declared
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenGetterVarHolder.deprecatedWithHiddenGetter"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenGetterVarHolder.deprecatedWithHiddenGetter [page 1/1]
            @Deprecated("deprecated") @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN) var deprecatedWithHiddenGetter: Int
        """.trimIndent()
    }

    @Test
    fun `should render var with getter deprecated with hidden level and protected setter of open class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenGetterVarOpenHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenGetterVarOpenHolder [page 1/1]
            open class HiddenGetterVarOpenHolder {
                @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN) var withProtectedSetter: Int
                    protected set
            }
        """.trimIndent()
    }

    @Test
    fun `should render interface var with getter deprecated with hidden level`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenGetterVarInterface"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenGetterVarInterface [page 1/1]
            interface HiddenGetterVarInterface {
                @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN) var abstractVar: Int
                @Deprecated("deprecated") @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN) var deprecatedVar: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should render interface var with getter deprecated with hidden level compiled without jvm default`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsHiddenGetterVar"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.defaultimpls.DefaultImplsHiddenGetterVar [page 1/1]
            interface DefaultImplsHiddenGetterVar {
                @Deprecated("deprecated") @get:Deprecated("replaced", level = DeprecationLevel.HIDDEN) var deprecatedVar: Int
            }
        """.trimIndent()
    }

    @Test
    fun `should not resolve var with getter deprecated with hidden level and private setter`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenGetterVarHolder.withPrivateSetter") == null)
    }

    @Test
    fun `should not resolve var with getter and setter deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenGetterVarHolder.withHiddenSetter") == null)
    }

    @Test
    fun `should not resolve val with getter deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenGetterVarHolder.hiddenGetterVal") == null)
    }

    @Test
    fun `should not resolve override var with getter deprecated with hidden level and protected setter of final class`() {
        // the setter can be called only by the subclasses of the base class, while the getter hides the overridden one
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenGetterOverrideHolder.overridden") == null)
    }

    @Test
    fun `should render final class without override var with getter deprecated with hidden level and protected setter`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenGetterOverrideHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenGetterOverrideHolder [page 1/1]
            class HiddenGetterOverrideHolder : HiddenGetterOverrideBase
        """.trimIndent()
    }

    @Test
    fun `should not resolve function overridden by override deprecated with hidden level`() {
        // the hidden override hides the function it overrides too, which is therefore not inherited
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenOverride.member") == null)
    }

    @Test
    fun `should not resolve property overridden by override deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenOverride.property") == null)
    }

    @Test
    fun `should not resolve members overridden by inherited overrides deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenOverrideSub.member") == null)
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenOverrideSub.property") == null)
    }

    @Test
    fun `should render annotation class property deprecated with hidden level as parameter only`() {
        // @Deprecated cannot annotate the parameter, only the property is hidden, the argument can still be passed
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenDeprecationAnnotation"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenDeprecationAnnotation [page 1/1]
            annotation class HiddenDeprecationAnnotation(val visible: Int = 0, hidden: Int = 1)
        """.trimIndent()
    }

    @Test
    fun `should render annotation class property deprecated with hidden level without default value as parameter`() {
        // the argument must still be passed, like @HiddenDeprecationAnnotationWithoutDefault(1, 2)
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenDeprecationAnnotationWithoutDefault"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenDeprecationAnnotationWithoutDefault [page 1/1]
            annotation class HiddenDeprecationAnnotationWithoutDefault(val visible: Int, hidden: Int)
        """.trimIndent()
    }

    @Test
    fun `should render annotation class with all properties deprecated with hidden level`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenDeprecationAnnotationAllHidden"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenDeprecationAnnotationAllHidden [page 1/1]
            annotation class HiddenDeprecationAnnotationAllHidden(hidden: Int = 1)
        """.trimIndent()
    }

    @Test
    fun `should not resolve annotation class property deprecated with hidden level`() {
        assert(resolver.resolve("com.xemantic.kotlin.introspection.test.HiddenDeprecationAnnotation.hidden") == null)
    }

}
