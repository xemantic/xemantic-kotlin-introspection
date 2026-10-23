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

package com.xemantic.kotlin.introspection.othermodule

// The internal members are overridable only within this module
open class InternalBase {
    internal open fun reset() {}
    internal open val state: Int get() = 0
    open fun keep() {}
}

class SameModuleSubclass : InternalBase() {
    override fun reset() {}
    public override val state: Int get() = 1
}

// A @PublishedApi internal member is not mangled, unlike other internal members
open class PublishedApiBase {
    @PublishedApi
    internal open fun publish() {}
}
