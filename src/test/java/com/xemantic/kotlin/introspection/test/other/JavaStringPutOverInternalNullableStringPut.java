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

import com.xemantic.kotlin.introspection.test.StringPut;
import org.jetbrains.annotations.Nullable;

/**
 * Gives {@code @Nullable String} for the type parameter of the internal {@code InternalAbstractPut<T>}, next to {@code StringPut},
 * giving {@code String}, overriding both by {@code put(String)}, a platform type then, as the nullabilities disagree.
 */
public abstract class JavaStringPutOverInternalNullableStringPut extends com.xemantic.kotlin.introspection.test.InternalAbstractPut<@Nullable String>
    implements StringPut {

    @Override
    public String put(String t) {
        return "";
    }

}
