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

import com.xemantic.kotlin.introspection.test.GenericPut;
import com.xemantic.kotlin.introspection.test.HiddenOpenGenericPut;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Overrides the hidden {@code put(t: T)} of {@code HiddenOpenGenericPut<Integer>}, and the visible one of {@code GenericPut<@Nullable Integer>},
 * by {@code put(@NotNull Integer)}, whose nullabilities disagree, a platform type then.
 */
public abstract class JavaNotNullBoxedPutOverHiddenOpenAndNullableGenericPut extends HiddenOpenGenericPut<Integer>
    implements GenericPut<@Nullable Integer> {

    public String put(@NotNull Integer t) {
        return "";
    }

}
