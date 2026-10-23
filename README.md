# xemantic-kotlin-introspection

Kotlin API introspection of the classpath, rendering LLM-friendly signatures

[<img alt="Maven Central Version" src="https://img.shields.io/maven-central/v/com.xemantic.kotlin/xemantic-kotlin-introspection">](https://central.sonatype.com/artifact/com.xemantic.kotlin/xemantic-kotlin-introspection)
[<img alt="GitHub Release Date" src="https://img.shields.io/github/release-date/xemantic/xemantic-kotlin-introspection">](https://github.com/xemantic/xemantic-kotlin-introspection/releases)
[<img alt="license" src="https://img.shields.io/github/license/xemantic/xemantic-kotlin-introspection?color=blue">](https://github.com/xemantic/xemantic-kotlin-introspection/blob/main/LICENSE)

[<img alt="GitHub Actions Workflow Status" src="https://img.shields.io/github/actions/workflow/status/xemantic/xemantic-kotlin-introspection/build-main.yml">](https://github.com/xemantic/xemantic-kotlin-introspection/actions/workflows/build-main.yml)
[<img alt="GitHub branch check runs" src="https://img.shields.io/github/check-runs/xemantic/xemantic-kotlin-introspection/main">](https://github.com/xemantic/xemantic-kotlin-introspection/actions/workflows/build-main.yml)
[<img alt="GitHub commits since latest release" src="https://img.shields.io/github/commits-since/xemantic/xemantic-kotlin-introspection/latest">](https://github.com/xemantic/xemantic-kotlin-introspection/commits/main/)
[<img alt="GitHub last commit" src="https://img.shields.io/github/last-commit/xemantic/xemantic-kotlin-introspection">](https://github.com/xemantic/xemantic-kotlin-introspection/commits/main/)

[<img alt="GitHub contributors" src="https://img.shields.io/github/contributors/xemantic/xemantic-kotlin-introspection">](https://github.com/xemantic/xemantic-kotlin-introspection/graphs/contributors)
[<img alt="GitHub commit activity" src="https://img.shields.io/github/commit-activity/t/xemantic/xemantic-kotlin-introspection">](https://github.com/xemantic/xemantic-kotlin-introspection/commits/main/)
[<img alt="GitHub code size in bytes" src="https://img.shields.io/github/languages/code-size/xemantic/xemantic-kotlin-introspection">]()
[<img alt="GitHub Created At" src="https://img.shields.io/github/created-at/xemantic/xemantic-kotlin-introspection">](https://github.com/xemantic/xemantic-kotlin-introspection/commits)
[<img alt="kotlin version" src="https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fxemantic%2Fxemantic-kotlin-introspection%2Fmain%2Fgradle%2Flibs.versions.toml&query=versions.kotlin&label=kotlin">](https://kotlinlang.org/docs/releases.html)
[<img alt="discord users online" src="https://img.shields.io/discord/811561179280965673">](https://discord.gg/vQktqqN2Vn)
[![Bluesky](https://img.shields.io/badge/Bluesky-0285FF?logo=bluesky&logoColor=fff)](https://bsky.app/profile/xemantic.com)

## Why?

LLM agents can act by writing code, e.g. Kotlin scripts executed with [xemantic-kotlin-script](https://github.com/xemantic/xemantic-kotlin-script).
But the APIs available to the agent are not always in its training data,
or might have changed since,
so the agent needs to look them up, the way a developer would in the IDE.

This library resolves any Kotlin declaration available on the classpath,
and renders it as a Kotlin signature, without the bodies,
together with the extensions of the type, which are defined anywhere on the classpath.
It can also search the declarations, when the exact name is unknown.
The output is paged, so it fits into the context window of the LLM.

Extracted from [Golem XIV](https://github.com/xemantic/golem-xiv).

## Usage

In `build.gradle.kts` add:

```kotlin
dependencies {
    implementation("com.xemantic.kotlin:xemantic-kotlin-introspection:0.1.0")
}
```

Then:

```kotlin
val introspector = KotlinIntrospector()
println(introspector.resolve("com.example.TestClass"))
```

prints:

```kotlin
// com.example.TestClass [page 1/1]
class TestClass(val name: String) {
    fun greet(): String
}
// from com.example
fun TestClass.farewell(): String
// from com.example.ext
val TestClass.uppercaseName: String
fun TestClass.describe(): String
```

while `introspector.search("TestClass")` lists the fully qualified names of the matching declarations:

```kotlin
// search results for "TestClass" [page 1/2]
com.example.AbstractTestClass
com.example.AnnotationTestClass
...
```

* Both `resolve` and `search` take the 1-based `page`, and the positive `pageSize`, `10` by default,
  and return `null` if nothing is found.
* The output is paged by items,
  and every page can be read on its own,
  as the headers the items are listed under, like the `// from` header of the extensions,
  are repeated on every page.
* A class is paged by its enum entries, its members, and its extensions, one item each.
  A member is never split across pages,
  so a multi-line block, like a `companion object { … }`, counts as a single item,
  and the `pageSize` does not bound the number of lines of a page.
  A companion object having more members than a page comes with a hint to page through it by resolving it directly,
  like `introspector.resolve("com.example.Outer.Companion")`.
* A member is resolved by the name of its class, like `com.example.Outer.create`,
  which finds everything of that name the listing of the class shows:
  its members, the members of its companion object, and the extensions of the class and its supertypes,
  paged by signatures, one item each.
* The Kotlin built-in types, like `kotlin.String` or `kotlin.collections.List`,
  which are mapped to the JVM types and have no Kotlin metadata, are resolved through `kotlin-reflect`.
  The mutable collection types, like `kotlin.collections.MutableList`,
  sharing the JVM type with their read-only counterparts,
  are resolved from the `.kotlin_builtins` metadata of the standard library instead,
  and so are the companion objects of the built-in types, like `kotlin.Int.Companion`.
* The types of the `kotlin.*` packages are rendered with simple names, as LLMs know them well,
  while the other types are fully qualified, to avoid ambiguity.
* The whole classpath visible to the given class loader is scanned on creation, which takes a while,
  so the instance should be created once and reused.
  The scan can be disabled with `KotlinIntrospector(scanEntireClasspath = false)`,
  at the cost of losing the extensions defined in other classes, and the search.

## License

[GNU Affero General Public License v3.0](LICENSE)

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for the documentation conventions,
and for maintenance notes on updating the gradle wrapper and the project dependencies.
