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
 * Takes boxed {@code Integer} parameters, JVM methods of their own next to the members of built-in types,
 * like {@code get(index: Int)} of {@code List}, {@code compareTo(other: T)} of {@code Comparable<Int>},
 * or {@code add(element: E)} and {@code contains(element: E)} of {@code MutableCollection<Int>}.
 */
public abstract class JavaBoxedMembers {

    public String get(Integer index) {
        return "";
    }

    public int compareTo(Integer other) {
        return 0;
    }

    public boolean add(Integer element) {
        return true;
    }

    public boolean contains(Integer element) {
        return true;
    }

}
