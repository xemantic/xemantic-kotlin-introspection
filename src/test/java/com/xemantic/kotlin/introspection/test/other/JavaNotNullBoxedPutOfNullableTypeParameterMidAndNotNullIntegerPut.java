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

package com.xemantic.kotlin.introspection.test.other;

/**
 * Gives {@code Int?} for the type parameter of {@code GenericPut<T>} through a generic Kotlin interface passing {@code T?}
 * on to the generic {@code JavaGenericMidPut<T>}, and {@code @NotNull Integer} along another path,
 * which disagree, so that its {@code put(@NotNull Integer)} takes {@code Int!}.
 */
public abstract class JavaNotNullBoxedPutOfNullableTypeParameterMidAndNotNullIntegerPut
    implements com.xemantic.kotlin.introspection.test.IntNullableTypeParameterMidPut, JavaNotNullIntegerPutInterface {

    public String put(@org.jetbrains.annotations.NotNull Integer t) {
        return "";
    }

}
