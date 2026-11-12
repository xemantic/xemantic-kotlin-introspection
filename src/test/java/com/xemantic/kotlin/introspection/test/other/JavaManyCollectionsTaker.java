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
 * Takes seven collection types in three parameters, each the read-only or the mutable one in Kotlin,
 * like {@code (Mutable)Map<(Mutable)List<String!>!, (Mutable)List<String!>!>!}, merged with a Kotlin member
 * taking either of each.
 */
public abstract class JavaManyCollectionsTaker {

    public String take(
        java.util.Map<java.util.List<String>, java.util.List<String>> first,
        java.util.Map<java.util.List<String>, java.util.List<String>> second,
        java.util.List<String> third
    ) {
        return "";
    }

}
