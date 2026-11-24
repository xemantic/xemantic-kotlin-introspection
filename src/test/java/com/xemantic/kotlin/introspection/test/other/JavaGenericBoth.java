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

/**
 * A generic Java class of a method taking its type variable, which a Kotlin class given a nullable type argument
 * overrides only by a function taking the nullable type, and of a method bounded by its type variable, a platform type,
 * which a Kotlin class given its type parameter overrides also by one bounded by the definitely not nullable type,
 * and of a method taking its type variable annotated not null, which a function not nullable overrides, whatever given.
 */
public class JavaGenericBoth<E> {

    public <T extends E> void both(E a, T b) {}

    public <T extends E> void bounded(T x) {}

    public void notNull(@NonNull E x) {}

}
