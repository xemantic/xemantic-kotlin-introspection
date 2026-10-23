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

## Anti-patterns to avoid

- Do not add content to this file that is already discoverable by reading the source or build scripts — that inflates context without adding signal, reducing AI agent task success rates (see [arxiv 2602.11988](https://arxiv.org/abs/2602.11988)).
