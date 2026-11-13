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
 * A Java class not marked by {@code @NullMarked} of generic methods: one marked by itself, whose type variable stays a platform type,
 * one of a type variable bounded by another one of a bound annotated not nullable, which the compiler takes as given,
 * and one of an {@code Object} bound next to another one.
 */
public class JavaMethodTypeVariables {

    @org.jspecify.annotations.NullMarked
    public <T> void marked(T t) {
    }

    @org.jspecify.annotations.NullMarked
    public <T> String markedString(T t, String s) {
        return s;
    }

    public <T extends @org.jspecify.annotations.NonNull Object, U extends T> void bounded(U u) {
    }

    public <T extends Object & Comparable<? super T>> void objectAndComparable(T t) {
    }

}
