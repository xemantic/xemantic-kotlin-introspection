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
import kotlin.test.Test
import kotlin.test.assertFailsWith

// we keep this dependency statically initialized, so that classpath scanning happens only once
// given
val resolver = KotlinIntrospector()

class KotlinIntrospectorTest {

    @Test
    fun `should resolve String as known stdlib class`() {
        resolver.resolve(
            "kotlin.String"
        ) sameAsKotlin """
            // kotlin.String [page 1/42]
            class String : Comparable<String>, CharSequence {
                fun byteInputStream(charset: java.nio.charset.Charset): java.io.ByteArrayInputStream
                fun capitalize(): String
                fun capitalize(locale: java.util.Locale): String
                fun chars(): java.util.stream.IntStream
                fun codePointAt(index: Int): Int
                fun codePointBefore(index: Int): Int
                fun codePointCount(beginIndex: Int, endIndex: Int): Int
                fun codePoints(): java.util.stream.IntStream
                operator fun compareTo(other: String): Int
                fun compareTo(other: String, ignoreCase: Boolean): Int
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
                fun contentEquals(charSequence: CharSequence): Boolean
                fun contentEquals(stringBuilder: java.lang.StringBuffer): Boolean
                fun decapitalize(): String
                fun decapitalize(locale: java.util.Locale): String
                fun describeConstable(): java.util.Optional<String>
                fun drop(n: Int): String
                fun dropLast(n: Int): String
                fun dropLastWhile(predicate: Function1<Char, Boolean>): String
                fun dropWhile(predicate: Function1<Char, Boolean>): String
                fun encodeToByteArray(): ByteArray
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
                fun encodeToByteArray(startIndex: Int, endIndex: Int, throwOnInvalidSequence: Boolean): ByteArray
                fun endsWith(suffix: String, ignoreCase: Boolean): Boolean
                operator fun equals(other: Any?): Boolean
                fun equals(other: String?, ignoreCase: Boolean): Boolean
                fun filter(predicate: Function1<Char, Boolean>): String
                fun filterIndexed(predicate: Function2<Int, Char, Boolean>): String
                fun filterNot(predicate: Function1<Char, Boolean>): String
                fun format(args: Array<out Any?>): String
                fun format(locale: java.util.Locale?, args: Array<out Any?>): String
                operator fun get(index: Int): Char
            }
        """.trimIndent()
    }

    @Test
    fun `should resolve String as known stdlib class - page 19 (transition page with members and extensions)`() {
        // page 19 shows the last class member and first extensions
        resolver.resolve(
            "kotlin.String",
            page = 19
        ) sameAsKotlin """
            // kotlin.String [page 19/42]
            class String : Comparable<String>, CharSequence {
                fun uppercase(locale: java.util.Locale): String
            }
            // from kotlin
            inline infix fun <T> Comparable<T>.compareTo(other: T): Int
            // from kotlin.text
            val CharSequence.indices: IntRange
            val CharSequence.lastIndex: Int
            inline fun CharSequence.all(predicate: Function1<Char, Boolean>): Boolean
            fun CharSequence.any(): Boolean
            inline fun CharSequence.any(predicate: Function1<Char, Boolean>): Boolean
            fun CharSequence.asIterable(): Iterable<Char>
            fun CharSequence.asSequence(): Sequence<Char>
            inline fun <K, V> CharSequence.associate(transform: Function1<Char, Pair<K, V>>): Map<K, V>
        """.trimIndent()
    }

    @Test
    fun `should resolve String as known stdlib class - page 42 (the last)`() {
        // extension-only pages omit the empty class body
        resolver.resolve(
            "kotlin.String",
            page = 42
        ) sameAsKotlin """
            // kotlin.String [page 42/42]
            // from kotlin.text
            fun CharSequence.zipWithNext(): List<Pair<Char, Char>>
            inline fun <R> CharSequence.zipWithNext(transform: Function2<Char, Char, R>): List<R>
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
                fun byteInputStream(charset: java.nio.charset.Charset): java.io.ByteArrayInputStream
                fun capitalize(): String
                fun capitalize(locale: java.util.Locale): String
                fun chars(): java.util.stream.IntStream
                fun codePointAt(index: Int): Int
                fun codePointBefore(index: Int): Int
                fun codePointCount(beginIndex: Int, endIndex: Int): Int
                fun codePoints(): java.util.stream.IntStream
                operator fun compareTo(other: String): Int
                fun compareTo(other: String, ignoreCase: Boolean): Int
                fun contentEquals(charSequence: CharSequence): Boolean
                fun contentEquals(stringBuilder: java.lang.StringBuffer): Boolean
                fun decapitalize(): String
                fun decapitalize(locale: java.util.Locale): String
                fun describeConstable(): java.util.Optional<String>
                fun drop(n: Int): String
                fun dropLast(n: Int): String
                fun dropLastWhile(predicate: Function1<Char, Boolean>): String
                fun dropWhile(predicate: Function1<Char, Boolean>): String
                fun encodeToByteArray(): ByteArray
                fun encodeToByteArray(startIndex: Int, endIndex: Int, throwOnInvalidSequence: Boolean): ByteArray
                fun endsWith(suffix: String, ignoreCase: Boolean): Boolean
                operator fun equals(other: Any?): Boolean
                fun equals(other: String?, ignoreCase: Boolean): Boolean
                fun filter(predicate: Function1<Char, Boolean>): String
                fun filterIndexed(predicate: Function2<Int, Char, Boolean>): String
                fun filterNot(predicate: Function1<Char, Boolean>): String
                fun format(args: Array<out Any?>): String
                fun format(locale: java.util.Locale?, args: Array<out Any?>): String
                operator fun get(index: Int): Char
                fun hashCode(): Int
                fun hexToByte(format: HexFormat): Byte
                fun hexToByteArray(format: HexFormat): ByteArray
                fun hexToInt(format: HexFormat): Int
                fun hexToLong(format: HexFormat): Long
                fun hexToShort(format: HexFormat): Short
                fun hexToUByte(format: HexFormat): UByte
                fun hexToUByteArray(format: HexFormat): UByteArray
                fun hexToUInt(format: HexFormat): UInt
                fun hexToULong(format: HexFormat): ULong
                fun hexToUShort(format: HexFormat): UShort
                fun intern(): String
                fun isEmpty(): Boolean
                fun isLocalClassName(): Boolean
                val length: Int
                fun lowercase(): String
                fun lowercase(locale: java.util.Locale): String
                fun offsetByCodePoints(index: Int, codePointOffset: Int): Int
                fun orEmpty(): String
                fun padEnd(length: Int, padChar: Char): String
                fun padStart(length: Int, padChar: Char): String
                fun partition(predicate: Function1<Char, Boolean>): Pair<String, String>
                operator fun plus(other: Any?): String
                fun prependIndent(indent: String): String
                fun reader(): java.io.StringReader
                fun regionMatches(thisOffset: Int, other: String, otherOffset: Int, length: Int, ignoreCase: Boolean): Boolean
                fun removePrefix(prefix: CharSequence): String
                fun removeRange(startIndex: Int, endIndex: Int): String
                fun removeRange(range: IntRange): String
                fun removeSuffix(suffix: CharSequence): String
                fun removeSurrounding(prefix: CharSequence, suffix: CharSequence): String
                fun removeSurrounding(delimiter: CharSequence): String
                fun replace(oldChar: Char, newChar: Char, ignoreCase: Boolean): String
                fun replace(oldValue: String, newValue: String, ignoreCase: Boolean): String
                fun replaceAfter(delimiter: Char, replacement: String, missingDelimiterValue: String): String
                fun replaceAfter(delimiter: String, replacement: String, missingDelimiterValue: String): String
                fun replaceAfterLast(delimiter: String, replacement: String, missingDelimiterValue: String): String
                fun replaceAfterLast(delimiter: Char, replacement: String, missingDelimiterValue: String): String
                fun replaceBefore(delimiter: Char, replacement: String, missingDelimiterValue: String): String
                fun replaceBefore(delimiter: String, replacement: String, missingDelimiterValue: String): String
                fun replaceBeforeLast(delimiter: Char, replacement: String, missingDelimiterValue: String): String
                fun replaceBeforeLast(delimiter: String, replacement: String, missingDelimiterValue: String): String
                fun replaceFirst(oldChar: Char, newChar: Char, ignoreCase: Boolean): String
                fun replaceFirst(oldValue: String, newValue: String, ignoreCase: Boolean): String
                fun replaceFirstChar(transform: Function1<Char, Char>): String
                fun replaceFirstChar(transform: Function1<Char, CharSequence>): String
                fun replaceIndent(newIndent: String): String
                fun replaceIndentByMargin(newIndent: String, marginPrefix: String): String
                fun replaceRange(startIndex: Int, endIndex: Int, replacement: CharSequence): String
                fun replaceRange(range: IntRange, replacement: CharSequence): String
                fun reversed(): String
                fun slice(indices: IntRange): String
                fun slice(indices: Iterable<Int>): String
                fun startsWith(prefix: String, ignoreCase: Boolean): Boolean
                fun startsWith(prefix: String, startIndex: Int, ignoreCase: Boolean): Boolean
                fun strip(): String
                fun stripIndent(): String
                fun stripLeading(): String
                fun stripTrailing(): String
                fun subSequence(startIndex: Int, endIndex: Int): CharSequence
                fun subSequence(start: Int, end: Int): CharSequence
                fun substring(startIndex: Int): String
                fun substring(startIndex: Int, endIndex: Int): String
                fun substring(range: IntRange): String
                fun substringAfter(delimiter: Char, missingDelimiterValue: String): String
                fun substringAfter(delimiter: String, missingDelimiterValue: String): String
                fun substringAfterLast(delimiter: Char, missingDelimiterValue: String): String
                fun substringAfterLast(delimiter: String, missingDelimiterValue: String): String
                fun substringBefore(delimiter: Char, missingDelimiterValue: String): String
                fun substringBefore(delimiter: String, missingDelimiterValue: String): String
                fun substringBeforeLast(delimiter: Char, missingDelimiterValue: String): String
                fun substringBeforeLast(delimiter: String, missingDelimiterValue: String): String
                fun take(n: Int): String
                fun takeLast(n: Int): String
                fun takeLastWhile(predicate: Function1<Char, Boolean>): String
                fun takeWhile(predicate: Function1<Char, Boolean>): String
                fun toBigDecimal(): java.math.BigDecimal
                fun toBigDecimal(mathContext: java.math.MathContext): java.math.BigDecimal
                fun toBigDecimalOrNull(): java.math.BigDecimal?
                fun toBigDecimalOrNull(mathContext: java.math.MathContext): java.math.BigDecimal?
                fun toBigInteger(): java.math.BigInteger
                fun toBigInteger(radix: Int): java.math.BigInteger
                fun toBigIntegerOrNull(): java.math.BigInteger?
                fun toBigIntegerOrNull(radix: Int): java.math.BigInteger?
                fun toBoolean(): Boolean
                fun toBooleanStrict(): Boolean
                fun toBooleanStrictOrNull(): Boolean?
                fun toByte(): Byte
                fun toByte(radix: Int): Byte
                fun toByteArray(charset: java.nio.charset.Charset): ByteArray
                fun toByteOrNull(): Byte?
                fun toByteOrNull(radix: Int): Byte?
                fun toCharArray(startIndex: Int, endIndex: Int): CharArray
                fun toCharArray(): CharArray
                fun toCharArray(destination: CharArray, destinationOffset: Int, startIndex: Int, endIndex: Int): CharArray
                fun toDouble(): Double
                fun toDoubleOrNull(): Double?
                fun toFloat(): Float
                fun toFloatOrNull(): Float?
                fun toInt(): Int
                fun toInt(radix: Int): Int
                fun toIntOrNull(): Int?
                fun toIntOrNull(radix: Int): Int?
                fun toJvmInternalName(): String
                fun toLong(): Long
                fun toLong(radix: Int): Long
                fun toLongOrNull(): Long?
                fun toLongOrNull(radix: Int): Long?
                fun toLowerCase(): String
                fun toLowerCase(locale: java.util.Locale): String
                fun toPattern(flags: Int): java.util.regex.Pattern
                fun toRegex(): Regex
                fun toRegex(option: RegexOption): Regex
                fun toRegex(options: Set<RegexOption>): Regex
                fun toShort(): Short
                fun toShort(radix: Int): Short
                fun toShortOrNull(): Short?
                fun toShortOrNull(radix: Int): Short?
                fun toString(): String
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
                fun toUpperCase(): String
                fun toUpperCase(locale: java.util.Locale): String
                fun translateEscapes(): String
                fun trim(predicate: Function1<Char, Boolean>): String
                fun trim(chars: CharArray): String
                fun trim(): String
                fun trimEnd(predicate: Function1<Char, Boolean>): String
                fun trimEnd(chars: CharArray): String
                fun trimEnd(): String
                fun trimIndent(): String
                fun trimMargin(marginPrefix: String): String
                fun trimStart(predicate: Function1<Char, Boolean>): String
                fun trimStart(chars: CharArray): String
                fun trimStart(): String
                fun uppercase(): String
                fun uppercase(locale: java.util.Locale): String
            }
            // from kotlin
            inline infix fun <T> Comparable<T>.compareTo(other: T): Int
            // from kotlin.text
            val CharSequence.indices: IntRange
            val CharSequence.lastIndex: Int
            inline fun CharSequence.all(predicate: Function1<Char, Boolean>): Boolean
            fun CharSequence.any(): Boolean
            inline fun CharSequence.any(predicate: Function1<Char, Boolean>): Boolean
            fun CharSequence.asIterable(): Iterable<Char>
            fun CharSequence.asSequence(): Sequence<Char>
            inline fun <K, V> CharSequence.associate(transform: Function1<Char, Pair<K, V>>): Map<K, V>
            inline fun <K> CharSequence.associateBy(keySelector: Function1<Char, K>): Map<K, Char>
            inline fun <K, V> CharSequence.associateBy(keySelector: Function1<Char, K>, valueTransform: Function1<Char, V>): Map<K, V>
            inline fun <K, M : MutableMap<in K, in Char>> CharSequence.associateByTo(destination: M, keySelector: Function1<Char, K>): M
            inline fun <K, V, M : MutableMap<in K, in V>> CharSequence.associateByTo(destination: M, keySelector: Function1<Char, K>, valueTransform: Function1<Char, V>): M
            inline fun <K, V, M : MutableMap<in K, in V>> CharSequence.associateTo(destination: M, transform: Function1<Char, Pair<K, V>>): M
            inline fun <V> CharSequence.associateWith(valueSelector: Function1<Char, V>): Map<Char, V>
            inline fun <V, M : MutableMap<in Char, in V>> CharSequence.associateWithTo(destination: M, valueSelector: Function1<Char, V>): M
            fun CharSequence.chunked(size: Int): List<String>
            fun <R> CharSequence.chunked(size: Int, transform: Function1<CharSequence, R>): List<R>
            fun CharSequence.chunkedSequence(size: Int): Sequence<String>
            fun <R> CharSequence.chunkedSequence(size: Int, transform: Function1<CharSequence, R>): Sequence<R>
            fun CharSequence.commonPrefixWith(other: CharSequence, ignoreCase: Boolean): String
            fun CharSequence.commonSuffixWith(other: CharSequence, ignoreCase: Boolean): String
            operator fun CharSequence.contains(other: CharSequence, ignoreCase: Boolean): Boolean
            operator fun CharSequence.contains(char: Char, ignoreCase: Boolean): Boolean
            inline operator fun CharSequence.contains(regex: Regex): Boolean
            infix fun CharSequence?.contentEquals(other: CharSequence?): Boolean
            fun CharSequence?.contentEquals(other: CharSequence?, ignoreCase: Boolean): Boolean
            inline fun CharSequence.count(): Int
            inline fun CharSequence.count(predicate: Function1<Char, Boolean>): Int
            fun CharSequence.drop(n: Int): CharSequence
            fun CharSequence.dropLast(n: Int): CharSequence
            inline fun CharSequence.dropLastWhile(predicate: Function1<Char, Boolean>): CharSequence
            inline fun CharSequence.dropWhile(predicate: Function1<Char, Boolean>): CharSequence
            inline fun CharSequence.elementAt(index: Int): Char
            inline fun CharSequence.elementAtOrElse(index: Int, defaultValue: Function1<Int, Char>): Char
            inline fun CharSequence.elementAtOrNull(index: Int): Char?
            fun CharSequence.endsWith(char: Char, ignoreCase: Boolean): Boolean
            fun CharSequence.endsWith(suffix: CharSequence, ignoreCase: Boolean): Boolean
            inline fun CharSequence.filter(predicate: Function1<Char, Boolean>): CharSequence
            inline fun CharSequence.filterIndexed(predicate: Function2<Int, Char, Boolean>): CharSequence
            inline fun <C : java.lang.Appendable> CharSequence.filterIndexedTo(destination: C, predicate: Function2<Int, Char, Boolean>): C
            inline fun CharSequence.filterNot(predicate: Function1<Char, Boolean>): CharSequence
            inline fun <C : java.lang.Appendable> CharSequence.filterNotTo(destination: C, predicate: Function1<Char, Boolean>): C
            inline fun <C : java.lang.Appendable> CharSequence.filterTo(destination: C, predicate: Function1<Char, Boolean>): C
            inline fun CharSequence.find(predicate: Function1<Char, Boolean>): Char?
            fun CharSequence.findAnyOf(strings: Collection<String>, startIndex: Int, ignoreCase: Boolean): Pair<Int, String>?
            inline fun CharSequence.findLast(predicate: Function1<Char, Boolean>): Char?
            fun CharSequence.findLastAnyOf(strings: Collection<String>, startIndex: Int, ignoreCase: Boolean): Pair<Int, String>?
            fun CharSequence.first(): Char
            inline fun CharSequence.first(predicate: Function1<Char, Boolean>): Char
            inline fun <R : Any> CharSequence.firstNotNullOf(transform: Function1<Char, R?>): R
            inline fun <R : Any> CharSequence.firstNotNullOfOrNull(transform: Function1<Char, R?>): R?
            fun CharSequence.firstOrNull(): Char?
            inline fun CharSequence.firstOrNull(predicate: Function1<Char, Boolean>): Char?
            inline fun <R> CharSequence.flatMap(transform: Function1<Char, Iterable<R>>): List<R>
            inline fun <R> CharSequence.flatMapIndexed(transform: Function2<Int, Char, Iterable<R>>): List<R>
            inline fun <R, C : MutableCollection<in R>> CharSequence.flatMapIndexedTo(destination: C, transform: Function2<Int, Char, Iterable<R>>): C
            inline fun <R, C : MutableCollection<in R>> CharSequence.flatMapTo(destination: C, transform: Function1<Char, Iterable<R>>): C
            inline fun <R> CharSequence.fold(initial: R, operation: Function2<R, Char, R>): R
            inline fun <R> CharSequence.foldIndexed(initial: R, operation: Function3<Int, R, Char, R>): R
            inline fun <R> CharSequence.foldRight(initial: R, operation: Function2<Char, R, R>): R
            inline fun <R> CharSequence.foldRightIndexed(initial: R, operation: Function3<Int, Char, R, R>): R
            inline fun CharSequence.forEach(action: Function1<Char, Unit>)
            inline fun CharSequence.forEachIndexed(action: Function2<Int, Char, Unit>)
            inline fun CharSequence.getOrElse(index: Int, defaultValue: Function1<Int, Char>): Char
            fun CharSequence.getOrNull(index: Int): Char?
            inline fun <K> CharSequence.groupBy(keySelector: Function1<Char, K>): Map<K, List<Char>>
            inline fun <K, V> CharSequence.groupBy(keySelector: Function1<Char, K>, valueTransform: Function1<Char, V>): Map<K, List<V>>
            inline fun <K, M : MutableMap<in K, MutableList<Char>>> CharSequence.groupByTo(destination: M, keySelector: Function1<Char, K>): M
            inline fun <K, V, M : MutableMap<in K, MutableList<V>>> CharSequence.groupByTo(destination: M, keySelector: Function1<Char, K>, valueTransform: Function1<Char, V>): M
            inline fun <K> CharSequence.groupingBy(keySelector: Function1<Char, K>): Grouping<Char, K>
            fun CharSequence.hasSurrogatePairAt(index: Int): Boolean
            fun CharSequence.indexOf(char: Char, startIndex: Int, ignoreCase: Boolean): Int
            fun CharSequence.indexOf(string: String, startIndex: Int, ignoreCase: Boolean): Int
            fun CharSequence.indexOfAny(chars: CharArray, startIndex: Int, ignoreCase: Boolean): Int
            fun CharSequence.indexOfAny(strings: Collection<String>, startIndex: Int, ignoreCase: Boolean): Int
            inline fun CharSequence.indexOfFirst(predicate: Function1<Char, Boolean>): Int
            inline fun CharSequence.indexOfLast(predicate: Function1<Char, Boolean>): Int
            fun CharSequence.isBlank(): Boolean
            inline fun CharSequence.isEmpty(): Boolean
            inline fun CharSequence.isNotBlank(): Boolean
            inline fun CharSequence.isNotEmpty(): Boolean
            inline fun CharSequence?.isNullOrBlank(): Boolean
            inline fun CharSequence?.isNullOrEmpty(): Boolean
            operator fun CharSequence.iterator(): CharIterator
            fun CharSequence.last(): Char
            inline fun CharSequence.last(predicate: Function1<Char, Boolean>): Char
            fun CharSequence.lastIndexOf(char: Char, startIndex: Int, ignoreCase: Boolean): Int
            fun CharSequence.lastIndexOf(string: String, startIndex: Int, ignoreCase: Boolean): Int
            fun CharSequence.lastIndexOfAny(chars: CharArray, startIndex: Int, ignoreCase: Boolean): Int
            fun CharSequence.lastIndexOfAny(strings: Collection<String>, startIndex: Int, ignoreCase: Boolean): Int
            fun CharSequence.lastOrNull(): Char?
            inline fun CharSequence.lastOrNull(predicate: Function1<Char, Boolean>): Char?
            fun CharSequence.lineSequence(): Sequence<String>
            fun CharSequence.lines(): List<String>
            inline fun <R> CharSequence.map(transform: Function1<Char, R>): List<R>
            inline fun <R> CharSequence.mapIndexed(transform: Function2<Int, Char, R>): List<R>
            inline fun <R : Any> CharSequence.mapIndexedNotNull(transform: Function2<Int, Char, R?>): List<R>
            inline fun <R : Any, C : MutableCollection<in R>> CharSequence.mapIndexedNotNullTo(destination: C, transform: Function2<Int, Char, R?>): C
            inline fun <R, C : MutableCollection<in R>> CharSequence.mapIndexedTo(destination: C, transform: Function2<Int, Char, R>): C
            inline fun <R : Any> CharSequence.mapNotNull(transform: Function1<Char, R?>): List<R>
            inline fun <R : Any, C : MutableCollection<in R>> CharSequence.mapNotNullTo(destination: C, transform: Function1<Char, R?>): C
            inline fun <R, C : MutableCollection<in R>> CharSequence.mapTo(destination: C, transform: Function1<Char, R>): C
            inline infix fun CharSequence.matches(regex: Regex): Boolean
            fun CharSequence.max(): Char?
            fun CharSequence.max(): Char
            inline fun <R : Comparable<R>> CharSequence.maxBy(selector: Function1<Char, R>): Char?
            inline fun <R : Comparable<R>> CharSequence.maxBy(selector: Function1<Char, R>): Char
            inline fun <R : Comparable<R>> CharSequence.maxByOrNull(selector: Function1<Char, R>): Char?
            inline fun CharSequence.maxOf(selector: Function1<Char, Double>): Double
            inline fun CharSequence.maxOf(selector: Function1<Char, Float>): Float
            inline fun <R : Comparable<R>> CharSequence.maxOf(selector: Function1<Char, R>): R
            inline fun CharSequence.maxOfOrNull(selector: Function1<Char, Double>): Double?
            inline fun CharSequence.maxOfOrNull(selector: Function1<Char, Float>): Float?
            inline fun <R : Comparable<R>> CharSequence.maxOfOrNull(selector: Function1<Char, R>): R?
            inline fun <R> CharSequence.maxOfWith(comparator: java.util.Comparator<in R>, selector: Function1<Char, R>): R
            inline fun <R> CharSequence.maxOfWithOrNull(comparator: java.util.Comparator<in R>, selector: Function1<Char, R>): R?
            fun CharSequence.maxOrNull(): Char?
            fun CharSequence.maxWith(comparator: java.util.Comparator<in Char>): Char?
            fun CharSequence.maxWith(comparator: java.util.Comparator<in Char>): Char
            fun CharSequence.maxWithOrNull(comparator: java.util.Comparator<in Char>): Char?
            fun CharSequence.min(): Char?
            fun CharSequence.min(): Char
            inline fun <R : Comparable<R>> CharSequence.minBy(selector: Function1<Char, R>): Char?
            inline fun <R : Comparable<R>> CharSequence.minBy(selector: Function1<Char, R>): Char
            inline fun <R : Comparable<R>> CharSequence.minByOrNull(selector: Function1<Char, R>): Char?
            inline fun CharSequence.minOf(selector: Function1<Char, Double>): Double
            inline fun CharSequence.minOf(selector: Function1<Char, Float>): Float
            inline fun <R : Comparable<R>> CharSequence.minOf(selector: Function1<Char, R>): R
            inline fun CharSequence.minOfOrNull(selector: Function1<Char, Double>): Double?
            inline fun CharSequence.minOfOrNull(selector: Function1<Char, Float>): Float?
            inline fun <R : Comparable<R>> CharSequence.minOfOrNull(selector: Function1<Char, R>): R?
            inline fun <R> CharSequence.minOfWith(comparator: java.util.Comparator<in R>, selector: Function1<Char, R>): R
            inline fun <R> CharSequence.minOfWithOrNull(comparator: java.util.Comparator<in R>, selector: Function1<Char, R>): R?
            fun CharSequence.minOrNull(): Char?
            fun CharSequence.minWith(comparator: java.util.Comparator<in Char>): Char?
            fun CharSequence.minWith(comparator: java.util.Comparator<in Char>): Char
            fun CharSequence.minWithOrNull(comparator: java.util.Comparator<in Char>): Char?
            fun CharSequence.none(): Boolean
            inline fun CharSequence.none(predicate: Function1<Char, Boolean>): Boolean
            fun CharSequence.padEnd(length: Int, padChar: Char): CharSequence
            fun CharSequence.padStart(length: Int, padChar: Char): CharSequence
            inline fun CharSequence.partition(predicate: Function1<Char, Boolean>): Pair<CharSequence, CharSequence>
            inline fun CharSequence.random(): Char
            fun CharSequence.random(random: Random): Char
            inline fun CharSequence.randomOrNull(): Char?
            fun CharSequence.randomOrNull(random: Random): Char?
            inline fun CharSequence.reduce(operation: Function2<Char, Char, Char>): Char
            inline fun CharSequence.reduceIndexed(operation: Function3<Int, Char, Char, Char>): Char
            inline fun CharSequence.reduceIndexedOrNull(operation: Function3<Int, Char, Char, Char>): Char?
            inline fun CharSequence.reduceOrNull(operation: Function2<Char, Char, Char>): Char?
            inline fun CharSequence.reduceRight(operation: Function2<Char, Char, Char>): Char
            inline fun CharSequence.reduceRightIndexed(operation: Function3<Int, Char, Char, Char>): Char
            inline fun CharSequence.reduceRightIndexedOrNull(operation: Function3<Int, Char, Char, Char>): Char?
            inline fun CharSequence.reduceRightOrNull(operation: Function2<Char, Char, Char>): Char?
            fun CharSequence.regionMatches(thisOffset: Int, other: CharSequence, otherOffset: Int, length: Int, ignoreCase: Boolean): Boolean
            fun CharSequence.removePrefix(prefix: CharSequence): CharSequence
            fun CharSequence.removeRange(startIndex: Int, endIndex: Int): CharSequence
            fun CharSequence.removeRange(range: IntRange): CharSequence
            fun CharSequence.removeSuffix(suffix: CharSequence): CharSequence
            fun CharSequence.removeSurrounding(prefix: CharSequence, suffix: CharSequence): CharSequence
            fun CharSequence.removeSurrounding(delimiter: CharSequence): CharSequence
            fun CharSequence.repeat(n: Int): String
            inline fun CharSequence.replace(regex: Regex, replacement: String): String
            inline fun CharSequence.replace(regex: Regex, transform: Function1<MatchResult, CharSequence>): String
            inline fun CharSequence.replaceFirst(regex: Regex, replacement: String): String
            fun CharSequence.replaceRange(startIndex: Int, endIndex: Int, replacement: CharSequence): CharSequence
            fun CharSequence.replaceRange(range: IntRange, replacement: CharSequence): CharSequence
            fun CharSequence.reversed(): CharSequence
            inline fun <R> CharSequence.runningFold(initial: R, operation: Function2<R, Char, R>): List<R>
            inline fun <R> CharSequence.runningFoldIndexed(initial: R, operation: Function3<Int, R, Char, R>): List<R>
            inline fun CharSequence.runningReduce(operation: Function2<Char, Char, Char>): List<Char>
            inline fun CharSequence.runningReduceIndexed(operation: Function3<Int, Char, Char, Char>): List<Char>
            inline fun <R> CharSequence.scan(initial: R, operation: Function2<R, Char, R>): List<R>
            inline fun <R> CharSequence.scanIndexed(initial: R, operation: Function3<Int, R, Char, R>): List<R>
            fun CharSequence.single(): Char
            inline fun CharSequence.single(predicate: Function1<Char, Boolean>): Char
            fun CharSequence.singleOrNull(): Char?
            inline fun CharSequence.singleOrNull(predicate: Function1<Char, Boolean>): Char?
            fun CharSequence.slice(indices: IntRange): CharSequence
            fun CharSequence.slice(indices: Iterable<Int>): CharSequence
            fun CharSequence.split(regex: java.util.regex.Pattern, limit: Int): List<String>
            fun CharSequence.split(delimiters: Array<out String>, ignoreCase: Boolean, limit: Int): List<String>
            fun CharSequence.split(delimiters: CharArray, ignoreCase: Boolean, limit: Int): List<String>
            inline fun CharSequence.split(regex: Regex, limit: Int): List<String>
            fun CharSequence.splitToSequence(delimiters: Array<out String>, ignoreCase: Boolean, limit: Int): Sequence<String>
            fun CharSequence.splitToSequence(delimiters: CharArray, ignoreCase: Boolean, limit: Int): Sequence<String>
            inline fun CharSequence.splitToSequence(regex: Regex, limit: Int): Sequence<String>
            fun CharSequence.startsWith(char: Char, ignoreCase: Boolean): Boolean
            fun CharSequence.startsWith(prefix: CharSequence, ignoreCase: Boolean): Boolean
            fun CharSequence.startsWith(prefix: CharSequence, startIndex: Int, ignoreCase: Boolean): Boolean
            fun CharSequence.subSequence(range: IntRange): CharSequence
            inline fun CharSequence.substring(startIndex: Int, endIndex: Int): String
            fun CharSequence.substring(range: IntRange): String
            inline fun CharSequence.sumBy(selector: Function1<Char, Int>): Int
            inline fun CharSequence.sumByDouble(selector: Function1<Char, Double>): Double
            inline fun CharSequence.sumOf(selector: Function1<Char, java.math.BigDecimal>): java.math.BigDecimal
            inline fun CharSequence.sumOf(selector: Function1<Char, java.math.BigInteger>): java.math.BigInteger
            inline fun CharSequence.sumOf(selector: Function1<Char, Double>): Double
            inline fun CharSequence.sumOf(selector: Function1<Char, Int>): Int
            inline fun CharSequence.sumOf(selector: Function1<Char, Long>): Long
            inline fun CharSequence.sumOf(selector: Function1<Char, UInt>): UInt
            inline fun CharSequence.sumOf(selector: Function1<Char, ULong>): ULong
            fun CharSequence.take(n: Int): CharSequence
            fun CharSequence.takeLast(n: Int): CharSequence
            inline fun CharSequence.takeLastWhile(predicate: Function1<Char, Boolean>): CharSequence
            inline fun CharSequence.takeWhile(predicate: Function1<Char, Boolean>): CharSequence
            fun <C : MutableCollection<in Char>> CharSequence.toCollection(destination: C): C
            fun CharSequence.toHashSet(): java.util.HashSet<Char>
            fun CharSequence.toList(): List<Char>
            fun CharSequence.toMutableList(): MutableList<Char>
            fun CharSequence.toSet(): Set<Char>
            fun CharSequence.toSortedSet(): java.util.SortedSet<Char>
            inline fun CharSequence.trim(predicate: Function1<Char, Boolean>): CharSequence
            fun CharSequence.trim(chars: CharArray): CharSequence
            fun CharSequence.trim(): CharSequence
            inline fun CharSequence.trimEnd(predicate: Function1<Char, Boolean>): CharSequence
            fun CharSequence.trimEnd(chars: CharArray): CharSequence
            fun CharSequence.trimEnd(): CharSequence
            inline fun CharSequence.trimStart(predicate: Function1<Char, Boolean>): CharSequence
            fun CharSequence.trimStart(chars: CharArray): CharSequence
            fun CharSequence.trimStart(): CharSequence
            fun CharSequence.windowed(size: Int, step: Int, partialWindows: Boolean): List<String>
            fun <R> CharSequence.windowed(size: Int, step: Int, partialWindows: Boolean, transform: Function1<CharSequence, R>): List<R>
            fun CharSequence.windowedSequence(size: Int, step: Int, partialWindows: Boolean): Sequence<String>
            fun <R> CharSequence.windowedSequence(size: Int, step: Int, partialWindows: Boolean, transform: Function1<CharSequence, R>): Sequence<R>
            fun CharSequence.withIndex(): Iterable<IndexedValue<Char>>
            infix fun CharSequence.zip(other: CharSequence): List<Pair<Char, Char>>
            inline fun <V> CharSequence.zip(other: CharSequence, transform: Function2<Char, Char, V>): List<V>
            fun CharSequence.zipWithNext(): List<Pair<Char, Char>>
            inline fun <R> CharSequence.zipWithNext(transform: Function2<Char, Char, R>): List<R>
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
                fun copy(id: Int, name: String): DataTestClass
                open operator fun equals(other: Any?): Boolean
                open fun hashCode(): Int
                open fun toString(): String
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
            value class ValueTestClass(val value: String) {
                open operator fun equals(other: Any?): Boolean
                open fun hashCode(): Int
                open fun toString(): String
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
    fun `should resolve class with companion object`() {
        // an LLM needs to see companion object members to access static-like functionality
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionTestClass [page 1/1]
            class CompanionTestClass {
                companion object {
                    const val CONSTANT: String
                    private const val PRIVATE_CONSTANT: String
                    fun create(): CompanionTestClass
                }
            }
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
                inline fun processData(block: Function0<Unit>)
                infix fun combine(other: String): String
                operator fun plus(other: FunctionModifiersTestClass): FunctionModifiersTestClass
                tailrec fun factorial(n: Int, acc: Int): Int
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
            }
        """.trimIndent()
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
        // members are output in declaration order
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.VisibilityTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.VisibilityTestClass [page 1/1]
            class VisibilityTestClass {
                val publicProperty: String
                private val privateProperty: String
                protected val protectedProperty: String
                internal val internalProperty: String
                fun publicFunction(): String
                private fun privateFunction(): String
                protected fun protectedFunction(): String
                internal fun internalFunction(): String
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
        // compareTo from Comparable is an operator function
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.OverrideTestClass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.OverrideTestClass [page 1/1]
            class OverrideTestClass : Comparable<OverrideTestClass> {
                override operator fun compareTo(other: OverrideTestClass): Int
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
            fun TestSubtype.describeSubtype(): String
            fun TestIntermediateType.describeIntermediate(): String
            fun TestSupertype.describeSupertype(): String
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
                fun <R : Any> transform(block: Function2<K, V, R>): R
            }
            // from com.xemantic.kotlin.introspection.test
            fun <K, V> GenericTestClass<K, V>.swap(): GenericTestClass<V, K>
            inline fun <reified T> GenericTestClass<*, *>.valueAs(): T?
            fun <T> GenericTestClass<T, T>.longest(): T where T : CharSequence, T : Comparable<T>
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
    fun `should resolve all overloads of top-level function`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestOverloadsKt.topLevelOverloaded"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestOverloadsKt.topLevelOverloaded [page 1/1]
            fun topLevelOverloaded(): Int
            fun topLevelOverloaded(x: Int): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve all overloads of built-in type member`() {
        resolver.resolve(
            "kotlin.String.substring"
        ) sameAsKotlin """
            // kotlin.String.substring [page 1/1]
            inline fun String.substring(startIndex: Int): String
            inline fun String.substring(startIndex: Int, endIndex: Int): String
            fun String.substring(range: IntRange): String
        """.trimIndent()
    }

    @Test
    fun `should resolve function of multi-file class facade`() {
        resolver.resolve(
            "kotlin.collections.CollectionsKt.listOf"
        ) sameAsKotlin """
            // kotlin.collections.CollectionsKt.listOf [page 1/1]
            fun <T> listOf(element: T): List<T>
            fun <T> listOf(elements: Array<out T>): List<T>
            inline fun <T> listOf(): List<T>
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
            have("inline fun <T, R> Iterable<T>.map(transform: Function1<T, R>): List<R>" in this)
            have("fun <T> Collection<T>.toMutableList(): MutableList<T>" in this)
        }
    }

    @Test
    fun `should resolve MutableList with its mutating members`() {
        resolver.resolve(
            "kotlin.collections.MutableList"
        ) sameAsKotlin """
            // kotlin.collections.MutableList [page 1/35]
            interface MutableList<E> : List<E>, MutableCollection<E> {
                fun add(element: E): Boolean
                fun add(index: Int, element: E)
                fun addAll(elements: Collection<E>): Boolean
                fun addAll(index: Int, elements: Collection<E>): Boolean
                fun <T> asReversed(): MutableList<T>
                fun clear()
                fun <T> fill(value: T)
                fun listIterator(): MutableListIterator<E>
                fun listIterator(index: Int): MutableListIterator<E>
                fun remove(element: E): Boolean
            }
        """.trimIndent()
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
        // should show first 3 results (21 total classes, 7 pages)
        resolver.search("TestClass", pageSize = 3) sameAsKotlin """
            // search results for "TestClass" [page 1/7]
            com.xemantic.kotlin.introspection.test.AbstractTestClass
            com.xemantic.kotlin.introspection.test.AnnotationDefaultsTestClass
            com.xemantic.kotlin.introspection.test.AnnotationTestClass
        """.trimIndent()
    }

    @Test
    fun `search should support pagination with page parameter`() {
        // should show results 4-6 (21 total classes, 7 pages)
        resolver.search("TestClass", page = 2, pageSize = 3) sameAsKotlin """
            // search results for "TestClass" [page 2/7]
            com.xemantic.kotlin.introspection.test.CompanionTestClass
            com.xemantic.kotlin.introspection.test.DataTestClass
            com.xemantic.kotlin.introspection.test.EnumTestClass
        """.trimIndent()
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

}
