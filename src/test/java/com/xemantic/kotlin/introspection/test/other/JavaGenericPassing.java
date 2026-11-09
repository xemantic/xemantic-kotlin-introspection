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
 * A generic Java class not marked, overriding the method of {@code JavaNullMarkedPassingBase} by its own type variable,
 * which the compiler keeps as given, a platform type, unlike the type variable the mark keeps as given.
 */
public abstract class JavaGenericPassing<X> extends com.xemantic.kotlin.introspection.test.marked.JavaNullMarkedPassingBase<X> {

    @Override
    public abstract X pass(X value);

}
