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
 * The Java interfaces passing a platform type of their type argument to the Kotlin ones,
 * like {@code BoundedReceiverNullableChain0<BoundedReceiverWrap<T!>!>} of {@code Chain1<T>},
 * next to {@code BoundedReceiverNullableChain0<BoundedReceiverWrap<T?>>} of {@code BoundedReceiverNullableChain1<T>} extending both.
 */
public interface JavaNullableChainPaths {

    interface Chain1<T> extends BoundedReceiverNullableChain0<BoundedReceiverWrap<T>> {}

    interface Chain2<T> extends BoundedReceiverNullableChain1<BoundedReceiverWrap<T>> {}

    interface Chain3<T> extends BoundedReceiverNullableChain2<BoundedReceiverWrap<T>> {}

    interface Chain4<T> extends BoundedReceiverNullableChain3<BoundedReceiverWrap<T>> {}

    interface Chain5<T> extends BoundedReceiverNullableChain4<BoundedReceiverWrap<T>> {}

    interface Chain6<T> extends BoundedReceiverNullableChain5<BoundedReceiverWrap<T>> {}
}
