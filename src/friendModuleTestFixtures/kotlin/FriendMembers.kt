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

package com.xemantic.kotlin.introspection.friendmodule

import com.xemantic.kotlin.introspection.othermodule.InternalBase

// The internal members of a class of a friend module, like of the main module seen from the test one, can be overridden
class FriendModuleSubclass : InternalBase() {
    override fun reset() {}
    public override val state: Int get() = 1
}

class UnreflectableFriendModuleSubclass : InternalBase() {
    public override val state: Int get() = 1
    fun take(marker: com.xemantic.kotlin.introspection.othermodule.OtherModuleMarker) {}
}

class FriendModuleDefaultsSubclass : com.xemantic.kotlin.introspection.othermodule.InternalDefaultsBase() {
    public override fun find(limit: Int) {}
}

// A public override of a @PublishedApi internal member, whose JVM method is not mangled
class FriendModulePublishedApiSubclass : com.xemantic.kotlin.introspection.othermodule.PublishedApiBase() {
    public override fun publish() {}
}

open class FriendModuleOpenPublishedApiSubclass : com.xemantic.kotlin.introspection.othermodule.PublishedApiBase() {
    public override fun publish() {}
}

open class UnreflectableOpenFriendModuleSubclass : InternalBase() {
    public override val state: Int get() = 1
    fun take(marker: com.xemantic.kotlin.introspection.othermodule.OtherModuleMarker) {}
}
