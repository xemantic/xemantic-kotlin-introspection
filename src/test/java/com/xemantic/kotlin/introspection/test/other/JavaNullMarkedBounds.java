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
 * A Java class marked by {@code @NullMarked} of JSpecify, whose bounds of type parameters not annotated the compiler takes as not nullable,
 * like {@code <U : Any>} of {@code <U>}, but the one annotated nullable, also of a method marked by {@code @NullUnmarked},
 * as the compiler reads the bounds by the mark of the class.
 */
@org.jspecify.annotations.NullMarked
public abstract class JavaNullMarkedBounds {

    public abstract <U> U id(U value);

    public abstract <V extends Number> V number(V value);

    public abstract <W extends @org.jspecify.annotations.Nullable Object> W nullable(W value);

    @org.jspecify.annotations.NullUnmarked
    public abstract <U> U unmarked(U value);

}
