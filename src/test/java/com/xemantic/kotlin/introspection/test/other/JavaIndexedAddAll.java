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

import java.util.Collection;

/**
 * Declares the {@code addAll(int, Collection<? extends E>)} of {@code java.util.List} of {@code Integer},
 * seen as {@code Collection<out Int>}, the {@code Collection<Int>} of {@code addAll(index, elements)} of {@code MutableList<Int>},
 * as {@code Collection} is covariant, so that it implements it when merged with it.
 */
public abstract class JavaIndexedAddAll {

    public boolean addAll(int index, Collection<? extends Integer> elements) {
        return true;
    }

}
