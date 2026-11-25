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

import org.jetbrains.annotations.NotNull;

/**
 * Declares {@code put(@NotNull Integer)} below a Kotlin class joining the {@code put(t: T)} of {@code GenericPut<T>}, given {@code Integer}
 * by its Java superclass and {@code Int} by itself, with the hidden {@code put(t: T?)} of {@code HiddenNullableTypeGenericPut<Int>},
 * one member there, which the method overrides, taking {@code Int} as annotated.
 */
public abstract class JavaNotNullBoxedPutBelowHiddenNullableTypeJoin
    extends com.xemantic.kotlin.introspection.test.HiddenNullableTypeGenericPutJoin {

    public String put(@NotNull Integer t) {
        return "";
    }

}
