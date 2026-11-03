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

import java.util.List;

/**
 * The Java interfaces passing a Java list of their type argument to the Kotlin ones,
 * like {@code BoundedReceiverDoubling0<(Mutable)List<T!>!>} of {@code Doubling1<T>},
 * next to {@code BoundedReceiverDoubling0<MutableList<T>>} of {@code BoundedReceiverDoubling1<T>} extending both,
 * doubling the supertypes reached through different paths at each level,
 * down to {@code DoublingRoot<T>}, reaching {@code BoundedReceiverDoublingRows<(Mutable)List<T!>!>} only through Java.
 */
public interface JavaDoublingPaths {

    interface DoublingRoot<T> extends BoundedReceiverDoublingRows<List<T>> {}

    interface Doubling1<T> extends BoundedReceiverDoubling0<List<T>> {}

    interface Doubling2<T> extends BoundedReceiverDoubling1<List<T>> {}

    interface Doubling3<T> extends BoundedReceiverDoubling2<List<T>> {}

    interface Doubling4<T> extends BoundedReceiverDoubling3<List<T>> {}

    interface Doubling5<T> extends BoundedReceiverDoubling4<List<T>> {}

    interface Doubling6<T> extends BoundedReceiverDoubling5<List<T>> {}

    interface Doubling7<T> extends BoundedReceiverDoubling6<List<T>> {}

    interface Doubling8<T> extends BoundedReceiverDoubling7<List<T>> {}

    interface Doubling9<T> extends BoundedReceiverDoubling8<List<T>> {}

    interface Doubling10<T> extends BoundedReceiverDoubling9<List<T>> {}

    interface Doubling11<T> extends BoundedReceiverDoubling10<List<T>> {}

    interface Doubling12<T> extends BoundedReceiverDoubling11<List<T>> {}

    interface Doubling13<T> extends BoundedReceiverDoubling12<List<T>> {}

    interface Doubling14<T> extends BoundedReceiverDoubling13<List<T>> {}

    interface Doubling15<T> extends BoundedReceiverDoubling14<List<T>> {}

    interface Doubling16<T> extends BoundedReceiverDoubling15<List<T>> {}
}
