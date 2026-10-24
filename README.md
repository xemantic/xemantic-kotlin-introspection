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
  The members a class inherits from its supertypes, without redeclaring them, are not listed, not to bloat the listing,
  but they are resolved by its name, after the members it declares, which override them, and before the extensions,
  each as declared by the nearest supertype declaring it, under its `// inherited from` header,
  like `kotlin.collections.MutableList.get`, rendered as `operator fun get(index: Int): E` of `kotlin.collections.List`,
  the type arguments of the supertype not substituted.
  An override wins over the member it overrides, while the overloads it does not override are inherited too,
  and of the members of the same signature of unrelated supertypes, the one of the most specific return type wins,
  like `MutableCollection.iterator(): MutableIterator<E>` over `List.iterator(): Iterator<E>` for `MutableList`.
  A typealias to a Java class, like `kotlin.collections.ArrayList`, `kotlin.collections.HashMap`, or `kotlin.text.StringBuilder`,
  is not resolved yet.
* The Kotlin built-in types, like `kotlin.String` or `kotlin.collections.List`,
  which are mapped to the JVM types and have no class files of their own,
  are rendered like any other class from their declarations in the `.kotlin_builtins` metadata of the standard library,
  like `class Int private constructor() : Number, Comparable<Int>`, or `object Unit`,
  together with their companion objects, like the one of `kotlin.Int`, whose members also resolve by the name of the type,
  like `kotlin.Int.MAX_VALUE`.
  The ones with Kotlin metadata of their own, like `kotlin.UInt`, are rendered from it.
  `kotlin-reflect` only tells the deprecations of their members, which the `.kotlin_builtins` metadata does not carry,
  and the members of their JVM types only, like `String.chars()`,
  listed when the names of their parameters are known, which the JDK is compiled without.
  The body of a built-in type also lists the extensions of the standard library reading like its members,
  without their receivers, like `fun fill(value: E)` of `fun <T> MutableList<T>.fill(value: T)` in `interface MutableList<E>`,
  sorted by name together with its members, while the other ones are listed after it, with their receivers,
  like `fun String?.orEmpty()`, callable also on `null`, or `fun Iterable<Int>.sum()`.
  Only the extensions of the public `kotlin.*` packages are listed for the built-in types.
  When the `.kotlin_builtins` resources cannot be read, like when the standard library is a named module,
  whose resources are encapsulated, a warning is logged, and the built-in types are described by `kotlin-reflect` instead,
  approximately, from their JVM types, without their companion objects, and without the mutable collection types,
  which `kotlin-reflect` cannot tell apart from their read-only counterparts.
* The function types are resolved by their Kotlin names, like `kotlin.Function1` of `(P1) -> R`,
  or `kotlin.coroutines.SuspendFunction0` of `suspend () -> R`, listing the extensions on the function types,
  like `(suspend () -> T).startCoroutine(…)`.
  The compiler synthesizes these classes, so they are rendered like declared in the standard library sources,
  like `interface Function1<in P1, out R> : Function<R> { operator fun invoke(p1: P1): R }`.
  A function type with a receiver, or with context parameters, is of the same class,
  like `String.() -> Unit` and `context(String) () -> Unit` are both `kotlin.Function1`.
* The extensions on a type parameter bounded by a type,
  like `fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T`,
  are listed among the extensions of the bound,
  and with multiple bounds, like `where T : A, T : B`, only for the types satisfying all of them.
  Like any extension of a supertype, they are listed for the subtypes of the bound among the built-in types,
  but not for the other classes when the bound is a `kotlin.*` or `java.*` type,
  so that, e.g., a class implementing `Comparable` is not flooded with the generic extensions of the standard library.
  The extensions on an unbounded type parameter, like `let` or `also`, are not listed for any type,
  as they would be listed for every one.
* The types of the `kotlin.*` packages are rendered with simple names, as LLMs know them well,
  while the other types are fully qualified, to avoid ambiguity.
* Only the declarations which can be used from the user code are rendered:
  never the private or internal ones,
  the protected ones only of a class which the user code can subclass, maybe indirectly,
  or of the companion object of one —
  an open or abstract class with a constructor other than a private one,
  as even an internal one can be called by a public open subclass in its own module,
  or a sealed class with such a subclass —
  and never the ones deprecated with the `HIDDEN` level,
  or with `@DeprecatedSinceKotlin(hiddenSince = …)` not newer than the standard library of the class loader.
  The exception is a property declared by a primary constructor parameter of a data class, or a value class,
  which cannot be declared without it,
  so it is rendered with its visibility, like `private val b: Int`,
  or its deprecation, like `@Deprecated("Replaced", level = DeprecationLevel.HIDDEN) val a: Int`.
  An open class with only private constructors is assumed not to be subclassed,
  even though an open class nested in it might subclass it.
  The same rule applies to the setter of a `var`:
  when it cannot be called, like with `private set`, or `@set:Deprecated(level = HIDDEN)`,
  the property is rendered as a `val`,
  while a setter which can be called, but is narrower than the property,
  like the `protected set` of an open class, is rendered on its own line:

  ```kotlin
  var count: Int
      protected set
  ```

  An override of a `var` is the exception, as a `val` cannot override a `var`:
  it stays a `var`, with its setter rendered with its actual visibility,
  like `protected set` in a final class, even though the setter cannot be called there.
* The same rule applies to the classes, which are neither listed, nor resolved, nor found,
  together with everything declared within them,
  also when they cannot be used for another reason, like the synthetic ones,
  or the serializers generated by kotlinx.serialization.
  A class nested in a class whose class file is absent from the classpath is assumed to be part of the API,
  unlike the one nested in a class whose class file cannot be read.
  A declaration within a protected class, like a protected companion object,
  resolved directly, follows a comment telling that class,
  like `// in protected companion object Outer.Named`,
  as only the subclasses of its outer class can use it.
* A class, a constructor, a function, or a property deprecated with the `WARNING` or the `ERROR` level,
  which can still be referred to,
  is rendered with its `@Deprecated` annotation inline, telling what to use instead, like:

  ```kotlin
  @Deprecated("Use uppercase() instead.", ReplaceWith("uppercase(Locale.getDefault())"), level = DeprecationLevel.ERROR) inline fun String.toUpperCase(): String
  ```

  The level is the one in effect for the standard library of the class loader,
  also when told by `@DeprecatedSinceKotlin`, as for the declarations of the standard library,
  and it is rendered unless it is the default `WARNING`.
  To keep the signature compact,
  only the first line of the message is rendered, followed by `…` if there are more,
  and only the expression of `ReplaceWith`, without its imports.
* A class header without a constructor, like `class Foo`, means the implicit public constructor without parameters,
  as in Kotlin,
  so an explicit one is rendered whenever it differs,
  like `class Foo()` next to the secondary constructors listed in the class body,
  or `open class Foo protected constructor()`, callable only from a subclass.
  A class without any constructor callable from the user code,
  which can neither be instantiated, nor subclassed there,
  is marked with `private constructor()`, like `open class Base64 private constructor()`,
  whatever the actual visibility and parameters of its constructors,
  while the properties declared by its primary constructor are rendered in the class body.
  A data class, or a value class, cannot be declared without the parameters of its primary constructor,
  each declaring a property,
  so they are rendered after its `private constructor`, with the actual visibility of their properties,
  like `@JvmInline value class Duration private constructor(private val rawValue: Long)`.
  An object, an enum class, a sealed class, an interface, or an annotation class is not marked,
  as its kind already tells how it can be created, if at all.
  Neither are the headers of the nested classes and the sealed subclasses listed in their outer class,
  which are only references, rendered without constructors,
  while resolving such a class by its own name renders its constructors.
* The sealed subclasses are listed in their sealed class first, sorted by their names,
  relative to the sealed class when nested in it, or else to their package,
  where an inner class declared in another generic class is qualified with the type parameters it refers to,
  like `inner class Outer<T>.Sub : Sealed<T>`.
  A sealed subclass which cannot be listed is still told with a comment following them,
  as a `when` expression has to cover it:
  `// sealed subclass not found: com.example.Sub` when its class file is missing from the classpath,
  or `// sealed subclass could not be read: com.example.Sub` when it is present, but cannot be read,
  like without the Kotlin metadata.
  The ones which are not part of the API, like the internal ones, are omitted,
  told by `// sealed subclasses not part of the API omitted`,
  as a `when` expression covering the listed ones still needs an `else` branch.
* The whole classpath visible to the given class loader is scanned on creation, which takes a while,
  so the instance should be created once and reused.
  The scan can be disabled with `KotlinIntrospector(scanEntireClasspath = false)`,
  at the cost of losing the extensions defined in other classes, and the search.

## License

[GNU Affero General Public License v3.0](LICENSE)

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for the documentation conventions,
and for maintenance notes on updating the gradle wrapper and the project dependencies.
