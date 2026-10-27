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
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmVariance
import kotlin.metadata.isDefinitelyNonNull
import kotlin.metadata.isNullable
import kotlin.test.Test

/**
 * The substitution of the type arguments, and the type parameters whose uses as definitely not nullable,
 * like `T & Any`, are rendered so, exercised apart from the members rendering them.
 */
class TypeSubstitutionTest {

    private fun typeParameter(name: String, id: Int, vararg upperBounds: KmType): KmTypeParameter =
        KmTypeParameter(name, id, KmVariance.INVARIANT).also { it.upperBounds += upperBounds }

    private fun typeParameterUse(id: Int, nullable: Boolean = false, definitelyNonNull: Boolean = false): KmType =
        typeParameterType(id).also {
            it.isNullable = nullable
            it.isDefinitelyNonNull = definitelyNonNull
        }

    private val KmType.typeParameterId: Int? get() = (classifier as? KmClassifier.TypeParameter)?.id

    @Test
    fun `should tell type parameters of not nullable bounds`() {
        // given - <A : Any, B : A, C, D : C, E : String?, F : List<C>>
        val typeParameters = listOf(
            typeParameter("A", 0, classType("kotlin/Any")),
            typeParameter("B", 1, typeParameterUse(0)),
            typeParameter("C", 2, classType("kotlin/Any", nullable = true)),
            typeParameter("D", 3, typeParameterUse(2)),
            typeParameter("E", 4, classType("kotlin/String", nullable = true)),
            typeParameter(
                "F", 5,
                classType("kotlin/collections/List").also { it.arguments += KmTypeProjection(KmVariance.INVARIANT, typeParameterUse(2)) }
            )
        )

        // when
        val notNullable = notNullableTypeParameters(typeParameters)

        // then
        assert(notNullable == setOf(0, 1, 5))
    }

    @Test
    fun `should tell type parameter bounded by not nullable one declared after it`() {
        // given - <B : A, A : Any>
        val typeParameters = listOf(
            typeParameter("B", 0, typeParameterUse(1)),
            typeParameter("A", 1, classType("kotlin/Any"))
        )

        // when
        val notNullable = notNullableTypeParameters(typeParameters)

        // then
        assert(notNullable == setOf(0, 1))
    }

    @Test
    fun `should tell type parameter bounded by definitely not nullable type parameter`() {
        // given - <T, R : T & Any>
        val typeParameters = listOf(
            typeParameter("T", 0),
            typeParameter("R", 1, typeParameterUse(0, definitelyNonNull = true))
        )

        // when
        val notNullable = notNullableTypeParameters(typeParameters)

        // then
        assert(notNullable == setOf(1))
    }

    @Test
    fun `should tell type parameter bounded by not nullable one of enclosing scope`() {
        // given - <R : T>, <S : U> of a member, where the T of its class is not nullable, and the U is not known to be
        val typeParameters = listOf(
            typeParameter("R", 2, typeParameterUse(0)),
            typeParameter("S", 3, typeParameterUse(1))
        )

        // when
        val notNullable = notNullableTypeParameters(typeParameters, notNullable = setOf(0))

        // then
        assert(notNullable == setOf(0, 2))
    }

    @Test
    fun `should not tell type parameter bounded by nullable use of not nullable one`() {
        // given - <A : Any, B : A?>
        val typeParameters = listOf(
            typeParameter("A", 0, classType("kotlin/Any")),
            typeParameter("B", 1, typeParameterUse(0, nullable = true))
        )

        // when
        val notNullable = notNullableTypeParameters(typeParameters)

        // then
        assert(notNullable == setOf(0))
    }

    @Test
    fun `should substitute nullable use of type parameter with definitely not nullable type argument as nullable`() {
        // given - T? of T given U & Any, which is U?, as (U & Any)? does not compile
        val type = typeParameterUse(0, nullable = true)

        // when
        val substituted = type.substituted(mapOf(0 to typeParameterUse(1, definitelyNonNull = true)))

        // then
        assert(substituted.typeParameterId == 1 && substituted.isNullable && !substituted.isDefinitelyNonNull)
    }

    @Test
    fun `should substitute definitely not nullable use of type parameter with type parameter as definitely not nullable`() {
        // given - T & Any of T given U, which is U & Any
        val type = typeParameterUse(0, definitelyNonNull = true)

        // when
        val substituted = type.substituted(mapOf(0 to typeParameterUse(1)))

        // then
        assert(substituted.typeParameterId == 1 && !substituted.isNullable && substituted.isDefinitelyNonNull)
    }

    @Test
    fun `should substitute definitely not nullable use of type parameter with nullable type parameter as definitely not nullable`() {
        // given - T & Any of T given U?, which is U & Any
        val type = typeParameterUse(0, definitelyNonNull = true)

        // when
        val substituted = type.substituted(mapOf(0 to typeParameterUse(1, nullable = true)))

        // then
        assert(substituted.typeParameterId == 1 && !substituted.isNullable && substituted.isDefinitelyNonNull)
    }

    @Test
    fun `should substitute definitely not nullable use of type parameter with nullable class type as not nullable`() {
        // given - T & Any of T given String?, which is String
        val type = typeParameterUse(0, definitelyNonNull = true)

        // when
        val substituted = type.substituted(mapOf(0 to classType("kotlin/String", nullable = true)))

        // then
        assert(
            (substituted.classifier as? KmClassifier.Class)?.name == "kotlin/String" &&
                !substituted.isNullable && !substituted.isDefinitelyNonNull
        )
    }

    @Test
    fun `should substitute use of type parameter with definitely not nullable type argument as definitely not nullable`() {
        // given - T of T given U & Any
        val type = typeParameterUse(0)

        // when
        val substituted = type.substituted(mapOf(0 to typeParameterUse(1, definitelyNonNull = true)))

        // then
        assert(substituted.typeParameterId == 1 && !substituted.isNullable && substituted.isDefinitelyNonNull)
    }

    @Test
    fun `should tell type parameters declared in scope not nullable by the bounds of the enclosing ones`() {
        // given - the class scope of <T : Any, X>, and the <R : T, S : X> of its member
        val classScope = TypeParameterScope(mapOf(0 to "T", 1 to "X"), notNullable = setOf(0))

        // when
        val scope = classScope.withDeclared(listOf(typeParameter("R", 2, typeParameterUse(0)), typeParameter("S", 3, typeParameterUse(1))))

        // then
        assert(scope.names == mapOf(0 to "T", 1 to "X", 2 to "R", 3 to "S"))
        assert(scope.notNullable == setOf(0, 2))
    }

    @Test
    fun `should render definitely not nullable use of type parameter by the bounds in scope`() {
        // given - T & Any, and U & Any, of the type parameters of <T, U : Any>
        val introspector = DefaultKotlinIntrospector(scanEntireClasspath = false)
        val scope = TypeParameterScope(mapOf(0 to "T", 1 to "U"), notNullable = setOf(1))

        // when
        val rendered = listOf(0, 1).map { introspector.formatType(typeParameterUse(it, definitelyNonNull = true), typeParameters = scope) }

        // then - U & Any of a U : Any does not compile
        assert(rendered == listOf("T & Any", "U"))
    }

    @Test
    fun `should renumber type parameters of scope with the not nullable ones`() {
        // given - the scope of <T : Any, X> of an inheriting class
        val scope = TypeParameterScope(mapOf(0 to "T", 1 to "X"), notNullable = setOf(0))

        // when
        val renumbered = scope.mapIds { it + 100 }

        // then
        assert(renumbered.names == mapOf(100 to "T", 101 to "X") && renumbered.notNullable == setOf(100))
    }

    @Test
    fun `should join scopes with their not nullable type parameters`() {
        // given - the scope of a supertype <A : Any, B>, and the one of an inheriting class <U : Any>, renumbered apart
        val supertypeScope = TypeParameterScope(mapOf(0 to "A", 1 to "B"), notNullable = setOf(0))
        val inheritingScope = TypeParameterScope(mapOf(100 to "U"), notNullable = setOf(100))

        // when
        val scope = supertypeScope + inheritingScope

        // then
        assert(scope.names == mapOf(0 to "A", 1 to "B", 100 to "U") && scope.notNullable == setOf(0, 100))
    }

    @Test
    fun `should tell no type parameter not nullable when marking definitely not nullable`() {
        // given
        val names = mapOf(0 to "T", 1 to "R")

        // when
        val scope = TypeParameterScope.markingDefinitelyNotNull(names)

        // then
        assert(scope.names == names && scope.notNullable.isEmpty())
    }

    @Test
    fun `should render no definitely not nullable use when ignoring definitely not nullable`() {
        // given - T & Any of a T in scope, and of a type parameter outside of it, like the -1 of kotlin-reflect
        val introspector = DefaultKotlinIntrospector(scanEntireClasspath = false)
        val scope = TypeParameterScope.ignoringDefinitelyNotNull(mapOf(0 to "T"))

        // when
        val rendered = listOf(0, -1).map { introspector.formatType(typeParameterUse(it, definitelyNonNull = true), typeParameters = scope) }

        // then - the type parameter outside of the scope is rendered as T, like any unknown one
        assert(rendered == listOf("T", "T"))
    }

    @Test
    fun `should keep ignoring definitely not nullable in scope with declared type parameters`() {
        // given - the R & Any of a member <R> declared in a scope ignoring definitely not nullable
        val introspector = DefaultKotlinIntrospector(scanEntireClasspath = false)
        val scope = TypeParameterScope.ignoringDefinitelyNotNull(mapOf(0 to "T")).withDeclared(listOf(typeParameter("R", 1)))

        // when
        val rendered = introspector.formatType(typeParameterUse(1, definitelyNonNull = true), typeParameters = scope)

        // then
        assert(rendered == "R")
    }

}
