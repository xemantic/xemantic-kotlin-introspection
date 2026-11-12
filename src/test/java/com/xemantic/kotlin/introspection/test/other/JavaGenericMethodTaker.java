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
 * Declares generic methods returning {@code void}, which the compiler picks over the Kotlin members merged with them,
 * when its class is declared first, whose type parameters are numbered apart from the ones of the Kotlin members.
 */
public abstract class JavaGenericMethodTaker {

    public <T> void take2(T value, Class<T> type) {
    }

    public <A extends CharSequence, B> void pair(A first, B second) {
    }

}
