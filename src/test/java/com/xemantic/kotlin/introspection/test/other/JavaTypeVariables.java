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
 * A Java class of generic methods taking their type variables, which the compiler takes as given, not as platform types,
 * when in a contravariant position of a bound, like the {@code T} of {@code Comparable<T>},
 * or of a {@code KotlinSink<KotlinSource<T>>}, also through another type variable, unlike the ones of the other bounds,
 * like the {@code T} of {@code Comparable<Comparable<T>>}, covariant again.
 */
public class JavaTypeVariables {

    public <T extends Comparable<T>> void rec(T value) {
    }

    public <T extends Comparable<? super T>> T max(T first, T second) {
        return first;
    }

    public <T extends Comparable<T>, U extends T> void via(U value) {
    }

    @SafeVarargs
    public final <T extends Comparable<T>> void all(T... values) {
    }

    public <T extends Comparable<T>> void annotated(@org.jetbrains.annotations.Nullable T value) {
    }

    public <T, U extends Comparable<T>> void other(T value, U comparable) {
    }

    public <T extends kotlin.jvm.functions.Function1<T, kotlin.Unit>> void function(T value) {
    }

    public <T extends CharSequence> void bounded(T value) {
    }

    public <T extends Comparable<java.util.List<T>>> void nested(T value) {
    }

    public <T extends java.util.function.Consumer<? super T>> void consumer(T value) {
    }

    public <T extends Enum<T>> void enumerated(T value) {
    }

    public <T extends @org.jspecify.annotations.Nullable CharSequence> void nullableBound(T value) {
    }

    public <T extends Comparable<Comparable<T>>> void twice(T value) {
    }

    public <T extends java.util.List<Comparable<T>>> void listed(T value) {
    }

    public <T extends com.xemantic.kotlin.introspection.test.KotlinSink<com.xemantic.kotlin.introspection.test.KotlinSink<T>>>
    void sinkOfSink(T value) {
    }

    public <T extends com.xemantic.kotlin.introspection.test.KotlinSink<com.xemantic.kotlin.introspection.test.KotlinSource<T>>>
    void sinkOfSource(T value) {
    }

}
