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
 * The Java interfaces nesting the type arguments of {@code BoundedReceiverNestedPair} in a Java list at each level,
 * like {@code BoundedReceiverNestedPair<(Mutable)List<A!>!, (Mutable)List<B!>!>} of {@code Nesting1<A, B>},
 * each of these lists either the read-only one, or the mutable one, down to {@code Nesting18<A, B>}.
 */
public interface JavaNestedListPairs {

    interface Nesting0<A, B> extends BoundedReceiverNestedPair<A, B> {}

    interface Nesting1<A, B> extends Nesting0<List<A>, List<B>> {}

    interface Nesting2<A, B> extends Nesting1<List<A>, List<B>> {}

    interface Nesting3<A, B> extends Nesting2<List<A>, List<B>> {}

    interface Nesting4<A, B> extends Nesting3<List<A>, List<B>> {}

    interface Nesting5<A, B> extends Nesting4<List<A>, List<B>> {}

    interface Nesting6<A, B> extends Nesting5<List<A>, List<B>> {}

    interface Nesting7<A, B> extends Nesting6<List<A>, List<B>> {}

    interface Nesting8<A, B> extends Nesting7<List<A>, List<B>> {}

    interface Nesting9<A, B> extends Nesting8<List<A>, List<B>> {}

    interface Nesting10<A, B> extends Nesting9<List<A>, List<B>> {}

    interface Nesting11<A, B> extends Nesting10<List<A>, List<B>> {}

    interface Nesting12<A, B> extends Nesting11<List<A>, List<B>> {}

    interface Nesting13<A, B> extends Nesting12<List<A>, List<B>> {}

    interface Nesting14<A, B> extends Nesting13<List<A>, List<B>> {}

    interface Nesting15<A, B> extends Nesting14<List<A>, List<B>> {}

    interface Nesting16<A, B> extends Nesting15<List<A>, List<B>> {}

    interface Nesting17<A, B> extends Nesting16<List<A>, List<B>> {}

    interface Nesting18<A, B> extends Nesting17<List<A>, List<B>> {}

}
