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
 */
public interface KotlinIntrospector {

    /**
     * Resolves metadata for a Kotlin declaration by its fully qualified name.
     *
     * @param name fully qualified name (e.g., "kotlin.collections.List")
     * @param page 1-based page index
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
     * @param page 1-based page index
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
