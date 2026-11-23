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
 * Declares {@code put(Integer, String)} next to the {@code put(k: K, v: V)} of {@code Put2<K, V>}, given {@code Int} by the Kotlin {@code MidPut2<T>},
 * and {@code String} by this class, which it does not implement for K2.
 */
public abstract class JavaBoxedMidPut2 implements com.xemantic.kotlin.introspection.test.MidPut2<String> {

    public String put(Integer k, String v) {
        return "";
    }

}
