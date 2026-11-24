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
 * Gives {@code @NotNull Integer} for the type parameter of {@code GenericPut<T>} along one path,
 * and {@code @Nullable Integer} through the generic {@code JavaGenericMidPut<T>} along another,
 * which disagree, so that its {@code put(Integer)} takes {@code Int!}.
 */
public abstract class JavaBoxedPutOfNotNullAndNullableMidIntegerPut implements JavaNotNullIntegerPutInterface, JavaNullableIntegerMidPut {

    public String put(Integer t) {
        return "";
    }

}
