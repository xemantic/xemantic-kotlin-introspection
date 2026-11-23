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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A generic Java class of generic methods whose type variables are of annotated bounds: the annotation tells the bound itself,
 * neither the type variable a method takes, nor the ones nested in the bound, which stay platform types,
 * and an annotated collection nested in the bound is still of either mutability.
 */
public class JavaAnnotatedBoundsBase<E> {

    public <T extends @NonNull CharSequence> void notNull(T x) {
    }

    public <T extends @NonNull Comparable<T>> void nested(T x) {
    }

    public <T extends @Nullable E> void nullable(T x) {
    }

    public <T extends List<@NonNull E>> void listed(T x) {
    }

    public <T extends List<@NonNull List<String>>> void listedList(T x) {
    }

    public <T extends Map<String, @NonNull List<@Nullable String>>> void mapped(T x) {
    }

}
