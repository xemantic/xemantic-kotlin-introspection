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
import kotlin.metadata.Visibility
import kotlin.test.Test

class ApiClassesTest {

    private fun scope(
        visibility: Visibility = Visibility.PUBLIC,
        isCompanion: Boolean = false,
        isUsable: Boolean = true,
        isSubclassable: Boolean = false,
        sealedSubclasses: List<String> = emptyList()
    ) = ClassScope(visibility, isCompanion, isUsable, isSubclassable, sealedSubclasses)

    /**
     * The API classes of the [classes] found by their names, where a `null` scope tells a class
     * whose class file is present, but cannot be read, like without the Kotlin metadata,
     * while the classes requested for their scopes are recorded in the [requested] ones.
     */
    private fun apiClasses(
        classes: Map<String, ClassScope?>,
        requested: MutableList<String> = mutableListOf()
    ) = ApiClasses(
        scopeOf = { name -> requested += name; classes[name] },
        isAbsent = { name -> name !in classes }
    )

    @Test
    fun `should not tell class nested in class which cannot be read as part of the API`() {
        // given
        val apiClasses = apiClasses(
            mapOf(
                "pkg/Unreadable" to null,
                "pkg/Unreadable.Nested" to scope()
            )
        )

        // when
        val nested = apiClasses.apiClass("pkg/Unreadable.Nested")

        // then
        assert(nested == null)
    }

    @Test
    fun `should not decide whether sealed class is open until its protected members are queried`() {
        // given
        val requested = mutableListOf<String>()
        val apiClasses = apiClasses(
            mapOf(
                "pkg/Sealed" to scope(sealedSubclasses = listOf("pkg/Open")),
                "pkg/Open" to scope(isSubclassable = true)
            ),
            requested
        )

        // when
        val sealed = apiClasses.apiClass("pkg/Sealed")
        val isPublicApi = sealed?.isApi(Visibility.PUBLIC)
        val requestedBefore = requested.toList()
        val isProtectedApi = sealed?.isApi(Visibility.PROTECTED)

        // then
        assert(isPublicApi == true && requestedBefore == listOf("pkg/Sealed"))
        assert(isProtectedApi == true && "pkg/Open" in requested)
    }

    @Test
    fun `should not tell class nested in unusable class as part of the API`() {
        // given
        val apiClasses = apiClasses(
            mapOf(
                "pkg/Hidden" to scope(isUsable = false),
                "pkg/Hidden.Nested" to scope()
            )
        )

        // when
        val nested = apiClasses.apiClass("pkg/Hidden.Nested")

        // then
        assert(nested == null)
    }

    @Test
    fun `should tell class nested in class which is not found as part of the API`() {
        // given
        val apiClasses = apiClasses(mapOf("pkg/Missing.Nested" to scope()))

        // when
        val nested = apiClasses.apiClass("pkg/Missing.Nested")

        // then
        assert(nested != null && nested.isApi(Visibility.PUBLIC) && !nested.isApi(Visibility.PROTECTED))
    }

    @Test
    fun `should not tell class which is not found as part of the API`() {
        // given
        val apiClasses = apiClasses(emptyMap())

        // when
        val missing = apiClasses.apiClass("pkg/Missing")

        // then
        assert(missing == null)
    }

    @Test
    fun `should tell protected class nested in sealed class open through its own protected subclass as not part of the API`() {
        // given
        // the only open subclass of the sealed class is the protected one nested in it, which is not part of the API
        // unless the sealed class is open, which it would be only through that subclass
        val apiClasses = apiClasses(
            mapOf(
                "pkg/Sealed" to scope(sealedSubclasses = listOf("pkg/Sealed.Sub")),
                "pkg/Sealed.Sub" to scope(visibility = Visibility.PROTECTED, isSubclassable = true)
            )
        )

        // when
        val sealed = apiClasses.apiClass("pkg/Sealed")
        val sub = apiClasses.apiClass("pkg/Sealed.Sub")

        // then
        assert(sealed != null && !sealed.isApi(Visibility.PROTECTED))
        assert(sub == null)
    }

    @Test
    fun `should tell protected class nested in sealed class open through another subclass as part of the API`() {
        // given
        val apiClasses = apiClasses(
            mapOf(
                "pkg/Sealed" to scope(sealedSubclasses = listOf("pkg/Sealed.Protected", "pkg/Open")),
                "pkg/Sealed.Protected" to scope(visibility = Visibility.PROTECTED, isSubclassable = true),
                "pkg/Open" to scope(isSubclassable = true)
            )
        )

        // when
        val protected = apiClasses.apiClass("pkg/Sealed.Protected")

        // then
        assert(protected != null && protected.isApi(Visibility.PROTECTED))
    }

    @Test
    fun `should tell constructor of sealed class open through its subclass as not part of the API`() {
        // given
        val apiClasses = apiClasses(
            mapOf(
                "pkg/Sealed" to scope(sealedSubclasses = listOf("pkg/Open")),
                "pkg/Open" to scope(isSubclassable = true)
            )
        )

        // when
        val sealed = apiClasses.apiClass("pkg/Sealed")

        // then
        assert(sealed != null && sealed.isApi(Visibility.PROTECTED) && !sealed.isConstructorApi(Visibility.PROTECTED))
    }

    @Test
    fun `should tell protected members of companion object of open class as part of the API`() {
        // given
        val apiClasses = apiClasses(
            mapOf(
                "pkg/Open" to scope(isSubclassable = true),
                "pkg/Open.Companion" to scope(isCompanion = true)
            )
        )

        // when
        val companion = apiClasses.apiClass("pkg/Open.Companion")

        // then
        assert(companion != null && companion.isApi(Visibility.PROTECTED))
    }

    @Test
    fun `should tell top-level declarations which are part of the API`() {
        // when
        val api = Visibility.entries.filter { TopLevel.isApi(it) }

        // then
        assert(api == listOf(Visibility.PUBLIC))
    }

}
