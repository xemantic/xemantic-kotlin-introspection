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

package com.xemantic.kotlin.introspection.isolated;

import java.util.List;

/**
 * A Java class with a method referring to {@link Absent}, which cannot be reflected without it.
 */
public abstract class AbsentJavaBase<T> {

    public void process(T item) {
    }

    public void run() {
    }

    public void take(Absent absent) {
    }

    public void take(DollarHolder.Dollar$Nested nested) {
    }

    public void prim(int x) {
    }

    public <S extends T> void bounded(S x) {
    }

    public void process(T[] items) {
    }

    public <S extends T> void boundedArray(S[] items) {
    }

    public void nested(T[][] items) {
    }

    public void lists(List<T>[] items) {
    }

}
