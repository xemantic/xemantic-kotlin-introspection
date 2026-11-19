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

import com.xemantic.kotlin.introspection.test.GenericAcceptor;

/**
 * Implements the {@code accept(t: T)} and {@code put2(t: T, tag: String)} of {@code GenericAcceptor<T>}, given {@code String},
 * taking {@code String!} in Kotlin where they take the type parameter {@code T}, and the declared {@code String} otherwise.
 */
public abstract class JavaStringGenericAcceptor implements GenericAcceptor<String> {

    @Override
    public void accept(String value) {
    }

    @Override
    public String put2(String value, String tag) {
        return tag;
    }

}
