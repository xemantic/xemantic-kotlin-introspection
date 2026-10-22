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

package com.xemantic.kotlin.introspection.isolated;

/**
 * A generic Java class with inner classes, one of them shadowing its type variable,
 * and a method whose type variable shadows the one of the class,
 * each of them declaring a method referring to {@link Absent}, so that it cannot be reflected without it.
 */
public class AbsentJavaOuter<T> {

    public <T extends Number> void shadowing(T x) {
    }

    public void take(Absent absent) {
    }

    public class Inner<U> {

        public void outer(T x) {
        }

        public void inner(U x) {
        }

        public void take(Absent absent) {
        }

    }

    public class Shadowing<T> {

        public void shadowed(T x) {
        }

        public void take(Absent absent) {
        }

    }

}
