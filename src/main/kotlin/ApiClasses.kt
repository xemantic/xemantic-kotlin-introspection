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

import kotlin.metadata.ClassKind
import kotlin.metadata.Visibility

/**
 * What a class tells by itself about whether it, and the declarations within it, are part of the API,
 * see [ApiClasses].
 *
 * @param kind the kind of the class, telling, like for a protected one, how it is declared.
 * @param isUsable whether the class can be used in the source code at all, regardless of its visibility,
 *   unlike a synthetic class, a class deprecated with the `HIDDEN` level, or a generated serializer.
 * @param isSubclassable whether this class can be subclassed outside of its own body, and so, maybe indirectly,
 *   by the user code: an open or abstract class with a constructor other than a private one, which is not hidden.
 *   An internal constructor does not close the class, as a subclass in its own module can be public and open,
 *   while a private one does, unless the class has other constructors.
 * @param sealedSubclasses the located names, see [locatedNameOf], of the direct subclasses of a sealed class, or a sealed interface,
 *   which might make it open to the user code indirectly, see [ApiScope.isOpen].
 */
internal class ClassScope(
    val visibility: Visibility,
    val kind: ClassKind,
    val isUsable: Boolean,
    val isSubclassable: Boolean,
    val sealedSubclasses: List<String>
) {
    val isCompanion: Boolean get() = kind == ClassKind.COMPANION_OBJECT
}

/**
 * A class enclosing a declaration of the API, which is itself part of the API.
 *
 * @param isOpen whether the user code can declare a subclass of this class, maybe indirectly,
 *   and so use its protected members, and override its open members: told by [ClassScope.isSubclassable],
 *   or, for a sealed class, or a sealed interface, by an open subclass which is part of the API,
 *   like `open class A : S()` of `sealed class S`,
 *   decided only once asked, as it is needed only for the protected members, and for the modifiers of the open ones.
 *   An open class with only private constructors is not open,
 *   even when it is subclassed by an open class nested in it, which the metadata does not tell.
 * @param isSubclassable see [ClassScope.isSubclassable].
 * @param kind see [ClassScope.kind].
 */
internal class ApiScope(
    val visibility: Visibility,
    isOpen: Lazy<Boolean>,
    val isSubclassable: Boolean,
    val kind: ClassKind
) {

    val isOpen: Boolean by isOpen

    val isCompanion: Boolean get() = kind == ClassKind.COMPANION_OBJECT

}

/**
 * Where a declaration is declared, telling whether it is part of the API by its visibility, see [isApi].
 */
internal sealed interface DeclarationScope {

    /**
     * Whether a declaration of this [visibility] declared in this scope is part of the API,
     * the one which can be used from the user code, and therefore listed, resolved, and found.
     * Private and internal declarations are not, neither are the `@PublishedApi internal` ones,
     * callable only from public inline functions.
     */
    fun isApi(visibility: Visibility): Boolean

}

/**
 * The scope of the top-level declarations, of which only the public ones are part of the API.
 */
internal object TopLevel : DeclarationScope {

    override fun isApi(visibility: Visibility): Boolean = visibility == Visibility.PUBLIC

}

/**
 * A class of the API, see [ApiClasses], with the [scopes] of itself and its enclosing classes, from the outermost one.
 */
internal class ApiClass(val scopes: List<ApiScope>) : DeclarationScope {

    private val owner: ApiScope get() = scopes.last()

    /**
     * Whether the class is open to the user code, see [ApiScope.isOpen].
     */
    val isOpen: Boolean get() = owner.isOpen

    /**
     * Protected members are part of the API, but only of an open class, or of the companion object of an open class,
     * as no subclass using them can be declared otherwise.
     */
    override fun isApi(visibility: Visibility): Boolean {
        // the protected members of a companion object are accessible to the subclasses of its outer class
        val subclassed = if (owner.isCompanion) scopes.getOrNull(scopes.lastIndex - 1) else owner
        return visibility == Visibility.PUBLIC || (visibility == Visibility.PROTECTED && subclassed?.isOpen == true)
    }

    /**
     * Whether a constructor of this [visibility] can be called from the user code:
     * a protected one only by a direct subclass of an open or abstract class,
     * which is why the protected constructor of a sealed class cannot,
     * even when the sealed class is open, see [ApiScope.isOpen].
     */
    fun isConstructorApi(visibility: Visibility): Boolean =
        visibility == Visibility.PUBLIC || (visibility == Visibility.PROTECTED && owner.isSubclassable)

    /**
     * The scope of the class nested in this one, with its own [scope].
     */
    operator fun plus(scope: ApiScope): ApiClass = ApiClass(scopes + scope)

}

/**
 * The one place deciding which classes are part of the API, and so the declarations within them,
 * for listing, resolving, searching, and indexing alike.
 * A class is part of the API when it is [usable][ClassScope.isUsable], and its visibility is part of the API
 * in its enclosing class, see [DeclarationScope.isApi], which has to be part of the API as well,
 * so that nothing declared within a class which is not part of the API is.
 * An enclosing class whose class file is absent from the classpath is assumed to be a public one,
 * as nothing tells otherwise, unlike the one whose class file cannot be read, like without the Kotlin metadata,
 * which might be private, while the class itself has to be found.
 *
 * @param scopeOf the scope of a class by its metadata name, like `com/example/Outer.Nested`,
 *   or `null` if it is not found, or cannot be read.
 * @param isAbsent whether the class file of a class, by its metadata name, is absent from the classpath.
 * @param transientFailures the failures which might be transient, of [scopeOf] and [isAbsent],
 *   so that the openness of a sealed class decided meanwhile is not kept, see [isOpen].
 */
internal class ApiClasses(
    private val scopeOf: (String) -> ClassScope?,
    private val isAbsent: (String) -> Boolean,
    private val transientFailures: TransientFailures? = null
) {

    // whether a sealed class is open, by its metadata name, see ApiScope.isOpen
    private val sealedOpenness = BoundedCache<String, Boolean>(capacity = 1024)

    /**
     * The class named like `com/example/Outer.Nested`, if it is part of the API, or else `null`.
     */
    fun apiClass(name: String): ApiClass? = apiClass(name, inProgress = emptySet())

    /**
     * The class named like `com/example/Outer.Nested` in its metadata, whose own [scope] is already known,
     * like the one of a class found by its binary name, which might not be the one of its metadata name,
     * like after relocating the class by shading, which does not rewrite its metadata.
     */
    fun apiClass(name: String, scope: ClassScope): ApiClass? = apiClass(name, inProgress = emptySet(), scope)

    /**
     * The class named like `com/example/Outer.Nested` nested in the [outer] class, which is its enclosing class,
     * derived from it, without deciding again whether the classes enclosing it are part of the API.
     */
    fun nestedApiClass(outer: ApiClass, name: String): ApiClass? =
        enclosed(outer, name, scopeOf(name) ?: return null, inProgress = emptySet())

    /**
     * @param inProgress the sealed classes whose openness is being decided, assumed not to be open meanwhile,
     *   as a class nested in a sealed class, like its subclass, might be part of the API only if the sealed class is open.
     * @param scope the scope of the class itself, if already known.
     */
    private fun apiClass(name: String, inProgress: Set<String>, scope: ClassScope? = null): ApiClass? {
        // local and anonymous classes have names starting with '.'
        if (name.startsWith('.')) return null
        var apiClass: ApiClass? = null
        for (className in enclosingClassNames(name)) {
            val enclosingScope = scopeOf(className) ?: if (isAbsent(className)) ABSENT_ENCLOSING_CLASS else return null
            apiClass = enclosed(apiClass, className, enclosingScope, inProgress) ?: return null
        }
        return enclosed(apiClass, name, scope ?: scopeOf(name) ?: return null, inProgress)
    }

    /**
     * The class named [name], of the [scope], enclosed by the [outer] class, or a top-level one, if `null`,
     * if it is part of the API, or else `null`.
     */
    private fun enclosed(outer: ApiClass?, name: String, scope: ClassScope, inProgress: Set<String>): ApiClass? {
        if (!scope.isUsable || !(outer ?: TopLevel).isApi(scope.visibility)) return null
        val apiScope = ApiScope(
            visibility = scope.visibility,
            isOpen = lazy { isOpen(name, scope, inProgress) },
            isSubclassable = scope.isSubclassable,
            kind = scope.kind
        )
        return outer?.plus(apiScope) ?: ApiClass(listOf(apiScope))
    }

    /**
     * Whether the class is open, see [ApiScope.isOpen], where a sealed class is, when any of its subclasses
     * is part of the API, and open.
     * Only the openness decided without assuming any sealed class not to be open is kept,
     * or else decided to be open anyway, which is exact either way,
     * as such an assumption matters only when the sealed class is not open otherwise,
     * and not after a transient failure meanwhile, see [transientFailures].
     */
    private fun isOpen(name: String, scope: ClassScope, inProgress: Set<String>): Boolean {
        if (scope.isSubclassable) return true
        if (scope.sealedSubclasses.isEmpty()) return false
        sealedOpenness.getIfCached(name)?.let { return it }
        if (name in inProgress) return false
        val failureCount = transientFailures?.count()
        val isOpen = scope.sealedSubclasses.any { subclass -> apiClass(subclass, inProgress + name)?.isOpen == true }
        if ((inProgress.isEmpty() || isOpen) && transientFailures?.count() == failureCount) sealedOpenness.put(name, isOpen)
        return isOpen
    }

    private companion object {

        val ABSENT_ENCLOSING_CLASS = ClassScope(
            visibility = Visibility.PUBLIC,
            // never protected, as it is assumed to be public
            kind = ClassKind.CLASS,
            isUsable = true,
            isSubclassable = false,
            sealedSubclasses = emptyList()
        )

    }

}

/**
 * The metadata names of the classes enclosing the class named like `com/example/Outer.Middle.Nested`,
 * which are `com/example/Outer` and `com/example/Outer.Middle`.
 */
internal fun enclosingClassNames(name: String): List<String> {
    val packagePrefix = name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    val parts = name.substringAfterLast('/').split('.')
    return (1 until parts.size).map { packagePrefix + parts.take(it).joinToString(".") }
}
