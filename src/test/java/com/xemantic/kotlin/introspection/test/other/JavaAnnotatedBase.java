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
 * A Java class of methods annotated nullable, or not, whose overrides inherit the annotations, see {@code JavaAnnotationInheriting}.
 */
public abstract class JavaAnnotatedBase<T> {

    public abstract @org.jetbrains.annotations.Nullable String find(@org.jetbrains.annotations.NotNull String key);

    public abstract @org.jetbrains.annotations.NotNull T resolve(@org.jetbrains.annotations.NotNull T value);

    public abstract @org.jetbrains.annotations.Nullable String lookup(@org.jetbrains.annotations.Nullable String key);

    public abstract @org.jetbrains.annotations.Nullable String other(@org.jetbrains.annotations.NotNull String key);

}
