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
import kotlin.metadata.ClassKind
import kotlin.metadata.Visibility
import kotlin.test.Test

class ApiClassesTest {

    private fun scope(
        visibility: Visibility = Visibility.PUBLIC,
        isCompanion: Boolean = false,
        isUsable: Boolean = true,
        isSubclassable: Boolean = false,
        sealedSubclasses: List<String> = emptyList()
    ) = ClassScope(
        visibility,
        if (isCompanion) ClassKind.COMPANION_OBJECT else ClassKind.CLASS,
        isUsable,
        isSubclassable,
        sealedSubclasses
    )

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
    fun `should tell class of the API by its own scope when it is not found by its metadata name`() {
        // given
        // like a class relocated by shading, found by its binary name, while its metadata name is the original one
        val apiClasses = apiClasses(mapOf("pkg/Outer" to scope(Visibility.PRIVATE)))

        // when
        val byName = apiClasses.apiClass("pkg/Relocated")
        val byScope = apiClasses.apiClass("pkg/Relocated", scope())
        val nestedInPrivate = apiClasses.apiClass("pkg/Outer.Relocated", scope())

        // then
        assert(byName == null)
        assert(byScope?.isApi(Visibility.PUBLIC) == true)
        // still not part of the API within a class which is not
        assert(nestedInPrivate == null)
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
    fun `should keep openness of sealed class decided while deciding the one of its sealed superclass`() {
        // given
        val requested = mutableListOf<String>()
        val apiClasses = apiClasses(
            mapOf(
                "pkg/Sealed" to scope(sealedSubclasses = listOf("pkg/SealedSub")),
                "pkg/SealedSub" to scope(sealedSubclasses = listOf("pkg/Open")),
                "pkg/Open" to scope(isSubclassable = true)
            ),
            requested
        )
        val isSealedOpen = apiClasses.apiClass("pkg/Sealed")?.isOpen
        requested.clear()

        // when
        val isSealedSubOpen = apiClasses.apiClass("pkg/SealedSub")?.isOpen

        // then
        // being open is exact, also when decided assuming its sealed superclass not to be open meanwhile
        assert(isSealedOpen == true && isSealedSubOpen == true)
        assert(requested == listOf("pkg/SealedSub"))
    }

    @Test
    fun `should derive nested class of the API from its outer class`() {
        // given
        val requested = mutableListOf<String>()
        val apiClasses = apiClasses(
            mapOf(
                "pkg/Outer" to scope(isSubclassable = true),
                "pkg/Outer.Companion" to scope(isCompanion = true),
                "pkg/Outer.Private" to scope(Visibility.PRIVATE)
            ),
            requested
        )
        val outer = apiClasses.apiClass("pkg/Outer")!!
        requested.clear()

        // when
        val companion = apiClasses.nestedApiClass(outer, "pkg/Outer.Companion")
        val private = apiClasses.nestedApiClass(outer, "pkg/Outer.Private")

        // then
        // the protected members of the companion object of an open class are part of the API
        assert(companion?.isApi(Visibility.PROTECTED) == true)
        assert(private == null)
        assert(requested == listOf("pkg/Outer.Companion", "pkg/Outer.Private"))
    }

    @Test
    fun `should decide whether sealed class is open again after a transient failure`() {
        // given
        val transientFailures = TransientFailures()
        var isSubclassRead = false
        val apiClasses = ApiClasses(
            scopeOf = { name ->
                when (name) {
                    "pkg/Sealed" -> scope(sealedSubclasses = listOf("pkg/Open"))
                    // the subclass cannot be read the first time, maybe only for now
                    "pkg/Open" -> if (isSubclassRead) scope(isSubclassable = true) else null.also { transientFailures.mark() }
                    else -> null
                }
            },
            isAbsent = { false },
            transientFailures = transientFailures
        )

        // when
        val isProtectedApiBefore = apiClasses.apiClass("pkg/Sealed")?.isApi(Visibility.PROTECTED)
        isSubclassRead = true
        val isProtectedApiAfter = apiClasses.apiClass("pkg/Sealed")?.isApi(Visibility.PROTECTED)

        // then
        assert(isProtectedApiBefore == false && isProtectedApiAfter == true)
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
