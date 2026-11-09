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
 * A Java class not marked, of methods marked by {@code @NullMarked}, whose types not annotated are not nullable,
 * but not the bounds of their type parameters, which the compiler reads by the mark of the class,
 * and of its type variable, whose mark the overrides do not inherit, see {@code JavaMethodNullMarkedOverriding}.
 */
public abstract class JavaMethodNullMarked<N> {

    @org.jspecify.annotations.NullMarked
    public abstract <U> U id(U value);

    @org.jspecify.annotations.NullMarked
    public abstract N put(N value);

}
