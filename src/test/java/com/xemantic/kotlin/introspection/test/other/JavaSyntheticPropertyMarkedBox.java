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
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A Java class marked by {@code @NullMarked}, of accessors of its type variable, of a nullable bound,
 * which a subclass not marked gives as a platform type, see {@code JavaSyntheticPropertyMarkedBoxOfString},
 * and of accessors of a star projection next to the ones of {@code Object}, which is not nullable here.
 */
@NullMarked
public abstract class JavaSyntheticPropertyMarkedBox<T extends @Nullable Object> {

    public abstract T getValue();

    public abstract void setValue(T value);

    public abstract @NonNull T getStrict();

    public abstract void setStrict(T strict);

    public abstract T getLax();

    public abstract void setLax(@Nullable T lax);

    public abstract List<?> getStars();

    // a List<*> is no List<Any>
    public abstract void setStars(List<Object> stars);

    public abstract List<Object> getObjects();

    public abstract void setObjects(List<?> objects);

}
