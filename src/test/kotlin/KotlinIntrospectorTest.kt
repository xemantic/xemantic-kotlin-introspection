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
                fun byteInputStream(charset: java.nio.charset.Charset = …): java.io.ByteArrayInputStream
                fun capitalize(): String
                fun capitalize(locale: java.util.Locale): String
                fun chars(): java.util.stream.IntStream
                fun codePointAt(index: Int): Int
                fun codePointBefore(index: Int): Int
                fun codePointCount(beginIndex: Int, endIndex: Int): Int
                fun codePoints(): java.util.stream.IntStream
                operator fun compareTo(other: String): Int
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
                fun encodeToByteArray(startIndex: Int = …, endIndex: Int = …, throwOnInvalidSequence: Boolean = …): ByteArray
                fun endsWith(suffix: String, ignoreCase: Boolean = …): Boolean
                operator fun equals(other: Any?): Boolean
                fun equals(other: String?, ignoreCase: Boolean = …): Boolean
                fun filter(predicate: Function1<Char, Boolean>): String
                fun filterIndexed(predicate: Function2<Int, Char, Boolean>): String
                fun filterNot(predicate: Function1<Char, Boolean>): String
                fun format(vararg args: Any?): String
                fun format(locale: java.util.Locale?, vararg args: Any?): String
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
                fun byteInputStream(charset: java.nio.charset.Charset = …): java.io.ByteArrayInputStream
                fun capitalize(): String
                fun capitalize(locale: java.util.Locale): String
                fun chars(): java.util.stream.IntStream
                fun codePointAt(index: Int): Int
                fun codePointBefore(index: Int): Int
                fun codePointCount(beginIndex: Int, endIndex: Int): Int
                fun codePoints(): java.util.stream.IntStream
                operator fun compareTo(other: String): Int
                fun compareTo(other: String, ignoreCase: Boolean = …): Int
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
                fun encodeToByteArray(startIndex: Int = …, endIndex: Int = …, throwOnInvalidSequence: Boolean = …): ByteArray
                fun endsWith(suffix: String, ignoreCase: Boolean = …): Boolean
                operator fun equals(other: Any?): Boolean
                fun equals(other: String?, ignoreCase: Boolean = …): Boolean
                fun filter(predicate: Function1<Char, Boolean>): String
                fun filterIndexed(predicate: Function2<Int, Char, Boolean>): String
                fun filterNot(predicate: Function1<Char, Boolean>): String
                fun format(vararg args: Any?): String
                fun format(locale: java.util.Locale?, vararg args: Any?): String
                operator fun get(index: Int): Char
                fun hashCode(): Int
                fun hexToByte(format: HexFormat = …): Byte
                fun hexToByteArray(format: HexFormat = …): ByteArray
                fun hexToInt(format: HexFormat = …): Int
                fun hexToLong(format: HexFormat = …): Long
                fun hexToShort(format: HexFormat = …): Short
                fun hexToUByte(format: HexFormat = …): UByte
                fun hexToUByteArray(format: HexFormat = …): UByteArray
                fun hexToUInt(format: HexFormat = …): UInt
                fun hexToULong(format: HexFormat = …): ULong
                fun hexToUShort(format: HexFormat = …): UShort
                fun intern(): String
                fun isEmpty(): Boolean
                fun isLocalClassName(): Boolean
                val length: Int
                fun lowercase(): String
                fun lowercase(locale: java.util.Locale): String
                fun offsetByCodePoints(index: Int, codePointOffset: Int): Int
                fun orEmpty(): String
                fun padEnd(length: Int, padChar: Char = …): String
                fun padStart(length: Int, padChar: Char = …): String
                fun partition(predicate: Function1<Char, Boolean>): Pair<String, String>
                operator fun plus(other: Any?): String
                fun prependIndent(indent: String = …): String
                fun reader(): java.io.StringReader
                fun regionMatches(thisOffset: Int, other: String, otherOffset: Int, length: Int, ignoreCase: Boolean = …): Boolean
                fun removePrefix(prefix: CharSequence): String
                fun removeRange(startIndex: Int, endIndex: Int): String
                fun removeRange(range: IntRange): String
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
                fun replaceFirstChar(transform: Function1<Char, Char>): String
                fun replaceFirstChar(transform: Function1<Char, CharSequence>): String
                fun replaceIndent(newIndent: String = …): String
                fun replaceIndentByMargin(newIndent: String = …, marginPrefix: String = …): String
                fun replaceRange(startIndex: Int, endIndex: Int, replacement: CharSequence): String
                fun replaceRange(range: IntRange, replacement: CharSequence): String
                fun reversed(): String
                fun slice(indices: IntRange): String
                fun slice(indices: Iterable<Int>): String
                fun startsWith(prefix: String, ignoreCase: Boolean = …): Boolean
                fun startsWith(prefix: String, startIndex: Int, ignoreCase: Boolean = …): Boolean
                fun strip(): String
                fun stripIndent(): String
                fun stripLeading(): String
                fun stripTrailing(): String
                fun subSequence(startIndex: Int, endIndex: Int): CharSequence
                fun subSequence(start: Int, end: Int): CharSequence
                fun substring(startIndex: Int): String
                fun substring(startIndex: Int, endIndex: Int): String
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
                fun toByteArray(charset: java.nio.charset.Charset = …): ByteArray
                fun toByteOrNull(): Byte?
                fun toByteOrNull(radix: Int): Byte?
                fun toCharArray(startIndex: Int = …, endIndex: Int = …): CharArray
                fun toCharArray(): CharArray
                fun toCharArray(destination: CharArray, destinationOffset: Int = …, startIndex: Int = …, endIndex: Int = …): CharArray
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
                fun toPattern(flags: Int = …): java.util.regex.Pattern
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
                fun trim(vararg chars: Char): String
                fun trim(): String
                fun trimEnd(predicate: Function1<Char, Boolean>): String
                fun trimEnd(vararg chars: Char): String
                fun trimEnd(): String
                fun trimIndent(): String
                fun trimMargin(marginPrefix: String = …): String
                fun trimStart(predicate: Function1<Char, Boolean>): String
                fun trimStart(vararg chars: Char): String
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
            fun CharSequence.commonPrefixWith(other: CharSequence, ignoreCase: Boolean = …): String
            fun CharSequence.commonSuffixWith(other: CharSequence, ignoreCase: Boolean = …): String
            operator fun CharSequence.contains(other: CharSequence, ignoreCase: Boolean = …): Boolean
            operator fun CharSequence.contains(char: Char, ignoreCase: Boolean = …): Boolean
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
            fun CharSequence.endsWith(char: Char, ignoreCase: Boolean = …): Boolean
            fun CharSequence.endsWith(suffix: CharSequence, ignoreCase: Boolean = …): Boolean
            inline fun CharSequence.filter(predicate: Function1<Char, Boolean>): CharSequence
            inline fun CharSequence.filterIndexed(predicate: Function2<Int, Char, Boolean>): CharSequence
            inline fun <C : java.lang.Appendable> CharSequence.filterIndexedTo(destination: C, predicate: Function2<Int, Char, Boolean>): C
            inline fun CharSequence.filterNot(predicate: Function1<Char, Boolean>): CharSequence
            inline fun <C : java.lang.Appendable> CharSequence.filterNotTo(destination: C, predicate: Function1<Char, Boolean>): C
            inline fun <C : java.lang.Appendable> CharSequence.filterTo(destination: C, predicate: Function1<Char, Boolean>): C
            inline fun CharSequence.find(predicate: Function1<Char, Boolean>): Char?
            fun CharSequence.findAnyOf(strings: Collection<String>, startIndex: Int = …, ignoreCase: Boolean = …): Pair<Int, String>?
            inline fun CharSequence.findLast(predicate: Function1<Char, Boolean>): Char?
            fun CharSequence.findLastAnyOf(strings: Collection<String>, startIndex: Int = …, ignoreCase: Boolean = …): Pair<Int, String>?
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
            fun CharSequence.indexOf(char: Char, startIndex: Int = …, ignoreCase: Boolean = …): Int
            fun CharSequence.indexOf(string: String, startIndex: Int = …, ignoreCase: Boolean = …): Int
            fun CharSequence.indexOfAny(chars: CharArray, startIndex: Int = …, ignoreCase: Boolean = …): Int
            fun CharSequence.indexOfAny(strings: Collection<String>, startIndex: Int = …, ignoreCase: Boolean = …): Int
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
            fun CharSequence.lastIndexOf(char: Char, startIndex: Int = …, ignoreCase: Boolean = …): Int
            fun CharSequence.lastIndexOf(string: String, startIndex: Int = …, ignoreCase: Boolean = …): Int
            fun CharSequence.lastIndexOfAny(chars: CharArray, startIndex: Int = …, ignoreCase: Boolean = …): Int
            fun CharSequence.lastIndexOfAny(strings: Collection<String>, startIndex: Int = …, ignoreCase: Boolean = …): Int
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
            fun CharSequence.padEnd(length: Int, padChar: Char = …): CharSequence
            fun CharSequence.padStart(length: Int, padChar: Char = …): CharSequence
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
            fun CharSequence.regionMatches(thisOffset: Int, other: CharSequence, otherOffset: Int, length: Int, ignoreCase: Boolean = …): Boolean
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
            fun CharSequence.subSequence(range: IntRange): CharSequence
            inline fun CharSequence.substring(startIndex: Int, endIndex: Int = …): String
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
            fun CharSequence.trim(vararg chars: Char): CharSequence
            fun CharSequence.trim(): CharSequence
            inline fun CharSequence.trimEnd(predicate: Function1<Char, Boolean>): CharSequence
            fun CharSequence.trimEnd(vararg chars: Char): CharSequence
            fun CharSequence.trimEnd(): CharSequence
            inline fun CharSequence.trimStart(predicate: Function1<Char, Boolean>): CharSequence
            fun CharSequence.trimStart(vararg chars: Char): CharSequence
            fun CharSequence.trimStart(): CharSequence
            fun CharSequence.windowed(size: Int, step: Int = …, partialWindows: Boolean = …): List<String>
            fun <R> CharSequence.windowed(size: Int, step: Int = …, partialWindows: Boolean = …, transform: Function1<CharSequence, R>): List<R>
            fun CharSequence.windowedSequence(size: Int, step: Int = …, partialWindows: Boolean = …): Sequence<String>
            fun <R> CharSequence.windowedSequence(size: Int, step: Int = …, partialWindows: Boolean = …, transform: Function1<CharSequence, R>): Sequence<R>
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
            value class ValueTestClass(val value: String) {
                override operator fun equals(other: Any?): Boolean
                override fun hashCode(): Int
                override fun toString(): String
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
            fun <T> valueParametersSample(vararg elements: T, limit: Int = …): List<T>
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
            fun (suspend () -> Unit).retry()
        """.trimIndent()
    }

    @Test
    fun `should resolve extension property on suspend function type`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestFunctionModifiersKt.retryCount"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestFunctionModifiersKt.retryCount [page 1/1]
            val (suspend () -> Unit).retryCount: Int
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
                override val label: String
                override fun greet(name: String): String
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
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ValueComparable"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ValueComparable [page 1/1]
            value class ValueComparable(val x: Int) : Comparable<ValueComparable> {
                override operator fun compareTo(other: ValueComparable): Int
                override operator fun equals(other: Any?): Boolean
                override fun hashCode(): Int
                override fun toString(): String
            }
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
                fun process-handler(item: String)
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of function of inner class referring to type parameter of outer class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ProbeOuterString.Sub"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ProbeOuterString.Sub [page 1/1]
            inner class Sub : ProbeOuter.Inner {
                override fun f(t: String)
                fun f(t: Int)
            }
        """.trimIndent()
    }

    // the context parameters are not rendered yet, the subclass declares fun run(), context(c: Ctx) fun run(),
    // and context(c: Ctx) val label, while the superclass declares context(log: Logger) members
    @Test
    fun `should not mark overloads with other context parameters as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ContextOverloadingSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ContextOverloadingSubclass [page 1/1]
            class ContextOverloadingSubclass : ContextBase {
                val label: String
                fun run()
                fun run()
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
                override val label: String
                override fun run()
            }
        """.trimIndent()
    }

    // context(c: Ctx) override fun process(item: UInt), next to fun process(item: Int)
    @Test
    fun `should mark override with context parameter and value class type argument`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.UIntContextProcessor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.UIntContextProcessor [page 1/1]
            class UIntContextProcessor : ContextProcessor<UInt> {
                override fun process(item: UInt)
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
            override fun process(item: UInt)
        """.trimIndent()
    }

    // context(c: Ctx) override fun act(), next to context(log: Logger) fun act()
    @Test
    fun `should match context parameter of type argument of supertype`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CtxContextual"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CtxContextual [page 1/1]
            class CtxContextual : Contextual<Ctx> {
                override fun act()
                fun act()
            }
        """.trimIndent()
    }

    // override fun run(), next to context(c: Ctx) fun run()
    @Test
    fun `should not mark function with context parameter named like Java method as override`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ContextRunnable"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ContextRunnable [page 1/1]
            class ContextRunnable : java.lang.Runnable {
                override fun run()
                fun run()
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
                fun accept()
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
                val label: String
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
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.FunctionTypeImpl"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.FunctionTypeImpl [page 1/1]
            class FunctionTypeImpl : Function1<String, Int> {
                override operator fun invoke(p1: String): Int
                fun invoke(p1: Int): Int
            }
        """.trimIndent()
    }

    @Test
    fun `should mark override of invoke of suspend function type supertype`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SuspendFunctionTypeImpl"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SuspendFunctionTypeImpl [page 1/1]
            class SuspendFunctionTypeImpl : suspend (String) -> Int {
                override suspend operator fun invoke(p1: String): Int
                fun invoke(p1: String, p2: Int): Int
            }
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
    fun `should not bind Java method type variable shadowing type variable of its class to type argument of the class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.JavaOuterSubclass"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.JavaOuterSubclass [page 1/1]
            class JavaOuterSubclass : com.xemantic.kotlin.introspection.test.other.JavaOuterBase<String> {
                inner class InnerSubclass : com.xemantic.kotlin.introspection.test.other.JavaOuterBase.Inner<Int>
                inner class ShadowingSubclass : com.xemantic.kotlin.introspection.test.other.JavaOuterBase.Shadowing<Int>
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
            inner class InnerSubclass : com.xemantic.kotlin.introspection.test.other.JavaOuterBase.Inner<Int> {
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
            inner class ShadowingSubclass : com.xemantic.kotlin.introspection.test.other.JavaOuterBase.Shadowing<Int> {
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
                override fun onEvent(callback: Function1<String, Unit>)
                fun onEvent(callback: Function1<Int, Unit>, extra: Int)
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
                fun anonymous(): Any
                fun whenMapping(nestedEnum: NestedClassesHolder.NestedEnum): Int
                companion object {
                    fun create(): NestedClassesHolder
                }
            }
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
    fun `should list extensions of built-in type from object outside of the class body`() {
        // Int.seconds is not a member of Int, it has to be imported from Duration.Companion
        resolver.resolve("kotlin.Int", pageSize = 10000) should {
            have("    val seconds: Duration" !in lines())
            have("// from kotlin.time.Duration.Companion" in lines())
            have("val Int.seconds: Duration" in lines())
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
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder.Cursor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TypeParametersNestedHolder.Cursor [page 1/1]
            inner class Cursor : Iterator<K> {
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

    @Test
    fun `should resolve class with extensions sorted by name regardless of declaration and scan order`() {
        // the extensions are spread over 2 files, in each declared in reverse order,
        // and the order in which files are scanned depends on the file system
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.ExtensionOrderSubject"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.ExtensionOrderSubject [page 1/1]
            class ExtensionOrderSubject(val value: Int)
            // from com.xemantic.kotlin.introspection.test
            val ExtensionOrderSubject.bravo: Int
            val ExtensionOrderSubject.charlie: Int
            val ExtensionOrderSubject.yankee: Int
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
                fun <R : Any> transform(block: Function2<K, V, R>): R
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
    fun `should resolve only public function overloads through file facade`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.TestExtensionVisibilityKt.helper"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.TestExtensionVisibilityKt.helper [page 1/1]
            fun com.xemantic.kotlin.introspection.test.ExtensionVisibilityTarget.helper(): Int
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
            class PrivateConstructorHolder {
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
            class InternalConstructorHolder {
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
                inner class GenericSealedOuter.Sub : GenericSealedHolder<T>
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
                inner class InnerSealedInterfaceHolder.Sub : InnerSealedInterface, Comparable<X>
            }
        """.trimIndent()
    }

    @Test
    fun `should list companion object of another class as sealed subclass`() {
        // the own companion object is rendered as such, while the one of another class is a branch of a when expression
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.CompanionSealedHolder"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.CompanionSealedHolder [page 1/1]
            sealed class CompanionSealedHolder {
                class Sub : CompanionSealedHolder
                companion object CompanionSealedSubclassHolder.Companion : CompanionSealedHolder
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
                class PublicTopLevelSealedSubclass : SealedVisibilityHolder
                class PublicSubclass : SealedVisibilityHolder
                fun usePrivate(): SealedVisibilityHolder
            }
        """.trimIndent()
    }

    @Test
    fun `should render sealed class without body when no subclass is part of the API`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenSubclassesSealed"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenSubclassesSealed [page 1/1]
            sealed class HiddenSubclassesSealed
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
        // both kotlin-reflect and the standard library declare KClass.cast
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
            // from kotlin.text
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
            fun <T> listOf(vararg elements: T): List<T>
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
    fun `search should not find synthetic nested classes`() {
        assert(resolver.search("WhenMappings") == null)
    }

    @Test
    fun `search should not find companion objects`() {
        // they are already rendered within their outer class
        resolver.search("Companion", pageSize = 1000) should {
            have(!contains("CompanionTestClass.Companion"))
        }
    }

    @Test
    fun `search should not find synthetic nested class`() {
        assert(resolver.search("Synthetic\$Nested") == null)
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

}
