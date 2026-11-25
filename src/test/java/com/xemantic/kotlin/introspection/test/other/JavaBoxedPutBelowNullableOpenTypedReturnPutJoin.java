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
 * Declares {@code put(Integer)} below a Kotlin class joining the {@code put(t: T)} of {@code GenericPut<@Nullable Integer>},
 * declared first, taking {@code Int?}, with the one of {@code OpenGenericPut<Integer>}, taking {@code Int!},
 * next to the {@code put(t: T): R} of {@code GenericTypedReturnPut<Int, String>}, taking {@code Int}, a member apart there.
 */
public abstract class JavaBoxedPutBelowNullableOpenTypedReturnPutJoin
    extends com.xemantic.kotlin.introspection.test.NullableOpenTypedReturnPutJoin {

    @Override
    public String put(Integer t) {
        return "";
    }

}
