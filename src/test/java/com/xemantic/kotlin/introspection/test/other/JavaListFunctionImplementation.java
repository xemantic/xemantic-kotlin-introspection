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
 * A Java class implementing {@code java.util.function.Function} of lists of its own type variable,
 * whose {@code apply} takes, and returns, the flexible {@code (Mutable)List<T!>!}.
 */
public class JavaListFunctionImplementation<T> implements java.util.function.Function<java.util.List<T>, java.util.List<T>> {

    @Override
    public java.util.List<T> apply(java.util.List<T> value) {
        return value;
    }

}
