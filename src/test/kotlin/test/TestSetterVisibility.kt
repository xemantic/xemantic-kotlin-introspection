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

package com.xemantic.kotlin.introspection.test

// The setters which cannot be called from the user code, so that the properties cannot be assigned there
class NarrowedSetterHolder {
    var privateSetter: Int = 0
        private set
    var internalSetter: Int = 0
        internal set
    // the protected members of a final class cannot be used by any subclass
    @Suppress("PROTECTED_IN_FINAL_CLASS")
    var protectedSetter: Int = 0
        protected set
    var publicSetter: Int = 0
}

// The protected setter can be called from a subclass
open class OpenNarrowedSetterHolder {
    var privateSetter: Int = 0
        private set
    var protectedSetter: Int = 0
        protected set
    protected var protectedPropertyPrivateSetter: Int = 0
        private set
    protected var protectedProperty: Int = 0
    companion object {
        var companionProtectedSetter: Int = 0
            protected set
    }
}

// a property and a function of the same name, found by a member lookup paged by declarations
open class PagedSetterHolder {
    var value: Int = 0
        protected set
    fun value(@Suppress("unused") next: Int) {}
}

var NarrowedSetterHolder.extensionPrivateSetter: Int
    get() = privateSetter
    private set(@Suppress("unused") value) {}

var topLevelPrivateSetter: Int = 0
    private set

object NarrowedSetterExtensions {
    var NarrowedSetterHolder.objectExtensionPrivateSetter: Int
        get() = privateSetter
        private set(@Suppress("unused") value) {}
}

open class ProtectedSetterBase {
    open var overridden: Int = 0
        protected set
}

// a val cannot override a var, so the override stays a var, even when its setter cannot be called
class ProtectedSetterOverride : ProtectedSetterBase() {
    override var overridden: Int = 1
}

class ProtectedSetterConstructorOverride(override var overridden: Int) : ProtectedSetterBase()
