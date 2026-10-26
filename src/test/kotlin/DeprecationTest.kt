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

package com.xemantic.kotlin.introspection

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

class DeprecationTest {

    // whether the version is not newer than 2.1, the one the user code is compiled with
    private val isSince: (String) -> Boolean = { version ->
        val (major, minor) = version.split('.').map { it.toInt() }
        KotlinVersion(major, minor) <= KotlinVersion(2, 1)
    }

    @Test
    fun `should not deprecate declaration without annotations`() {
        // given
        val annotations = DeprecationAnnotations()

        // when
        val deprecation = annotations.deprecationOf(isSince)

        // then
        assert(deprecation == null)
    }

    @Test
    fun `should deprecate with WARNING level by default`() {
        // given
        val annotations = DeprecationAnnotations().apply {
            isDeprecated = true
            message = "Use bar()"
            replaceWith = "bar()"
        }

        // when
        val deprecation = annotations.deprecationOf(isSince)

        // then
        assert(deprecation?.level == DeprecationLevel.WARNING)
        assert(deprecation?.message == "Use bar()")
        assert(deprecation?.replaceWith == "bar()")
    }

    @Test
    fun `should deprecate with explicit level`() {
        // given
        val annotations = DeprecationAnnotations().apply {
            isDeprecated = true
            level = "ERROR"
            replaceWith = ""
        }

        // when
        val deprecation = annotations.deprecationOf(isSince)

        // then
        assert(deprecation?.level == DeprecationLevel.ERROR)
        assert(deprecation?.replaceWith == null)
    }

    @Test
    fun `should deprecate with the highest level reached since Kotlin version`() {
        // given
        val annotations = DeprecationAnnotations().apply {
            isDeprecated = true
            isDeprecatedSinceKotlin = true
            warningSince = "1.5"
            errorSince = "2.1"
            hiddenSince = "2.2"
        }

        // when
        val deprecation = annotations.deprecationOf(isSince)

        // then
        assert(deprecation?.level == DeprecationLevel.ERROR)
    }

    @Test
    fun `should hide declaration hidden since older Kotlin version`() {
        // given
        val annotations = DeprecationAnnotations().apply {
            isDeprecated = true
            isDeprecatedSinceKotlin = true
            warningSince = "1.3"
            hiddenSince = "1.6"
        }

        // when
        val deprecation = annotations.deprecationOf(isSince)

        // then
        assert(deprecation?.isHidden == true)
    }

    @Test
    fun `should not deprecate declaration before any Kotlin version it is deprecated since`() {
        // given
        val annotations = DeprecationAnnotations().apply {
            isDeprecated = true
            isDeprecatedSinceKotlin = true
            warningSince = "2.2"
            errorSince = "2.3"
        }

        // when
        val deprecation = annotations.deprecationOf(isSince)

        // then
        assert(deprecation == null)
    }

    @Test
    fun `should not deprecate declaration before Kotlin version despite explicit default level`() {
        // given
        val annotations = DeprecationAnnotations().apply {
            isDeprecated = true
            // like the default level kotlin-reflect tells for a built-in declaration
            level = "WARNING"
            isDeprecatedSinceKotlin = true
            hiddenSince = "2.2"
        }

        // when
        val deprecation = annotations.deprecationOf(isSince)

        // then
        assert(deprecation == null)
    }


    @Test
    fun `should not hide var whose getter only is hidden`() {
        // given
        val getterDeprecation = Deprecation("replaced", replaceWith = null, DeprecationLevel.HIDDEN)

        // when
        val deprecation = varDeprecationOf(propertyDeprecation = null, getterDeprecation, setterDeprecation = null)

        // then
        assert(deprecation != null && !deprecation.isHidden && deprecation.hidesGetter)
        assert(deprecation.target == Deprecation.Target.GETTER)
    }

    @Test
    fun `should keep deprecation of var next to its hidden getter`() {
        // given
        val propertyDeprecation = Deprecation("deprecated", replaceWith = null, DeprecationLevel.WARNING)
        val getterDeprecation = Deprecation("replaced", replaceWith = null, DeprecationLevel.HIDDEN)

        // when
        val deprecation = varDeprecationOf(propertyDeprecation, getterDeprecation, setterDeprecation = null)

        // then
        assert(deprecation != null && !deprecation.isHidden && deprecation.hidesGetter)
        assert(deprecation.message == "deprecated" && deprecation.target == Deprecation.Target.DECLARATION)
        assert(deprecation.accessors.map { it.message to it.target } == listOf("replaced" to Deprecation.Target.GETTER))
    }

    @Test
    fun `should hide var which is hidden itself regardless of its getter`() {
        // given
        val propertyDeprecation = Deprecation("hidden", replaceWith = null, DeprecationLevel.HIDDEN)
        val getterDeprecation = Deprecation("replaced", replaceWith = null, DeprecationLevel.HIDDEN)

        // when
        val deprecation = varDeprecationOf(propertyDeprecation, getterDeprecation, setterDeprecation = null)

        // then
        assert(deprecation?.isHidden == true && deprecation.accessors.isEmpty())
    }

    @Test
    fun `should deprecate only getter of var with level other than hidden`() {
        // given
        val getterDeprecation = Deprecation("read y", replaceWith = null, DeprecationLevel.ERROR)

        // when
        val deprecation = varDeprecationOf(propertyDeprecation = null, getterDeprecation, setterDeprecation = null)

        // then
        assert(deprecation != null && !deprecation.isHidden && !deprecation.hidesGetter)
        assert(deprecation.target == Deprecation.Target.GETTER && deprecation.level == DeprecationLevel.ERROR)
    }

    @Test
    fun `should deprecate only setter of var`() {
        // given
        val setterDeprecation = Deprecation("assign y", replaceWith = null, DeprecationLevel.ERROR)

        // when
        val deprecation = varDeprecationOf(propertyDeprecation = null, getterDeprecation = null, setterDeprecation)

        // then
        assert(deprecation != null && !deprecation.isHidden && !deprecation.hidesSetter)
        assert(deprecation.target == Deprecation.Target.SETTER)
        // reading the var is not deprecated
        assert(deprecation.ofReading() == null)
    }

    @Test
    fun `should keep deprecations of var and both its accessors`() {
        // given
        val propertyDeprecation = Deprecation("use y", replaceWith = null, DeprecationLevel.WARNING)
        val getterDeprecation = Deprecation("read y", replaceWith = null, DeprecationLevel.HIDDEN)
        val setterDeprecation = Deprecation("assign y", replaceWith = null, DeprecationLevel.ERROR)

        // when
        val deprecation = varDeprecationOf(propertyDeprecation, getterDeprecation, setterDeprecation)

        // then
        assert(deprecation != null && !deprecation.isHidden && deprecation.hidesGetter && !deprecation.hidesSetter)
        assert(deprecation.message == "use y" && deprecation.target == Deprecation.Target.DECLARATION)
        assert(
            deprecation.accessors.map { it.message to it.target } ==
                listOf("read y" to Deprecation.Target.GETTER, "assign y" to Deprecation.Target.SETTER)
        )
    }

    @Test
    fun `should tell hidden setter of var`() {
        // given
        val setterDeprecation = Deprecation("replaced", replaceWith = null, DeprecationLevel.HIDDEN)

        // when
        val deprecation = varDeprecationOf(propertyDeprecation = null, getterDeprecation = null, setterDeprecation)

        // then
        assert(deprecation != null && !deprecation.isHidden && deprecation.hidesSetter)
    }

    @Test
    fun `should deprecate reading of var by its own and getter deprecations`() {
        // given
        val deprecation = varDeprecationOf(
            propertyDeprecation = Deprecation("use z", replaceWith = "z", DeprecationLevel.WARNING),
            getterDeprecation = Deprecation("read y", replaceWith = null, DeprecationLevel.ERROR),
            setterDeprecation = Deprecation("assign y", replaceWith = null, DeprecationLevel.ERROR)
        )

        // when
        val reading = deprecation?.ofReading()

        // then
        assert(reading != null && reading.message == "use z" && reading.replaceWith == "z")
        assert(reading.target == Deprecation.Target.DECLARATION && reading.level == DeprecationLevel.WARNING)
        assert(
            reading.accessors.map { Triple(it.message, it.level, it.target) } ==
                listOf(Triple("read y", DeprecationLevel.ERROR, Deprecation.Target.GETTER))
        )
    }

    @Test
    fun `should deprecate reading of var by its getter deprecation alone as declaration`() {
        // given
        val deprecation = varDeprecationOf(
            propertyDeprecation = null,
            getterDeprecation = Deprecation("read y", replaceWith = null, DeprecationLevel.ERROR),
            setterDeprecation = Deprecation("assign y", replaceWith = null, DeprecationLevel.WARNING)
        )

        // when
        val reading = deprecation?.ofReading()

        // then
        assert(reading != null && reading.message == "read y" && reading.level == DeprecationLevel.ERROR)
        assert(reading.target == Deprecation.Target.DECLARATION && reading.accessors.isEmpty())
    }

    @Test
    fun `should deprecate receiver of extension which is not deprecated itself`() {
        // given
        val receiverDeprecation = Deprecation("use constructor", replaceWith = null, DeprecationLevel.ERROR)

        // when
        val deprecation = extensionDeprecationOf(deprecation = null, receiverDeprecation)

        // then
        assert(deprecation != null && deprecation.message == "use constructor" && deprecation.level == DeprecationLevel.ERROR)
        assert(deprecation.target == Deprecation.Target.RECEIVER && deprecation.accessors.isEmpty() && !deprecation.isHidden)
    }

    @Test
    fun `should keep deprecations of extension and its receiver`() {
        // given
        val ownDeprecation = Deprecation("use of()", replaceWith = "of()", DeprecationLevel.WARNING)
        val receiverDeprecation = Deprecation("use constructor", replaceWith = null, DeprecationLevel.ERROR)

        // when
        val deprecation = extensionDeprecationOf(ownDeprecation, receiverDeprecation)

        // then
        assert(deprecation != null && deprecation.message == "use of()" && deprecation.target == Deprecation.Target.DECLARATION)
        assert(
            deprecation.accessors.map { Triple(it.message, it.level, it.target) } ==
                listOf(Triple("use constructor", DeprecationLevel.ERROR, Deprecation.Target.RECEIVER))
        )
    }

    @Test
    fun `should keep deprecation of receiver of val extension when reading it`() {
        // given
        val deprecation = extensionDeprecationOf(
            deprecation = null,
            receiverDeprecation = Deprecation("use constructor", replaceWith = null, DeprecationLevel.WARNING)
        )

        // when
        val reading = deprecation?.ofReading()

        // then
        assert(reading != null && reading.message == "use constructor" && reading.target == Deprecation.Target.RECEIVER)
    }

    @Test
    fun `should keep deprecation of receiver of var extension next to the one of its getter when reading it`() {
        // given
        val deprecation = extensionDeprecationOf(
            deprecation = varDeprecationOf(
                propertyDeprecation = null,
                getterDeprecation = Deprecation("read y", replaceWith = null, DeprecationLevel.ERROR),
                setterDeprecation = Deprecation("assign y", replaceWith = null, DeprecationLevel.WARNING)
            ),
            receiverDeprecation = Deprecation("use constructor", replaceWith = null, DeprecationLevel.WARNING)
        )

        // when
        val reading = deprecation?.ofReading()

        // then
        assert(reading != null && reading.message == "read y" && reading.target == Deprecation.Target.DECLARATION)
        assert(
            reading.accessors.map { Triple(it.message, it.level, it.target) } ==
                listOf(Triple("use constructor", DeprecationLevel.WARNING, Deprecation.Target.RECEIVER))
        )
    }

    @Test
    fun `should hide reading of var whose getter is hidden`() {
        // given
        val deprecation = varDeprecationOf(
            propertyDeprecation = Deprecation("use z", replaceWith = null, DeprecationLevel.WARNING),
            getterDeprecation = Deprecation("read y", replaceWith = null, DeprecationLevel.HIDDEN),
            setterDeprecation = null
        )

        // when
        val reading = deprecation?.ofReading()

        // then
        assert(reading != null && reading.isHidden)
    }

}
