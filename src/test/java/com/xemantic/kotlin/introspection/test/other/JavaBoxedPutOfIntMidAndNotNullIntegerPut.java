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
 * Gives {@code Int}, not nullable, for the type parameter of {@code GenericPut<T>} through a Kotlin interface
 * implementing the generic {@code JavaGenericMidPut<T>}, before {@code @NotNull Integer} along another path,
 * so that its {@code put(Integer)} takes {@code Int}.
 */
public abstract class JavaBoxedPutOfIntMidAndNotNullIntegerPut
    implements com.xemantic.kotlin.introspection.test.IntGenericMidPut, JavaNotNullIntegerPutInterface {

    public String put(Integer t) {
        return "";
    }

}
