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
 * A generic Java class whose methods a Kotlin class inherits through {@link JavaInheritedMid}.
 */
public class JavaInheritedBase<T> {

    public T first(List<T> items) {
        return null;
    }

    public void accept(T item) {
    }

    protected void protectedHelper() {
    }

    @Deprecated
    public void legacyHelper() {
    }

    void packagePrivateHelper() {
    }

    public static void staticHelper() {
    }

    @Override
    public String toString() {
        return "";
    }

}
