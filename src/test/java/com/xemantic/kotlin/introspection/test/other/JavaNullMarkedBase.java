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
 * A Java class marked by {@code @NullMarked} of JSpecify, whose types not annotated the compiler takes as not nullable,
 * of a type variable of a nullable bound, which stays as given, and of a method, and of a nested class, marked otherwise.
 */
@org.jspecify.annotations.NullMarked
public abstract class JavaNullMarkedBase<T, N extends @org.jspecify.annotations.Nullable Object> {

    public abstract String label(String value);

    public abstract @org.jspecify.annotations.Nullable String find(@org.jspecify.annotations.Nullable String key);

    public abstract T first(T fallback);

    public abstract N nullable(N value);

    public abstract java.util.List<String> names(java.util.List<? extends T> items);

    @org.jspecify.annotations.NullUnmarked
    public abstract String unmarked(String value);

    @org.jspecify.annotations.NullUnmarked
    public abstract static class Unmarked {

        public abstract String label(String value);

    }

    public abstract static class Nested {

        public abstract String label(String value);

    }

}
