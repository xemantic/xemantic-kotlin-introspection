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

import com.xemantic.kotlin.introspection.test.*;

/**
 * The Java interfaces passing their type arguments to the Kotlin ones using their type parameter
 * as definitely not nullable, or as nullable, seen by Kotlin as {@code PlatformBox<String>}, of {@code String! & Any},
 * as {@code PlatformBox<PlatformWrap<String!>>}, of {@code PlatformWrap<String!>! & Any},
 * and as {@code PlatformBox<PlatformWrap<String!>?>}, of {@code PlatformWrap<String!>!?}.
 */
public interface JavaPlatformBoxes {

    interface NotNullString extends PlatformNotNullBox<String> {}

    interface NotNullWrap extends PlatformNotNullBox<PlatformWrap<String>> {}

    interface NullableWrap extends PlatformNullableBox<PlatformWrap<String>> {}

    interface Passing<T> extends PlatformBox<T> {}

    interface NotNullPassing<T> extends PlatformNotNullBox<T> {}

    interface OtherPassing<T> extends PlatformOther<T> {}
}
