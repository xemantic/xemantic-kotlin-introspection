# CLAUDE.md

This file captures only what cannot be inferred from the codebase itself.

## Rules for editing this file

Both developers and AI agents are expected to add entries as they encounter surprises.

- **Add an entry** when you encounter something unexpected: a build quirk, a non-obvious constraint, a dependency gotcha, or any behavior that would surprise the next agent or developer.
- **Add an entry** when a developer flags an anti-pattern produced by AI — describe the anti-pattern and the preferred alternative.
- **Do not** add codebase overviews, directory listings, or anything discoverable by reading the source.
- Keep entries concise: the lesson and its non-obvious *why*, grouped under a heading if a theme emerges.
  Name a symbol or test as a pointer, but don't restate what its source comment already says.

## Conventions

### Markdown authoring

Markdown files use [semantic line breaks](https://sembr.org/):
break a line after a sentence,
and optionally at clause boundaries within a long sentence,
so that diffs stay meaningful and reviewable.

There is no column width limit —
never reflow or hard-wrap a paragraph to fit some character count.
Modern editors soft-wrap Markdown visually,
see [DEVELOPMENT.md](DEVELOPMENT.md#markdown-soft-wrapping-in-the-ide) for how to enable it.

### Tests

- **Write the failing test first.**
  For any newly found bug, edge case or use case, add a test that reproduces it *before* touching the implementation, watch it fail, then make it pass.
  Add it at the narrowest level that exercises the behavior — an end-to-end test can pass while the bug hides in an untested combination.
  Scratch tests used to probe behavior are fine, but replace them with a named regression test before finishing.
- Tests keep the `// given`, `// when`, `// then` comment structure — AI agents tend to omit it.
  In a functional-style test where the setup is inlined into the action, `// given` may be omitted, but `// when` and `// then` stay.
- Exception: the tests in `KotlinIntrospectorTest` exercising a single `resolver.resolve(…)` / `resolver.search(…)` call are kept compact —
  no `// when` / `// then` and no intermediate variable, the assertion is chained on the call itself:
  `resolver.resolve(…) sameAsKotlin """…"""` for a snapshot,
  `resolver.resolve(…) should { have(…) }` for partial checks,
  and `assertFailsWith<…> { resolver.resolve(…) } should { have(message == …) }` for an expected exception.
  There are dozens of them, and the structure comments would only add noise; a comment explaining *why* the output looks the way it does may stay above the call.
- Assert a boolean expression with `assert(expr)` from `com.xemantic.kotlin.test` — it is power-assert-instrumented in `build.gradle.kts`, so a failure prints every subvalue of the expression.
  Do not fall back to `assertEquals` / `assertTrue` / `assertNotNull` from `kotlin.test`, which discard that information.
- Never assert on wall-clock time (a ratio or a bound): tests run under whatever CI load there is, and fail with no code change.
  To guard against, say, quadratic backtracking, feed an input large enough that the regression hangs for minutes while the correct code takes milliseconds.

### Working with AI agents

- In Claude Code "auto mode", never commit on your own — leave the changes in the working tree so the developer can review the diff first, and commit only when explicitly asked.
- The copyright year range (e.g. 2025-2026) is applied on autosave — a new file uses only the current year.

## Known gotchas

- After upgrading the Gradle wrapper, `test` may fail with `NoSuchFileException: build/test-results/test/binary/in-progress-results-generic.bin`, because the results of the previous Gradle version are stale — delete `build/test-results` (or run `clean`) and retry.
- After a fresh Gradle daemon starts, the first build reports `configuration cache cannot be reused because file '.../caches/jreleaser/jreleaser/<version>/marker.txt' has changed` —
  the JReleaser plugin bumps a banner counter in that file behind Gradle's back on every configuration,
  so the cache is invalidated once per daemon lifetime and reused normally afterwards; it is harmless.

- The Gradle configuration cache is enabled (`gradle.properties`), so a task action (`doLast { … }`) must not call a function declared in the build script — the script object is not serializable.
  Inline the logic or move the helper into `buildSrc` / an included build, and register tasks with `tasks.register(...)` rather than the `by tasks.registering` delegate.

- kotlin-metadata-jvm reads the module name the compiler omits from the metadata (the default `main`) back as `KmClass.moduleName == "main"`, not `null` —
  so unrelated default-named modules both report `"main"`, and a `null` check cannot detect the default module name.

- In a function type the `suspend` modifier precedes the context parameters: `suspend context(Ctx) () -> Unit` —
  the `context(Ctx) suspend () -> Unit` order, which the GitHub issues used, does not compile.

- kotlin-reflect reports a synthetic `@Deprecated("This member is not fully supported by Kotlin compiler, …")` on the JDK members of the built-in types newer than the mapped ones, like `String.strip()` or `describeConstable()`,
  mirroring the K1 warning; K2 rejects them outright, see `isUnsupportedByCompiler`.

- Reflecting the members of a class one by one does not get around a missing parameter type, see `reflectedMembersFile`.

- A mapped Java interface named directly as a supertype, like `abstract class X : java.util.Collection<String>`, keeps its Java members in K2, but inconsistently:
  `java.util.Collection`, `java.util.Map` and `java.lang.CharSequence` have only the Java functions (`size()`, `keySet()`, `length()`, no `size`, `keys`, `length`),
  `java.util.List` and `java.util.Set` have the `size` property and no `size()`, and `java.util.Map.Entry` has both `key` and `getKey()` —
  so `reflectedJavaClass` keeps a table of the Java-viewed ones (`JAVA_VIEWED_BUILTIN_INTERFACES`), verified with kotlinc 2.4.21.

- When probing kotlinc, a probe file with many errors can leave out some of them, like an `unresolved reference` of a later call,
  so a call reported as compiling there may not compile — confirm such a shape in a file of its own,
  assigning the call to a mistyped `val x: Int = …`, whose error names the type it resolves to.

- A call cannot tell whether kotlinc merges two inherited members of the same signature: K2 drops equivalent candidates instead of reporting an ambiguity.
  Probe it with a concrete class inheriting a Java implementation and a Kotlin abstract member — `does not implement abstract member` means they stay apart.
  The rule is by JVM parameter types, see `isMergedJvmParameter`.

- When probing kotlinc with Java fixtures, compile the Java sources with javac first and pass the classes on the classpath:
  K2 analyzing `.java` sources next to the Kotlin ones crashes in supertype resolution (`ClassCastException`) on shapes like `interface J extends KG<String>` where `KG<T> : R<Array<T>>`,
  and erases a raw Kotlin supertype of a Java source differently, like `T : CharSequence` to `Any` instead of `CharSequence` —
  so the Gradle test build, compiling both together, rejects some fixtures which kotlinc accepts against compiled Java classes.

- The test classpath resolves `org.jetbrains:annotations` to 26.x, of both the declaration and the `TYPE_USE` targets, not the 13.0 the standard library depends on —
  javac writes the `@NotNull String[]` of a fixture also as a type annotation of the elements, so a kotlinc probe of the fixtures must compile against the same version.

- A Java test fixture implementing a Kotlin member deprecated as `HIDDEN` cannot mark it `@Override`, see `JavaPrimitiveAndBoxedHiddenPut`.

- A Java test fixture cannot refer to a Kotlin class of the same source set whose supertype takes a `T & Any`, like `interface NotNullBox<T> : Box<T & Any>`:
  K2 fails on it with `Unexpected type: class ConeDefinitelyNotNullType` (`FirJavaElementFinder`), while compiling the Kotlin sources —
  such a class goes to a source set compiled before, see `PlatformBoxes.kt` of `otherModuleTestFixtures`.

- Two supertypes of the same class with different type arguments compile only when one of them is reached through Java,
  like `Box<List<String>>` next to `Box<List<String!>>` — two Kotlin ones are rejected (`has inconsistent values`),
  so a sweep of supertypes reached through several paths needs Java fixtures.

- A `null` call tells nothing of the member when a standard library extension accepts it, like `contains(element: T)` of `Iterable`, `remove(key, value)` or `getOrDefault` of `Map` —
  `x.getOrDefault("", null)` compiles on a `HashMap<String, String>` subclass through the extension.
  Probe the member only, shadowing the extension by one of the same name in the probe's own package, returning a marker type.

- When probing kotlinc with an internal declaration of another module, give the modules different `-module-name`s,
  as both are `main` otherwise, and the internal one is visible —
  while a public member of an internal class is visible anyway through a public subclass, like a Java one, which needs no suppressed error.

- A Java method and a Kotlin member of unrelated supertypes, of the same JVM signature, are merged only when their Kotlin types agree but for the platform ones:
  a Java `accept(@NotNull String)` next to a Kotlin `accept(s: String?)` is rejected with `inherited platform declarations clash`, so such a join is no valid fixture.

- kotlinc 2.4.21 rejects an enum entry named `entries` (`conflicting declarations`, KT-48872), also one deprecated as `HIDDEN`,
  so the entry shadowing the `entries` property can be neither probed, nor compiled as a fixture.

- `CallInference` models the constraint system of K2 after the compiler sources
  (`VariableReadinessCalculator`, `ConstraintIncorporator`, `ResultTypeResolver`, `NewCommonSuperTypeCalculator`, tag `v2.4.21`),
  not after probes: the rules of the order the type parameters are fixed in, derived from probes, regressed in every round (#268, #292).
  Change it only against a sweep of extension shapes × receivers judged by kotlinc, comparing the listings before and after.

- kotlinc reports a violated bound of an inferred type parameter only as a warning,
  when a type parameter of the same call is inferred as an empty intersection, like `Int & String`, itself only a warning —
  so `x.ext()` compiles with `A` inferred as `Any` for `A : Comparable<A>`.
  A probe compiled with `-nowarn` does not tell the two apart.

- With a type argument given explicitly, like `x.ext<_, Nothing, _>()`, kotlinc reports the violated bound of an inferred type parameter
  as an error also next to an empty intersection, unlike when all of them are inferred, see `CallInference`.

- When judging a sweep by calls with explicit type arguments, give explicitly only what the README convention takes as given:
  a `W` of `W : U`, or an `E` of `T : List<E>`, is judged by `x.ext()` alone (#343),
  though kotlinc says `cannot infer type for type parameter 'E'. Specify it explicitly.`
  Never count a call compiling only with `Nothing` (#366): `x.ext<Nothing, _>()` of `<A : Comparable<A>, B : A> Sink<B>.ext()`
  compiles on nearly any contravariant receiver.
  The next entry is the one shape not reconciled with this rule.

- A plain `x.ext()` rejected by kotlinc does not tell that the extension cannot be called:
  a type parameter which neither the receiver nor a bound refers to, like `U` of `fun <T : Comparable<T>?, U : T> Ct<T & Any>.ext()`,
  given explicitly, changes what the ones its bound refers to are inferred as — `x.ext()` fails on a `Ct<String>` with `inferred type is 'String?'`, while `x.ext<_, String>()` compiles.
  A probe judging an extension not callable must give such type parameters explicitly too, but not the ones the receiver refers to —
  so #334 lists this platform-type shape, against the sweep rule above, which #343 decided later.

- A kotlinc crash, like `IllegalStateException: Arguments depth is too high` (see `matchesOnce`), ends the whole compilation:
  a batch probe reports one such row per run, with no `error:` line, so keep the classes causing it out of a batch.

- Whether kotlinc crashes (`Arguments depth is too high`) is not told by the path the model takes to its verdict,
  see `constrainsSubtypeOfIntersection`, `tellSubtypesInferredFrom` (#376).

- In a kotlinc probe `import java.util.function.*` does not bring `Function`: the default import of `kotlin.Function` wins over a star import,
  so `Function<String, String>` fails with `one type argument expected` — import `java.util.function.Function` explicitly.

- A Java subclass of a JDK class which kotlin-reflect cannot describe, like of `java.util.LinkedHashSet`, cannot simply keep its overrides
  of the members of the built-in types when converted, see `javaMembersOf`: they would be listed as taking platform types,
  like `addAll(Collection<out String>?)` or `removeIf(Predicate<in String>?)`, while kotlinc rejects `addAll(null)` and `removeIf(null)` there,
  as kotlin-reflect tells of the same subclass of `java.util.TreeSet`.

- K2 enhances a method a Java class inherits without declaring it, by the methods of its other supertypes, like an override,
  but only where the inherited one tells nothing, and not in a Kotlin class joining the same supertypes — see `joinedParameterNullability`.
  A probe of it: `val r: Marker = x::bar` names the function type with its platform types, like `KFunction1<String!, Unit>`.

- kotlinc rejects `rec<String>(null)` of a Java `<T extends Comparable<T>> void rec(T t)` with `actual type is 'Nothing?', but 'String!' was expected`,
  though the message names a platform type, but accepts `rec<BigDecimal>(null)`, or a Java `JCS implements Comparable<JCS>`:
  the verdict depends on who declares the type argument, not on the bound, so probe such a shape with Java-declared type arguments too —
  see `givenTypeVariableNames`.

- Telling the relocation right is not enough where a package is relocated onto the name of another relocated one,
  like `p/lib/sub/` to `p/lib/` next to `p/lib/` to `p/lib/old/`, so that a JVM method equals another signature by identity:
  a type of a Java member is matched by both its JVM name and the name the relocation translates it back to (`unlocatedType`),
  and a type argument substituted from the metadata is translated back once more,
  so a rule pairing these JVM methods right turned missed overrides into false `final override`s (#355),
  like of `fun more(e: p.lib.sub.Event)` next to `override fun more(e: p.lib.Event)` of a Java `more(p.lib.Event)` —
  the matching has to change together with it.

- An optimizer, like ProGuard (`method/marking/static`), makes the JVM method of a member static, keeping its descriptor and the metadata,
  so a static method of a class is not necessarily a JVM-only one, like of a `@JvmStatic` function (#355).

- A class file relocated twice keeps the metadata name of the first relocation, like `o/ext/Event` found as `s/p/ext/Event`,
  while the classes compiled against it refer to it as `p/ext/Event`, so the name a Kotlin class tells is not always the one its users know (#355).

- With the parameter classes missing, a class relocated alone into a subpackage of its relocated package (`p/lib/Event` to `s/lib/ev/Event`)
  cannot be told from a JVM method of no member naming a class of that subpackage next to a shrunk override (`je(Ls/lib/sub/Event;)V`, added by a bytecode tool):
  the class files are the same up to the names, see `apartCandidates` —
  taking a Kotlin class found by the package-given name, but telling a name of a relocation before, for the class itself fixes the latter
  and breaks package merges of the review harness (#355).

- Pairing more JVM methods of a relocated class right is not a gain by itself: a package the JVM methods leave untold, as relocated to two packages,
  keeps the lookups of packages merged into one, like `p/lib/` and `p/ext/` into `m/`, from false matches —
  counting the prefix of a supertype for the prefix pairing (`prefixRelocatedPairs`), or telling a package by its other classes the JVM methods name,
  fixed a few thousand listings of the #355 harness and broke a thousand of the merge layouts (#380), so measure such a rule on every layout.
  Likewise, a rule telling a package which was left untold locates there every class of it no JVM method pairs, like a shrunk member's,
  which turns into a false `override` — see `pairedPackages`.

- A sweep judging an extension "callable only with `Nothing`" from a fixed list of type arguments misjudges it since `Nothing` does not count (#366, #362):
  most such rows compile with a declared self-referring type — probe them with declared types too, see `isDeclarableFor`,
  also of a suspend function type: `interface X : suspend () -> X` makes `<T : suspend () -> T> Inv<T>` callable on `G<X>` of `G<V> : Inv<suspend () -> V>` (#376).

- Kotlin never passes named arguments to a Java method of its own, even one compiled with `-parameters`, so a Java method taking parameters never shadows an extension — see `isShadowingJavaMethod`;
  a Java method overriding a Kotlin member, or merged with one, takes the names of the Kotlin one though, like `k.over(x = 1)`, see the entry on `namedByKotlinMembers` below —
  its shadowing is not modelled: the extension next to such a Java override stays listed.

- kotlinc 2.4.21 infers the `T` of an invariant `X<T>` from an `out Nothing` projection, also nested in an invariant position,
  like `fun <T> Inv<MutableCollection<T>>.f()` on `Inv<MutableCollection<out Nothing>>`, but not from `out String`, nor does it accept `Inv<MutableCollection<String>>` there —
  as `Nothing` does not count as a type argument making an extension callable (#366), the `out` of `java.util.List<out V>` is kept, see `projectionsOf`.

- A JDK interface named in Kotlin, like `java.util.Collection<String>`, is no supertype of another one named so, like `java.util.List<String>`:
  the Java supertypes of `java.util.List` are mapped to the built-in types, like `MutableCollection<E!>`, so only those are reached.

- In a sweep of classes of two type parameters, like `interface R<K, V : K> : Pair<K, V>`, the type arguments have to be combined per extension,
  like `R<W<X>, X>` of `interface X : W<X>` for `fun <T> Pair<W<T>, T>.f()` — a fixed pool of pairs judges such rows as rejected, which kotlinc accepts.
  A row callable only with `Nothing`, or `Nothing?`, as a type argument of the class counts as rejected (#366),
  but a pool lacking a self-referring type, like `X`, also judges so the rows which only such a declared type makes callable.

- kotlinc does not convert an anonymous function to a suspend function type:
  `k.f(fun(): String = "")` calls the extension taking `() -> String` next to a member taking `suspend () -> CharSequence`,
  which a lambda, a callable reference, and a value of the function type call —
  so probe a parameter of a function type with an anonymous function too, see `acceptsTypeArguments`.

- A lambda, or a function reference, is typed against the type of the parameter of each candidate,
  so a member accepting every value of the type of an extension's parameter may still not take them,
  and kotlinc calls the extension — see `isConvertedTo`, `functionSupertypeOf`.
  Probe a parameter of a function type, or of a functional interface, also with lambdas with and without explicit parameters,
  anonymous functions, with and without a receiver, references to functions of another return type, with a default parameter,
  and suspend ones, and a generic class also on projected and function-typed receivers.

- kotlinc's warning `this extension is shadowed by a member` tells no verdict: it is reported for `fun B.on(f: suspend () -> Unit)`
  next to `fun on(f: Function<Unit>)`, though `b.on(::returningString)` of a suspend function calls the extension — probe the calls (#376).

- A member / extension pair is told as never reaching the extension only as far as its probes go:
  `fun f(x: Comparable<String>)` next to `fun <T : CharSequence> S.f(x: Comparable<T>)` calls the member for a `Comparable<String>`
  and a `Comparable<CharSequence>`, but the extension for a `Comparable<StringBuilder>` —
  probe a type parameter of the extension with an unrelated type satisfying its bounds, and a generic receiver also with `Nothing`.

- K2 judges the override of a Java method by the first path to a generic Kotlin supertype, not by the nearest one, see `typeArgumentGivers`.

- A Java `put(int)` never implements a Kotlin `put(t: T)` given `Integer` by a Java class, whatever its nullability, see `javaGivenPositions` —
  the listing does not follow it for a `@NullMarked` class yet.

- A Java method overriding a Kotlin member, or merged with one, takes its parameter names for kotlinc, so `put(t = 1)` compiles,
  while a named-argument probe of a member merged only of Java methods fails, see `namedByKotlinMembers` —
  a merge dropping the only Kotlin row for an `arg0` one is a regression a sweep must probe by a named call.

- The `inheritedCandidates` rules deciding which merged member is listed are not transitive:
  a rule overriding the rank order let three members beat each other in a circle, and `resolve` returned `null` —
  keep the guard of `listed`, and sweep three-way joins.

- A Java `put(Integer)` overriding a member given `@NotNull Integer` along one path and `@Nullable Integer` along another takes a platform type,
  while the member itself, not overridden, takes the type of its first path, see `isJavaTypeArgument`, `givenPositions`.

- A type argument annotated nullable given through a generic Java class passing its type variable on,
  like `JMid<@Nullable Integer>` of `JMid<T> extends GenericPut<T>`, also one marked by `@NullMarked`, or a Kotlin `KN : JMid<Int?>`,
  still disagrees with a `@NotNull Integer` of another path, so that the Java override takes a platform type —
  a walk of the givers stopping at the Java class misses it, see `typeArgumentGivers`.

- The Gradle test build rejects `abstract class X : J(), JDb` (`J implements GenericPut<Integer>`, `JDb` a default `put(Integer)`) with `must override 'put' because it inherits multiple interface methods`,
  while kotlinc compiles it against the compiled Java classes, so it is no fixture.

- A member deprecated as `HIDDEN` compiles to a synthetic JVM method with no generic signature,
  so its `genericParameterTypes` are erased, like `Object` for a `put(t: T)` — tell its type parameters by the Kotlin metadata, see `typeVariableParameterOf`.

- A Kotlin class joining a member given `Integer` by Java (`Int!`) with one given `Int` by Kotlin, like `Y : JavaBase(), GenericPut<Int>`,
  has one member typed like the one of the more specific return type, or else declared first,
  which a Java `put(Integer)` below overrides when it takes `Int!` — but only when no Java class above declares the method itself,
  like a `put(Integer)` of `JavaBase`, which keeps the one given `Int` a member apart, see `isJoinPickedOver`,
  while a Java interface declaring it does not.

- Probe the setter of a synthetic property by an assignment, from outside the class, and from a subclass:
  `'val' cannot be reassigned` tells a setter not taken, `cannot access` one taken, but not visible —
  and which of two setters of the type is taken only their visibility tells, see `settersInDeclarationOrder`.

- When probing whether a generic function overrides, compile it both with the `override` modifier and without:
  one rejected with the modifier (`overrides nothing`) might be rejected without it too, as an `accidental override` of the same JVM signature,
  while an overload of the same JVM signature might compile, like `where T : Comparable<T>, T : Any` next to `<T : Comparable<T>>`, see `boundsMatch`.

- A synthetic property sweep row judged on a receiver like `X<String>` of a generic class, where a `setValue(String)` sets a `T getValue()`,
  is an artifact of the receiver: the hint of the class listing stays `val` for the type parameter (#372), see `settersInDeclarationOrder` —
  and javac rejects a private `setValue(T)` next to a public one of the same substituted signature (`weaker access privileges`),
  so no fixture shows the private setter of a generic class passed over in a listing of the type parameter.

- Read every error of a probe, not only the first one, and not only the type a mistyped `val x: Marker = …` names:
  `val r: Marker = Thread::name` reports the type `KMutableProperty1<Thread, String!>`, and, on the reference itself,
  `the feature "references to synthetic java properties" is unsupported`.

- kotlinc merges the members the direct supertypes contribute around the first one, so their order tells the listing:
  `X : JImpl<String>(), Function<String, String>, K` (`K` an `apply(x: String?)`) has one `apply`, as the `String!` of `JImpl` is merged with both,
  while `X : Function<String, String>, K, JImpl<String>()` has two, as K2 enhances the JDK method to take `String` —
  and a Kotlin class joining some of them below contributes every member it keeps apart, each by the one it picks,
  a merged member as one only next to the members of its own supertypes — another Kotlin member of the same signature named below the join parts it again —
  while a Java class joining them from different supertypes merges them all.
  `inheritedCandidates` groups the candidates of all the supertypes at once, one per class, telling the joins below only in narrow cases
  (`contributionOrder`, `mergedThroughClasses`): broader rules (grouping by the joining classes, types of JDK interfaces merged by their nullability)
  regressed neighbouring shapes in #339.
  A sweep of joins needs every order of the supertypes, the joins named through Kotlin and Java classes next to other supertypes,
  Kotlin classes one to three levels below the joins, and members with bodies: each of these regressed on its own.

- When probing kotlinc, `val r: Marker = x::apply` names the type of one overload, with no ambiguity, when the class has two,
  the message of an empty concrete subclass tells one abstract member from several (`member:` / `members:`), but not which —
  an override of only one of them does,
  and an `override fun <T> toArray(g: IntFunction<Array<T>>)` compiles also on a class where `x.toArray(g)` does not resolve,
  as Kotlin hides the member of `java.util.Collection` only from calls.

- `reflectedValue` swallows what a conversion of a reflected member throws, dropping the whole member silently,
  like every Java method overriding a Kotlin member taking a mutable collection was, see `reflectedClassName` —
  a member missing from a listing may be a conversion failing, not a rule of the listing.

- kotlinc compiling many probe files in one run reports the errors of the backend, like `inherited platform declarations clash`,
  only when no file of the run has an error of the frontend —
  compile the files which passed again, without the failing ones, until none of them fails.

- A call missing an argument names the parameter of the member kotlinc picks, like the `p0` of a Java method,
  while a named call still takes the name of a Kotlin member merged with it, like `m13(a = arrayOf(""))` —
  so the message does not tell which names a call may use, probe the named call itself.

- An empty concrete subclass of a class joining a hidden member may fail with `inherited platform declarations clash`, not `does not implement`,
  though the joining class itself compiles — such a probe tells nothing of the abstract members, probe them by calls.

- kotlinc's `named argument is prohibited for parameter with an ambiguous name` also counts names no listing shows:
  of hidden and internal members, and of the members a joined override renames — but not along a single path, see `ambiguousNamePositions`.

- A call naming no parameter of the member, like `x.apply(arg0 = "")`, makes kotlinc list every member of the name as a candidate
  (`none of the following candidates is applicable: fun apply(p0: String?): String … fun apply(x: String): CharSequence …`),
  or name the parameter of the single one (`no value passed for parameter 'p0'`) — the probe telling how many members a class has,
  where the null call, the return type and the empty concrete subclass cannot, like below a join of a half-nullable JDK interface (#374).

- The sweep model counting the members rendered without a modifier as abstract can be right by accident:
  a visible member rendered abstract in place of the hidden one kotlinc demands — confirm a changed abstract count
  by overriding each candidate signature in a concrete subclass of its own.

- The class-file path of `bridgeSignatures` cannot be told from its reflection fallback by a test, like one of a class file too new for ASM:
  the bridges are read only for a reflected `Method`, so the declared methods of the class reflect, telling the same bridges,
  while a Java class whose methods do not reflect, like of a parameter class missing, is not described by kotlin-reflect either,
  its member listed from the built-in type instead (#370).

- Between two extensions of the same name, kotlinc calls one on a class over one on a type parameter whenever neither is more specific,
  like `C?.f()` over `<T : S> T.f()`, or `I.f()` over `<T : S> T.f()`, on a `C : S, I`, while a type parameter is more specific than a class
  its bounds are subclasses of, like `<T> T.f() where T : S, T : I` than `S.f()` — two generic ones are ambiguous then, see `ComparedReceiver`.

- When probing kotlinc with Kotlin sources compiled in two runs into one output directory, like a library and its subclasses after javac,
  give the runs different `-module-name`s: the second `META-INF/main.kotlin_module` overwrites the first one,
  so that its top-level functions are `unresolved reference`s.

## Anti-patterns to avoid

- Do not add content to this file that is already discoverable by reading the source or build scripts — that inflates context without adding signal, reducing AI agent task success rates (see [arxiv 2602.11988](https://arxiv.org/abs/2602.11988)).
