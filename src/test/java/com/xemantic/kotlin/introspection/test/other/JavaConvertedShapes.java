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
 * The shapes of the types of Java methods converted from the Java reflection, see {@code toKmJavaFunction},
 * compared with the ones kotlin-reflect describes.
 */
public abstract class JavaConvertedShapes<T extends Comparable<? super T>, U> {

    public class Inner<V> {
    }

    public static class Nested<W> {
    }

    public abstract java.util.List rawList();

    public abstract java.util.Map rawMap(java.util.Map map);

    public abstract T[] genericArray(U[] array);

    public abstract java.util.List<String>[] parameterizedArray();

    public abstract int[][] primitiveMatrix(long... values);

    @SafeVarargs
    public final <E> java.util.List<E> elements(E... elements) {
        return null;
    }

    public abstract void objects(Object... objects);

    public abstract Inner<String> inner();

    public abstract JavaConvertedShapes<T, U>.Inner<U> qualifiedInner();

    public abstract Nested<T> nested();

    public abstract java.util.List<? extends Object> anyElements();

    public abstract java.util.List<? super Integer> integerConsumer(java.util.Collection<? super T> sink);

    public abstract <S extends T> S bounded(java.util.Comparator<? super S> comparator);

    public abstract <K extends Comparable<K> & java.io.Serializable> K multipleBounds();

    public abstract Integer boxed(Integer value, Character character);

    public abstract Void nothing(Void value);

    public abstract java.util.Iterator<java.util.Map.Entry<String, ? extends Number>> entries();

    public abstract java.util.function.Function<? super T, ? extends U> function(java.util.function.BiFunction<T, ? super U, ?> function);

    protected abstract String protectedMember(String value);

    abstract String packagePrivateMember();

    public abstract Object plus(Object other);

    public abstract String get(int index, int column);

    public abstract void set(int index, String value);

    public abstract boolean contains(String value);

    public abstract java.util.Iterator<T> iterator();

    public abstract @org.jetbrains.annotations.NotNull String notNull(@org.jetbrains.annotations.NotNull String value, @org.jetbrains.annotations.Nullable U other);

    public abstract @org.jetbrains.annotations.Nullable T nullable(@org.jetbrains.annotations.Nullable T value);

}
