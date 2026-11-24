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

import java.util.List;

/**
 * A generic Java class of generic methods whose type variables are bounded by its type variable, of platform types,
 * which a Kotlin override declares of either nullability, like {@code T : CharSequence?} of {@code T extends E}
 * for a {@code JavaGenericBoundsBase<CharSequence>}, also where it is a type argument of the bound.
 */
public class JavaGenericBoundsBase<E> {

    public <T extends E> void bounded(T x) {
    }

    public <T extends Comparable<E>> void compared(T x) {
    }

    public <T extends List<? extends E>> void listed(T x) {
    }

}
