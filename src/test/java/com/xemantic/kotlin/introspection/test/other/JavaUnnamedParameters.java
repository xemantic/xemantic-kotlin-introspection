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
 * A Java class compiled without the {@code -parameters} option, like the JDK,
 * so that kotlin-reflect names the parameters of its methods after their position, like {@code arg0}.
 */
public class JavaUnnamedParameters {

    public String indent(int n) {
        return null;
    }

    public String format(Object... args) {
        return null;
    }

    public String describe(Object value) {
        return null;
    }

    public <T> T first(T value) {
        return null;
    }

    @SafeVarargs
    public final <T> java.util.List<T> listOf(T... values) {
        return null;
    }

}
