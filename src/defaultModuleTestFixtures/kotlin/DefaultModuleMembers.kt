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

package com.xemantic.kotlin.introspection.defaultmodule

// The internal members are overridable only within this module, whose default name is not in the metadata
open class DefaultModuleBase {
    internal open fun reset() {}
    internal open val state: Int get() = 0
    internal open fun clear() {}
}

class DefaultModuleSubclass : DefaultModuleBase() {
    override fun reset() {}
    public override val state: Int get() = 1
    public override fun clear() {}
}

// A @PublishedApi internal member is not mangled, so it is told to be overridden within the same module only by its name
open class DefaultModulePublishedApiBase {
    @PublishedApi
    internal open fun publish(x: Int = 0) {}
}

class DefaultModulePublishedApiSubclass : DefaultModulePublishedApiBase() {
    public override fun publish(x: Int) {}
}
