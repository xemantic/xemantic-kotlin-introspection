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
 * A generic Java class of generic methods, inherited through its raw subclass, see {@code JavaRawGenericMid}.
 */
public abstract class JavaRawGenericBase<T> {

    public abstract <U> U convert(Object value, U fallback);

    public abstract <U extends CharSequence> java.util.List<U> names(java.util.Map<T, U> index);

    public abstract java.util.List<T> items();

    @SuppressWarnings("unchecked")
    public abstract <U> U[] all(U... elements);

}
