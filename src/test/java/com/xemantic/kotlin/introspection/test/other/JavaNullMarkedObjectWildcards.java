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

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * A Java class marked by {@code @NullMarked}, of the wildcards {@code ? extends Object}, which stand for {@code out Any} then,
 * unlike the star projection {@code ?}, the {@code out Any?}, though the reflection tells them apart neither.
 */
@NullMarked
public abstract class JavaNullMarkedObjectWildcards {

    public abstract List<? extends Object> getBounded();

    public abstract void setBounded(List<Object> bounded);

    public abstract Collection<?> getStarred();

    public abstract void setStarred(Collection<? extends Object> starred);

    public abstract List<? extends @Nullable Object> getLax();

    public abstract void setLax(List<@Nullable Object> lax);

    // a platform type, the star projection
    @NullUnmarked
    public abstract Class<? extends Object> getKlass();

    @NullUnmarked
    public abstract void setKlass(Class<?> klass);

}
