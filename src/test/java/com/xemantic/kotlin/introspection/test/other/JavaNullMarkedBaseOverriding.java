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
 * A Java class not marked overriding the methods of {@code JavaNullMarkedBase} not annotated,
 * which inherit the types the mark tells not nullable, but the ones of the type variables, which stay platform types.
 */
public abstract class JavaNullMarkedBaseOverriding extends JavaNullMarkedBase<String, String> {

    @Override
    public abstract String label(String value);

    @Override
    public abstract String find(String key);

    @Override
    public abstract String first(String fallback);

    @Override
    public abstract String nullable(String value);

}
