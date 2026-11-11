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
 * A Java class of generic methods taking their type variables: platform types, also the ones bounded by a {@code Comparable}
 * of themselves, for which the compiler rejects {@code null} only for a type argument declared by Kotlin,
 * unlike the one of an annotated bound, which the compiler takes as given, whatever the type argument,
 * when it is its single bound.
 */
public class JavaTypeVariables {

    public <T extends Comparable<T>> void rec(T value) {
    }

    public <T extends Comparable<? super T>> T max(T first, T second) {
        return first;
    }

    public <T extends CharSequence> void bounded(T value) {
    }

    public <T extends @org.jspecify.annotations.Nullable CharSequence> void nullableBound(T value) {
    }

    public <T extends java.io.Serializable & @org.jspecify.annotations.NonNull Cloneable> void severalBounds(T value) {
    }

}
