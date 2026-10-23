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
            // kotlin.String [page 1/41]
            class String : Comparable<String>, CharSequence {
                inline fun byteInputStream(charset: java.nio.charset.Charset = …): java.io.ByteArrayInputStream
                fun capitalize(): String
                fun capitalize(locale: java.util.Locale): String
                fun chars(): java.util.stream.IntStream
                inline fun codePointAt(index: Int): Int
                inline fun codePointBefore(index: Int): Int
                inline fun codePointCount(beginIndex: Int, endIndex: Int): Int
                fun codePoints(): java.util.stream.IntStream
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
            // kotlin.String [page 2/41]
            class String : Comparable<String>, CharSequence {
                inline fun contentEquals(charSequence: CharSequence): Boolean
                inline fun contentEquals(stringBuilder: java.lang.StringBuffer): Boolean
                fun decapitalize(): String
                fun decapitalize(locale: java.util.Locale): String
                fun describeConstable(): java.util.Optional<String>
                fun drop(n: Int): String
                fun dropLast(n: Int): String
                inline fun dropLastWhile(predicate: (Char) -> Boolean): String
                inline fun dropWhile(predicate: (Char) -> Boolean): String
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
            // kotlin.String [page 3/41]
            class String : Comparable<String>, CharSequence {
                fun encodeToByteArray(startIndex: Int = …, endIndex: Int = …, throwOnInvalidSequence: Boolean = …): ByteArray
                fun endsWith(suffix: String, ignoreCase: Boolean = …): Boolean
                override operator fun equals(other: Any?): Boolean
                fun equals(other: String?, ignoreCase: Boolean = …): Boolean
                inline fun filter(predicate: (Char) -> Boolean): String
                inline fun filterIndexed(predicate: (index: Int, Char) -> Boolean): String
                inline fun filterNot(predicate: (Char) -> Boolean): String
                inline fun format(vararg args: Any?): String
                inline fun format(locale: java.util.Locale?, vararg args: Any?): String
                override operator fun get(index: Int): Char
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
            // kotlin.String [page 19/41]
            class String : Comparable<String>, CharSequence {
                inline fun uppercase(locale: java.util.Locale): String
            }
            // from kotlin
            inline infix fun <T> Comparable<T>.compareTo(other: T): Int
            // from kotlin.text
            val CharSequence.indices: IntRange
            val CharSequence.lastIndex: Int
            inline fun CharSequence.all(predicate: (Char) -> Boolean): Boolean
            fun CharSequence.any(): Boolean
            inline fun CharSequence.any(predicate: (Char) -> Boolean): Boolean
            fun CharSequence.asIterable(): Iterable<Char>
            fun CharSequence.asSequence(): Sequence<Char>
            inline fun <K, V> CharSequence.associate(transform: (Char) -> Pair<K, V>): Map<K, V>
        """.trimIndent()
    }

    @Test
    fun `should resolve String as known stdlib class - page 41 (the last)`() {
        // extension-only pages omit the empty class body
        resolver.resolve(
            "kotlin.String",
            page = 41
        ) sameAsKotlin """
            // kotlin.String [page 41/41]
            // from kotlin.text
            fun <R> CharSequence.windowedSequence(size: Int, step: Int = …, partialWindows: Boolean = …, transform: (CharSequence) -> R): Sequence<R>
            fun CharSequence.withIndex(): Iterable<IndexedValue<Char>>
            infix fun CharSequence.zip(other: CharSequence): List<Pair<Char, Char>>
            inline fun <V> CharSequence.zip(other: CharSequence, transform: (a: Char, b: Char) -> V): List<V>
            fun CharSequence.zipWithNext(): List<Pair<Char, Char>>
            inline fun <R> CharSequence.zipWithNext(transform: (a: Char, b: Char) -> R): List<R>
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
                fun capitalize(): String
                fun capitalize(locale: java.util.Locale): String
                fun chars(): java.util.stream.IntStream
                inline fun codePointAt(index: Int): Int
                inline fun codePointBefore(index: Int): Int
                inline fun codePointCount(beginIndex: Int, endIndex: Int): Int
                fun codePoints(): java.util.stream.IntStream
                override operator fun compareTo(other: String): Int
                fun compareTo(other: String, ignoreCase: Boolean = …): Int
                inline fun contentEquals(charSequence: CharSequence): Boolean
                inline fun contentEquals(stringBuilder: java.lang.StringBuffer): Boolean
                fun decapitalize(): String
                fun decapitalize(locale: java.util.Locale): String
                fun describeConstable(): java.util.Optional<String>
                fun drop(n: Int): String
                fun dropLast(n: Int): String
                inline fun dropLastWhile(predicate: (Char) -> Boolean): String
                inline fun dropWhile(predicate: (Char) -> Boolean): String
                fun encodeToByteArray(): ByteArray
                fun encodeToByteArray(startIndex: Int = …, endIndex: Int = …, throwOnInvalidSequence: Boolean = …): ByteArray
                fun endsWith(suffix: String, ignoreCase: Boolean = …): Boolean
                override operator fun equals(other: Any?): Boolean
                fun equals(other: String?, ignoreCase: Boolean = …): Boolean
                inline fun filter(predicate: (Char) -> Boolean): String
                inline fun filterIndexed(predicate: (index: Int, Char) -> Boolean): String
                inline fun filterNot(predicate: (Char) -> Boolean): String
                inline fun format(vararg args: Any?): String
                inline fun format(locale: java.util.Locale?, vararg args: Any?): String
                override operator fun get(index: Int): Char
                fun hashCode(): Int
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
                fun isEmpty(): Boolean
                fun isLocalClassName(): Boolean
                override val length: Int
                inline fun lowercase(): String
                inline fun lowercase(locale: java.util.Locale): String
                inline fun offsetByCodePoints(index: Int, codePointOffset: Int): Int
                inline fun orEmpty(): String
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
                fun strip(): String
                fun stripIndent(): String
                fun stripLeading(): String
                fun stripTrailing(): String
                override fun subSequence(startIndex: Int, endIndex: Int): CharSequence
                inline fun subSequence(start: Int, end: Int): CharSequence
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
                inline fun toBoolean(): Boolean
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
                fun toJvmInternalName(): String
                inline fun toLong(): Long
                inline fun toLong(radix: Int): Long
                fun toLongOrNull(): Long?
                fun toLongOrNull(radix: Int): Long?
                inline fun toLowerCase(): String
                inline fun toLowerCase(locale: java.util.Locale): String
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
                inline fun toUpperCase(): String
                inline fun toUpperCase(locale: java.util.Locale): String
                fun translateEscapes(): String
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
            }
            // from kotlin
            inline infix fun <T> Comparable<T>.compareTo(other: T): Int
            // from kotlin.text
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
            inline fun CharSequence.getOrElse(index: Int, defaultValue: (Int) -> Char): Char
            fun CharSequence.getOrNull(index: Int): Char?
            inline fun <K> CharSequence.groupBy(keySelector: (Char) -> K): Map<K, List<Char>>
            inline fun <K, V> CharSequence.groupBy(keySelector: (Char) -> K, valueTransform: (Char) -> V): Map<K, List<V>>
            inline fun <K, M : MutableMap<in K, MutableList<Char>>> CharSequence.groupByTo(destination: M, keySelector: (Char) -> K): M
            inline fun <K, V, M : MutableMap<in K, MutableList<V>>> CharSequence.groupByTo(destination: M, keySelector: (Char) -> K, valueTransform: (Char) -> V): M
            inline fun <K> CharSequence.groupingBy(keySelector: (Char) -> K): Grouping<Char, K>
            fun CharSequence.hasSurrogatePairAt(index: Int): Boolean
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
            fun CharSequence.subSequence(range: IntRange): CharSequence
            inline fun CharSequence.substring(startIndex: Int, endIndex: Int = …): String
            fun CharSequence.substring(range: IntRange): String
            inline fun CharSequence.sumBy(selector: (Char) -> Int): Int
            inline fun CharSequence.sumByDouble(selector: (Char) -> Double): Double
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
            companion object Companion {
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
    fun `should mark parameter of delegated override inheriting default value`() {
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
        // KFunction1 is a function reference type, not a function type
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.FunctionTypesSample"
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
            @JvmInline value class ValueComparable(val x: Int) : Comparable<ValueComparable> {
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
    fun `should keep final modality of override in enum class with entry bodies`() {
        // an enum class is final in the metadata, even when its entries subclass it
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.EntryBodiesEnum"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.EntryBodiesEnum [page 1/1]
            enum class EntryBodiesEnum : EnumOverridable {
                A,
                B;
                override fun f(): Int
                final override fun g()
            }
        """.trimIndent()
    }

    @Test
    fun `should keep final modality of override in enum class without entry bodies`() {
        // the metadata does not tell whether the entries have bodies
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.PlainEnum"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.PlainEnum [page 1/1]
            enum class PlainEnum : EnumOverridable {
                A,
                B;
                override fun f(): Int
                final override fun g()
            }
        """.trimIndent()
    }

    @Test
    fun `should keep abstract and final modality of override in sealed class`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.SealedModalityOverrides"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.SealedModalityOverrides [page 1/1]
            sealed class SealedModalityOverrides : ModalityBase {
                abstract override fun abstracted()
                final override fun finalized()
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
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.FunctionTypeImpl"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.FunctionTypeImpl [page 1/1]
            class FunctionTypeImpl : (String) -> Int {
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
        // the unsigned types are described by kotlin-reflect, not by their Kotlin metadata
        resolver.resolve("kotlin.UInt", pageSize = 1) should {
            have(lines()[1] == "@JvmInline value class UInt : Comparable<UInt> {")
        }
    }

    @Test
    fun `should resolve standard library value class`() {
        resolver.resolve("kotlin.time.Duration", pageSize = 1) should {
            have(lines()[1] == "@JvmInline value class Duration : Comparable<Duration> {")
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
    fun `should resolve extension of built-in type only from the standard library, not from kotlin-reflect`() {
        // both kotlin-reflect and the standard library declare KClass.cast,
        // the kotlin.reflect.full one is not listed with KClass, depending on kotlin-reflect being on the classpath
        resolver.resolve(
            "kotlin.reflect.KClass.cast"
        ) sameAsKotlin """
            // kotlin.reflect.KClass.cast [page 1/1]
            // from kotlin.reflect
            fun <T : Any> KClass<T>.cast(value: Any?): T
        """.trimIndent()
    }

    @Test
    fun `should not list extensions of built-in type from kotlin-reflect`() {
        resolver.resolve("kotlin.reflect.KClass", pageSize = 10000) should {
            have(!contains("// from kotlin.reflect.full"))
            have(!contains("// from kotlin.reflect.jvm"))
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
        resolver.resolve(
            "kotlin.collections.MutableList"
        ) sameAsKotlin """
            // kotlin.collections.MutableList [page 1/34]
            interface MutableList<E> : List<E>, MutableCollection<E> {
                override fun add(element: E): Boolean
                fun add(index: Int, element: E)
                override fun addAll(elements: Collection<E>): Boolean
                fun addAll(index: Int, elements: Collection<E>): Boolean
                fun <T> asReversed(): MutableList<T>
                override fun clear()
                inline fun <T> fill(value: T)
                override fun listIterator(): MutableListIterator<E>
                override fun listIterator(index: Int): MutableListIterator<E>
                override fun remove(element: E): Boolean
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
            // not redeclared by String, just inherited from Any
            have("    fun hashCode(): Int" in lines())
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
            operator fun compareTo(other: Double): Int
            operator fun compareTo(other: Float): Int
            override operator fun compareTo(other: Int): Int
            operator fun compareTo(other: Long): Int
            operator fun compareTo(other: Short): Int
            // from kotlin
            inline infix fun <T> Comparable<T>.compareTo(other: T): Int
        """.trimIndent()
    }

    @Test
    fun `should resolve Any members as not overriding`() {
        resolver.resolve("kotlin.Any") should {
            have("    operator fun equals(other: Any?): Boolean" in lines())
            have("    fun hashCode(): Int" in lines())
            have("    fun toString(): String" in lines())
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
                fun errorMember(): Int
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
    fun `should not render primary constructor deprecated with hidden level`() {
        resolver.resolve(
            "com.xemantic.kotlin.introspection.test.HiddenOnlyConstructor"
        ) sameAsKotlin """
            // com.xemantic.kotlin.introspection.test.HiddenOnlyConstructor [page 1/1]
            class HiddenOnlyConstructor {
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
            have("    override fun toChar(): Char" in lines())
        }
    }

    @Test
    fun `should resolve member of built-in type deprecated without explicit level`() {
        resolver.resolve("kotlin.Number.toChar") sameAsKotlin """
            // kotlin.Number.toChar [page 1/1]
            fun toChar(): Char
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

}
