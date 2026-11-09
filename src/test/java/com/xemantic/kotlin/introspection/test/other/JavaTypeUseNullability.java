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
 * A Java class of the type arguments, and of the array elements, annotated nullable, or not, by type annotations,
 * which the compiler reads, and of a declaration annotation of both targets next to the same one on a type argument.
 */
public abstract class JavaTypeUseNullability {

    public abstract java.util.List<@org.jspecify.annotations.Nullable String> names();

    public abstract @org.jspecify.annotations.Nullable String[] values();

    public abstract String @org.jspecify.annotations.Nullable [] array();

    public abstract void index(java.util.Map<String, @org.jspecify.annotations.Nullable String> index);

    public abstract java.util.List<? extends @org.jspecify.annotations.Nullable CharSequence> sequences();

    public abstract java.util.@org.jspecify.annotations.Nullable List<@org.jspecify.annotations.Nullable String> both();

    public abstract void entry(@org.jetbrains.annotations.NotNull java.util.Map.Entry<@org.jetbrains.annotations.NotNull String, String> entry);

}
