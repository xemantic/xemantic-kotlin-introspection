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
  The extensions of its companion object, like `fun Foo.Companion.parse()`, called as `Foo.parse()`,
  are listed, and resolved, with the extensions of the class, like `String.Companion.format()` with `String.format()`,
  unless the companion object is deprecated as hidden, like by an upgrade of its library after compiling the extension,
  as Kotlin no longer calls `Foo.parse()` then,
  but not the extensions on the supertypes of the companion object, also called as `Foo.parse()`,
  not to flood the listing of the class, like with the ones on `Any`,
  while a class nested in the companion object is resolved only through it, like `com.example.Foo.Companion.Builder`,
  as Kotlin does not resolve `Foo.Builder` either.
  The deprecation of a companion object deprecated with the `WARNING` or the `ERROR` level,
  which Kotlin reports for `Foo.parse()`, and `Foo.Companion.parse()`,
  is told on its extensions listed with the class, or resolved by its name, in a comment, as no annotation compiles there,
  after the deprecation of the extension itself, if any,
  like `/* companion object deprecated: "Use the constructor", level = DeprecationLevel.ERROR */ fun Foo.Companion.parse(): Foo`.
  The members a class inherits from its supertypes, without redeclaring them, are not listed, not to bloat the listing,
  but they are resolved by its name, after the members it declares, which override them, and before the extensions,
  each as declared by the nearest supertype declaring it, under its `// inherited from` header,
  with the type arguments the class gives for the type parameters of the supertype substituted,
  like `kotlin.collections.MutableList.get`, rendered as `operator fun get(index: Int): E` of `kotlin.collections.List`,
  or the `get` of a class extending `java.util.AbstractList<String>`, rendered as `operator fun get(index: Int): String`,
  while a type parameter of the member named like a type parameter of the class it refers to is renamed apart from it,
  like `fun <U1> thenApply(arg0: Function<in U, out U1>?)` for a class extending `java.util.concurrent.CompletableFuture<U>`.
  An override wins over the member it overrides, while the overloads it does not override are inherited too,
  and of the members of the same signature of unrelated supertypes, the one of the most specific return type wins,
  like `MutableCollection.iterator(): MutableIterator<E>` over `List.iterator(): Iterator<E>` for `MutableList`,
  also of the types related only through a Java class, like `java.util.ArrayList`, a subtype of `MutableList`.
  A member with a body of an interface is rendered as `open`, also when inherited by a final class,
  telling it apart from an abstract one.
  The methods which a Java supertype declares are inherited too, as kotlin-reflect describes them,
  like `fun ensureCapacity(arg0: Int)` of `java.util.ArrayList`,
  but the ones overriding a member of a Kotlin supertype, like the `get` of `java.util.ArrayList`,
  for which the Kotlin member is inherited instead, declared with the names of its parameters, which the JDK does not tell,
  unless the Java method makes it more visible, like the public `clone()` of the protected one of `kotlin.Cloneable`,
  or returns a more specific type,
  and the ones overriding a JDK method which the built-in type does not have, like the `sort(Comparator)` of `java.util.List`,
  as Kotlin calls the `MutableList<T>.sort(comparator)` extension instead.
  A typealias to a Java class, like `kotlin.collections.ArrayList`, `kotlin.collections.HashMap`, or `kotlin.text.StringBuilder`,
  is not resolved yet, like any other typealias.
* The Kotlin built-in types, like `kotlin.String` or `kotlin.collections.List`,
  which are mapped to the JVM types and have no class files of their own,
  are rendered like any other class from their declarations in the `.kotlin_builtins` metadata of the standard library,
  like `class Int private constructor() : Number, Comparable<Int>`, or `object Unit`,
  together with their companion objects, like the one of `kotlin.Int`, whose members also resolve by the name of the type,
  like `kotlin.Int.MAX_VALUE`.
  The ones with Kotlin metadata of their own, like `kotlin.UInt`, are rendered from it.
  `kotlin-reflect` only tells the deprecations of their members, which the `.kotlin_builtins` metadata does not carry,
  and the members of their JVM types only, like `String.chars()`,
  whose parameters are named after their positions, like `Throwable.printStackTrace(arg0: java.io.PrintStream?)`,
  as the JDK is compiled without their names, which Kotlin does not let a call to a Java method name anyway,
  and whose parameters of the platform types of Java, like `Throwable!`, accepting `null`, are nullable,
  like `Throwable.initCause(arg0: Throwable?): Throwable`,
  but the JDK members newer than the ones mapped to the built-in type, like `String.indent(Int)` or `String.strip()`,
  which the compiler does not call, and kotlin-reflect tells deprecated as "not fully supported by Kotlin compiler",
  and the ones the compiler hides, like `Enum.getDeclaringClass()`, or `List.reversed()` of JDK 21,
  whose calls resolve to the extensions instead, like `fun <T> Iterable<T>.reversed()`.
  The mutable collection types also list the members of their JVM types only,
  like `MutableCollection.removeIf(arg0: java.util.function.Predicate<in E>)`, or `MutableList.removeFirst()` of JDK 21,
  and the types the compiler enhances as definitely not nullable are rendered so,
  like `MutableMap.merge(arg0: K, arg1: V & Any, arg2: java.util.function.BiFunction<in V & Any, in V & Any, out V?>): V?`,
  or as just the type argument given for `V` when it is not nullable anyway, like the `Int` of a `HashMap<String, Int>`, or a `V : Any`.
  An extension hiding a member of the same signature, annotated with `@kotlin.internal.HidesMembers` in the standard library,
  is listed in place of that member, while a member accepting more arguments is still listed next to it,
  like `Throwable.addSuppressed(arg0: Throwable?)`, called by `addSuppressed(null)`,
  next to `Throwable.addSuppressed(exception: Throwable)`, called for any other argument.
  The body of a built-in type also lists the extensions of the standard library reading like its members,
  without their receivers, like `fun fill(value: E)` of `fun <T> MutableList<T>.fill(value: T)` in `interface MutableList<E>`,
  sorted by name together with its members, while the other ones are listed after it, with their receivers,
  like `fun String?.orEmpty()`, callable also on `null`, or `fun Iterable<Int>.sum()`,
  or the ones on a projected receiver whose parameters would read narrower in the body than they are,
  like `fun <T> MutableCollection<in T>.plusAssign(elements: Array<T>)`, taking an array of any subtype of `E`,
  unlike `plusAssign(elements: Iterable<E>)`, read the same in the body of `interface MutableCollection<E>`.
  Only the extensions of the public `kotlin.*` packages of the standard library, and of `kotlin-reflect`,
  like `KClass.memberFunctions`, are listed for the built-in types,
  told by the Kotlin module declaring them, also when merged into one jar, like a shadow jar,
  not the ones of the other libraries declaring `kotlin.*` packages,
  like `kotlin-metadata-jvm`, `kotlin-test`, or `kotlin-scripting`,
  but the ones of any library on their companion objects, like `fun Int.Companion.serializer()` of kotlinx.serialization.
  When the `.kotlin_builtins` resources cannot be read, like when the standard library is a named module,
  whose resources are encapsulated, a warning is logged, and the built-in types are described by `kotlin-reflect` instead,
  approximately, from their JVM types, without their companion objects,
  still listing the extensions of the companion objects, like `String.Companion.format()`, told by kotlin-reflect,
  and without the mutable collection types,
  which `kotlin-reflect` cannot tell apart from their read-only counterparts.
* The function types are resolved by their Kotlin names, like `kotlin.Function1` of `(P1) -> R`,
  or `kotlin.coroutines.SuspendFunction0` of `suspend () -> R`, listing the extensions on the function types,
  like `(suspend () -> T).startCoroutine(…)`,
  of any library, like `(() -> T).asFlow()` of kotlinx-coroutines, unlike for a built-in type, as there are few of them.
  The compiler synthesizes these classes, so they are rendered like declared in the standard library sources,
  like `interface Function1<in P1, out R> : Function<R> { operator fun invoke(p1: P1): R }`.
  A function type with a receiver, or with context parameters, is of the same class,
  like `String.() -> Unit` and `context(String) () -> Unit` are both `kotlin.Function1`.
  A class implementing a function type, like `class Handler : (Event) -> Unit`,
  lists the extensions of its function type class as well, whatever their type arguments.
  The function reference types, like `kotlin.reflect.KFunction1`, are resolved the same way,
  like `interface KFunction1<in P1, out R> : KFunction<R>, Function1<P1, R>`,
  listing also the extensions on `KFunction`, like `KFunction<*>.javaMethod`.
* The extensions on a type parameter bounded by a type,
  like `fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T`,
  are listed among the extensions of the bound,
  and with multiple bounds, like `where T : A, T : B`, only for the types satisfying all of them,
  also through a Java supertype, like `java.io.Closeable` of `java.io.FilterInputStream`.
  Like any extension of a supertype, they are listed for the subtypes of the bound among the built-in types,
  but not for the other classes when the bound is a `kotlin.*` or `java.*` type,
  so that, e.g., a class implementing `CharSequence` is not flooded with the generic extensions of the standard library,
  unless the class satisfies all the multiple bounds,
  like of `R.contains(element: T?)` where `R : ClosedRange<T>, R : Iterable<T>`,
  or the bound is `Comparable`, whose few extensions are listed for a class comparable to itself,
  like `class Version : Comparable<Version>`, as told by the Kotlin metadata, but not for an enum class.
  The ones on `T : Comparable<T>` are not listed for a final type comparable only to itself, which pins `T` to it,
  like `Int`, when it declares a more specific overload, a member, or an extension on the type itself,
  taking the same parameters, of the same names, where `T` stands for the type,
  like `fun <T : Comparable<T>> T.coerceAtLeast(minimumValue: T): T` next to `fun Int.coerceAtLeast(minimumValue: Int): Int`,
  called instead, unless the type argument is given explicitly, like `5.coerceAtLeast<Int>(3)`.
  Any other one is listed, as its type parameter can be inferred as a supertype,
  or it can be called with other arguments, like `5.coerceIn(null, 10)`, or `5.rangeTo(that = 6)`.
  The extensions on an unbounded type parameter, like `let` or `also`, are not listed for any type,
  as they would be listed for every one.
* The types of the `kotlin.*` packages are rendered with simple names, as LLMs know them well,
  and so are the types of the package of the rendered class, or of the file facade, relative to it,
  like `Outer.Nested`, while the other types are fully qualified, to avoid ambiguity.
* The extensions are listed under the `// from …` header of the package, or the object, to import them from,
  which is the Kotlin package also for a file facade compiled with `@JvmPackageName`,
  like `kotlin.collections` of `Map.getOrDefault()` of `kotlin.collections.jdk8.CollectionsJDK8Kt`.
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
  Likewise, the `open` modifier of a member, and the `final` one of an override,
  are rendered only in a class which the user code can subclass,
  as all the members of any other class are final to it,
  like the ones of a sealed class without such a subclass,
  or of an enum class, even when its entries override them.
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
  Conversely, a `var` whose getter only is hidden, like with `@get:Deprecated(level = HIDDEN)`,
  can still be assigned, but not read,
  so it is rendered with that annotation, like `@get:Deprecated("Replaced", level = DeprecationLevel.HIDDEN) var a: Int`,
  unless its setter cannot be called either.
* The same rule applies to the classes, which are neither listed, nor resolved, nor found,
  together with everything declared within them,
  also when they cannot be used for another reason, like the synthetic ones,
  or the serializers generated by kotlinx.serialization.
  A class nested in a class whose class file is absent from the classpath is assumed to be part of the API,
  unlike the one nested in a class whose class file cannot be read.
  A class relocated by shading, whose Kotlin metadata keeps its original name,
  is resolved, found, and listed by its relocated name,
  together with its enclosing classes, its nested classes, its companion object, and its sealed subclasses,
  while the types its declarations refer to keep their original names.
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

  The accessors of a `var` deprecated on their own keep their targets,
  as only the reading, or only the assigning, is deprecated,
  like `@set:Deprecated("Assign y", level = DeprecationLevel.ERROR) var x: Int`,
  while a `var` rendered as a `val` is deprecated as a whole by the higher of the deprecations of the property and its getter.
  The level is the one in effect for the standard library of the class loader,
  also when told by `@DeprecatedSinceKotlin`, as for the declarations of the standard library,
  and it is rendered unless it is the default `WARNING`.
  The user code is assumed to be compiled with the API version of that standard library:
  a lower `-api-version`, which the class loader does not tell,
  might still allow a declaration which is rendered with a higher level, or not rendered at all as hidden.
  When this library is shaded with a relocated `kotlin` package,
  the version of the standard library bundled with it is assumed instead.
  To keep the signature compact,
  only the first line of the message is rendered, followed by `…` if there are more,
  and only the expression of `ReplaceWith`, without its imports.
* A class compiled with `-Xcontext-receivers` by a Kotlin compiler before 2.2 keeps its legacy context receivers in its header,
  without names, as a class cannot declare context parameters,
  marked with a comment, as Kotlin 2.2+ cannot compile such a header:
  `context(Logger) /* legacy context receivers, not compiled by Kotlin 2.2+ */ class Foo`.
  The legacy context receivers of its functions and properties are rendered as their context parameters,
  like `context(_: Logger) fun bar()`, which Kotlin 2.2+ compiles.
* A class header without a constructor, like `class Foo`, means the implicit public constructor without parameters,
  as in Kotlin,
  so an explicit one is rendered whenever it differs,
  like `class Foo()` next to the secondary constructors listed in the class body,
  or `open class Foo protected constructor()`, callable only from a subclass.
  A class without any constructor callable from the user code,
  which can neither be instantiated, nor subclassed there,
  is marked with `private constructor()`, like `open class Base64 private constructor()`.
  The marker is a convention, not the actual signature:
  the constructors might be internal, or take parameters,
  like the ones of `IntProgression`, `Instant`, or `Uuid`,
  while the properties declared by its primary constructor are rendered in the class body.
  A data class, or a value class, cannot be declared without the parameters of its primary constructor,
  each declaring a property,
  so they are rendered after the same `private constructor` marker, with the actual visibility of their properties,
  like `@JvmInline value class Duration private constructor(private val rawValue: Long)`.
  An object, an enum class, a sealed class, an interface, or an annotation class is not marked,
  as its kind already tells how it can be created, if at all.
  Neither are the headers of the nested classes and the sealed subclasses listed in their outer class,
  which are only references, rendered without constructors,
  while resolving such a class by its own name renders its constructors.
* The sealed subclasses are listed in their sealed class first, sorted by their names,
  relative to the sealed class when nested in it, or else to their package,
  where an inner class declared in another generic class is qualified with the type parameters it refers to,
  like `inner class Outer<T>.Sub : Sealed<T>`,
  and the companion object of another class is referred to as an object, like `object Holder.Companion : Sealed`.
  So is an inner class of a generic class resolved directly,
  like `inner class Outer<T>.Inner : Comparable<T>`,
  as nothing else in its listing declares the type parameters of its outer classes.
  When the class file of such an outer class is absent from the classpath,
  the names of its type parameters are not known,
  and the ones the inner class refers to are named by their ids in the metadata, like `Outer<T0>.Inner`,
  followed by `_` when the inner class, one of its outer classes read, or one of their members,
  declares a type parameter of the same name, like `Outer<T0_>.Inner<T0>`.
  The ones it does not refer to are not known, nor how they are split between two such outer classes,
  so that all of them are given to the innermost one, like `Mid<T0, T1>.Leaf`.
  A sealed subclass which cannot be listed is still told with a comment following them,
  as a `when` expression has to cover it:
  `// sealed subclass not found: com.example.Sub` when its class file is missing from the classpath,
  or `// sealed subclass could not be read: com.example.Sub` when it is present, but cannot be read,
  like without the Kotlin metadata.
  The ones which are not part of the API, like the internal ones, are omitted,
  told by `// sealed subclasses not part of the API omitted`,
  as a `when` expression covering the listed ones still needs an `else` branch.
* A member overriding a member of a supertype is marked `override`,
  told by the Kotlin signatures, as the compiler does.
  An internal member of a supertype can be overridden only in its own module, or in a friend module,
  like the test module of the main one,
  which the metadata does not tell, as unrelated modules might share a module name,
  like the default `main` of `kotlinc` without `-module-name`,
  so the JVM methods of the overriding class, mangled with the module name, like `reset$core`, are the evidence.
  A `@PublishedApi internal` member is not mangled, so its JVM method tells nothing,
  and the modality of the member is the evidence instead in a final class,
  where only an override is open, and a final member is not an override,
  as it is for any internal member when a shrinker, like R8, removed the JVM method of the member, with its bridge,
  or when the JVM methods of the overriding class can be neither read nor reflected.
  This has known limitations:
  - in a class which is not final, a public override of a `@PublishedApi internal` member in a friend module
    is not marked `override`, and does not inherit the default values of its parameters,
    as it cannot be told from a member of the same signature of an unrelated module;
  - in such a class, a member of the same signature as a `@PublishedApi internal` member of an unrelated module of the same name,
    like two modules both compiled with the default `main`, is marked `override`,
    as that module cannot be told from the same one;
  - in a final class, a member of the same signature as a `@PublishedApi internal` member of a supertype,
    or, when shrunk or the JVM methods of the class cannot be read, as any internal member, marked `open` in vain, is marked `override`,
    while its override marked `final override` in vain is not;
  - in a class which is not final, an override in a friend module is not marked
    when a shrinker removed the bridge telling it,
    or when the JVM methods of the overriding class can be neither read nor reflected;
  - a member whose JVM method a shrinker removed, overloading a Java member of the same name,
    or one of a Kotlin supertype without the metadata, is marked `override`,
    as the shrinker might have removed the overridden JVM method as well,
    unless it is a member of a JDK class, like `Throwable.toString()`, which is never shrunk,
    or an override of one, like `toString()` of an application Java exception.
* The whole classpath visible to the given class loader is scanned on creation, which takes a while,
  so the instance should be created once and reused.
  The scan can be disabled with `KotlinIntrospector(scanEntireClasspath = false)`,
  at the cost of losing the extensions defined in other classes, and the search.

## License

[GNU Affero General Public License v3.0](LICENSE)

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for the documentation conventions,
and for maintenance notes on updating the gradle wrapper and the project dependencies.
