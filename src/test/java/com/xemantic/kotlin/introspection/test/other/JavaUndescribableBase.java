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
 * A Java class whose members kotlin-reflect cannot describe, failing on all of them at once,
 * as on the raw {@code Enum} of {@code Class<? extends Enum>}, like on the ones of {@code java.util.LinkedHashSet} since JDK 21.
 */
public abstract class JavaUndescribableBase<T> {

    public abstract Class<? extends Enum> enumType();

    public String describe(java.util.List<? extends T> items, int... widths) {
        return "";
    }

    protected abstract T first();

    public final java.util.Map<String, T> index() {
        return null;
    }

}
