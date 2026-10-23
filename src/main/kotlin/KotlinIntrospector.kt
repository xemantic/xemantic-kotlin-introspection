/*
 * xemantic-kotlin-introspection - Kotlin API introspection of the classpath, rendering LLM-friendly signatures
 * Copyright (C) 2025-2026  Kazimierz Pogoda / Xemantic
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

/**
 * Provides introspection capabilities for Kotlin APIs available on the classpath.
 * Use [resolve] to get detailed signatures for known types (e.g., extension functions of String).
 * Use [search] to discover APIs when the exact name is unknown.
 *
 * The introspector created by the [KotlinIntrospector] function is safe for concurrent use.
 */
public interface KotlinIntrospector {

    /**
     * Resolves metadata for a Kotlin declaration by its fully qualified name.
     *
     * The result is paginated by items, and every page can be read on its own,
     * as the headers the items are listed under, like the `// from` header of the package
     * to import the extensions from, are repeated on every page.
     *
     * A class is paginated by its enum entries, its members, and its extensions, one item each.
     * A member is never split across pages, so a multi-line block, like a `companion object { … }`,
     * counts as a single item, and the [pageSize] does not bound the number of lines of a page.
     * A companion object having more members than a page is rendered with a hint to resolve it directly,
     * like `com.example.Outer.Companion`, which pages through its members.
     *
     * A member, like `com.example.Outer.create`, is paginated by its signatures: the overloads,
     * the ones of the companion object, rendered within its declaration, and the extensions, one item each.
     *
     * Only the declarations which can be used from the user code are rendered: neither the private,
     * nor the internal ones, nor the ones deprecated as hidden, while the protected ones only of a class
     * which the user code can subclass, maybe indirectly, or of the companion object of one:
     * an open or abstract class with a constructor other than a private one,
     * or a sealed class with such a subclass of the API.
     * Neither the classes which cannot be used, like the hidden or synthetic ones, are rendered,
     * nor anything declared within them.
     * A declaration within a protected class, resolved directly, follows a comment telling that class,
     * like `// in protected companion object Outer.Named`.
     * A `var` whose setter cannot be called, like with `private set`, is rendered as a `val`,
     * while a setter narrower than its property, like `protected set`, is rendered on the line following it.
     * An override of a `var` stays a `var`, as a `val` cannot override it, with its setter rendered
     * with its actual visibility, like `protected set` in a final class, even though it cannot be called there.
     *
     * @param name fully qualified name (e.g., "kotlin.collections.List")
     * @param page 1-based page index, coerced into the range of the pages
     * @param pageSize number of items per page, must be positive
     * @return a Kotlin signature, including members (classes),
     *      overloads (functions), or `null` if not found
     * @throws IllegalArgumentException if the [pageSize] is not positive
     */
    public fun resolve(
        name: String,
        page: Int = 1,
        pageSize: Int = 10
    ): String?

    /**
     * Searches for Kotlin declarations matching the query.
     *
     * @param query search term (case-insensitive substring match against declaration names)
     * @param page 1-based page index, coerced into the range of the pages
     * @param pageSize number of items per page, must be positive
     * @return matching declarations as text, or `null` if none found
     * @throws IllegalArgumentException if the [pageSize] is not positive
     */
    public fun search(
        query: String,
        page: Int = 1,
        pageSize: Int = 10
    ): String?
}

/**
 * Creates a [KotlinIntrospector] of the classes available to the [classLoader].
 *
 * @param classLoader the class loader to introspect, the context class loader of the current thread by default.
 * @param scanEntireClasspath whether to index the whole classpath on creation, `true` by default,
 *   which makes the extensions defined anywhere on the classpath, and the [KotlinIntrospector.search], available,
 *   at the cost of the scanning time.
 */
public fun KotlinIntrospector(
    classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    scanEntireClasspath: Boolean = true
): KotlinIntrospector = DefaultKotlinIntrospector(
    classLoader,
    scanEntireClasspath
)
