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
 * The Java interfaces passing {@code String} to the generic Kotlin ones,
 * seen by Kotlin as {@code BoundedReceiverManyPaths0<List<String!>>}, and so on.
 */
public interface JavaManyListPaths {

    interface Strings0 extends BoundedReceiverListManyPaths0<String> {}

    interface Strings1 extends BoundedReceiverListManyPaths1<String> {}

    interface Strings2 extends BoundedReceiverListManyPaths2<String> {}

    interface Strings3 extends BoundedReceiverListManyPaths3<String> {}

    interface Strings4 extends BoundedReceiverListManyPaths4<String> {}

    interface Strings5 extends BoundedReceiverListManyPaths5<String> {}

    interface Strings6 extends BoundedReceiverListManyPaths6<String> {}

    interface Strings7 extends BoundedReceiverListManyPaths7<String> {}

    interface Strings8 extends BoundedReceiverListManyPaths8<String> {}
}
