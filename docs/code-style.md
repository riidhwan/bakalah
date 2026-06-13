# Code Style and Antipattern Guide

This guide documents style expectations and common antipatterns for this repository. Automated formatting is handled by Spotless and ktlint, but code quality here also depends on preserving module boundaries, keeping state ownership clear, and avoiding Android-specific shortcuts in shared layers.

## Formatting

Use the Gradle wrapper for all formatting checks:

```shell
./gradlew spotlessCheck
./gradlew spotlessApply
```

Spotless applies ktlint to `src/**/*.kt` and `*.kts`, trims trailing whitespace, and enforces final newlines. XML files under `src/**/*.xml` are also checked for trailing whitespace and final newlines.

The project uses official Kotlin style via `kotlin.code.style=official`. Do not manually fight ktlint. If a layout or expression becomes hard to read after formatting, simplify the code rather than adding local formatting workarounds.

## Static Analysis

Detekt tracks Kotlin code smells across project Kotlin sources, tests, Kotlin Gradle scripts, and build logic. The shared configuration and baseline live under `config/detekt/`.

Run Detekt through the compact Gradle wrapper during command-heavy work:

```shell
scripts/gradlew-compact detekt
```

Detekt is advisory for now: existing findings are captured in the committed baseline, and Detekt tasks ignore failures so release assembly is not blocked by historical debt. Treat new findings in files you materially change as part of the change. If a changed file contains unrelated historical findings that would materially expand the task, explain the exact findings and why deferring them is appropriate, then ask the maintainer before leaving them unresolved.

The intended direction is to tighten Detekt into a hard gate once the baseline has shrunk enough that failures are actionable instead of noisy.

## Kotlin Conventions

Use idiomatic Kotlin and prefer explicit, narrow APIs.

- Use `val` unless mutation is required.
- Keep nullability explicit and meaningful; avoid `!!` outside truly impossible states.
- Prefer expression bodies for small pure functions, but use block bodies when control flow or error handling is non-trivial.
- Prefer sealed interfaces/classes for finite UI states, events, dialogs, and result types.
- Keep extension functions close to the domain they extend. Avoid broad extension files that become dumping grounds.
- Use named arguments when calls have repeated values, booleans, or unclear parameter meaning.
- Avoid premature abstraction. Extract helpers only when they clarify intent or remove real duplication.

Example:

```kotlin
val chapters = getChaptersByMangaId.await(mangaId)
    .filter { it.read.not() }
    .sortedBy { it.sourceOrder }
```

Prefer this over mutable temporary lists unless mutation is needed for performance or API compatibility.

## Naming

Follow existing naming patterns:

- Classes, objects, composables, and screen models: `PascalCase`.
- Functions, properties, local variables, and parameters: `camelCase`.
- Constants: `UPPER_SNAKE_CASE` when used as true constants.
- Tests: name after the unit under test, for example `FetchIntervalTest`.
- Interactors: use verb or verb-noun names, such as `GetManga`, `UpdateChapter`, or `SetMangaCategories`.
- Repository interfaces: `MangaRepository`; implementations: `MangaRepositoryImpl`.
- Mappers: use focused names such as `MangaMapper.kt`, `HistoryMapper.kt`, or `TrackMapper.kt`.

Use descriptive names over abbreviations. Short names like `id`, `url`, and `db` are fine when the scope is small and obvious.

## Module Boundaries

Respect the architecture described in `docs/architecture.md`.

- Put business contracts, models, preferences, and interactors in `domain`.
- Put repository implementations, SQLDelight access, mappers, and paging sources in `data`.
- Put Android runtime wiring, jobs, activities, screen models, and app services in `app`.
- Put reusable UI primitives in `presentation-core`.
- Put feature-specific Compose UI in `app/src/main/java/eu/kanade/presentation/*` or `mihon/feature/*`.
- Put source contracts in `source-api` and local source implementation in `source-local`.

Antipatterns:

- Calling SQLDelight generated APIs directly from UI or domain code.
- Adding Android `Context` dependencies to domain interactors.
- Placing reusable business logic inside a composable because that was the nearest call site.
- Creating cross-module dependencies for one helper function instead of moving the helper to an appropriate shared module.

## Workflow Service Boundaries

App services may orchestrate a long-running workflow, but they should not become catch-all owners for every helper needed by that workflow. Treat file length and long methods as review signals, not as the root problem: the root problem is usually mixed responsibility.

For services that coordinate imports, publishing, transfers, refreshes, backups, restore, downloads, or other multi-step runtime work:

- Keep the public service focused on workflow sequencing, progress, cancellation, and result mapping.
- Extract stable sub-responsibilities into focused collaborators or pure helper files when they can be named independently, tested independently, or reused by another workflow.
- Keep source scanning, staging, archive construction, manifest mutation, transport path building, rollback, ordering policy, and result-detail encoding separate when more than one of those responsibilities appears in the same service.
- Prefer behavior-preserving extraction before redesigning shared workflow semantics.
- Add tests around extracted pure policies and boundary collaborators before sharing them across workflows.

Antipatterns:

- Large app services where most private functions are unrelated helper domains.
- Numeric file-size fixes that split code without improving ownership.
- Shared helpers that hide workflow-specific policy behind broad names like `Manager`, `Handler`, or `Processor`.
- Adding custom lint for known debt before the documented target shape is represented in code.

When custom enforcement is added, prefer warning thresholds before CI failures. A future quality check may warn on large Kotlin files or functions as prompts to inspect responsibility boundaries, with reasoned suppressions for generated tables, sealed result definitions, DSL/config files, or deliberately centralized Android integration points.

## Compose and UI Style

Compose code should make state flow clear.

- Keep composables mostly stateless: pass state in and emit callbacks out.
- Use screen models to coordinate loading, mutation, preferences, and domain calls.
- Use `remember` for local derived objects, not as a general cache for business data.
- Use `rememberSaveable` only for UI state that should survive recreation.
- Keep `LaunchedEffect` keys precise. Avoid `LaunchedEffect(Unit)` unless the effect is intentionally one-time for that composition.
- Use existing components from `presentation-core` before creating new UI primitives.
- Keep feature components near their feature unless they are reusable across multiple features.

Antipatterns:

- Starting repository writes directly from deeply nested composables.
- Passing a whole screen model into reusable UI components when callbacks would be clearer.
- Duplicating material wrappers or theme logic already available in `presentation-core`.
- Hiding expensive computation inside composable recomposition paths.

## Coroutines and Flow

Use coroutines and Flow to model asynchronous work and observable state.

- Prefer structured concurrency tied to screen-model or service lifecycles.
- Expose immutable `StateFlow` or `Flow` from state owners; keep `MutableStateFlow` private unless local model state requires otherwise.
- Use coroutine test utilities for time-sensitive or suspend logic.
- Keep blocking I/O off the main thread.
- Prefer domain interactors for reusable suspend operations.

Antipatterns:

- Launching global coroutines without ownership.
- Collecting flows indefinitely from a scope that outlives the feature.
- Converting flows to mutable lists early and manually keeping them synchronized.
- Swallowing coroutine cancellation with broad `catch (e: Exception)` blocks.

## Data and SQLDelight

Database-facing code belongs in `data`.

- Keep SQL in `.sq` files and migrations in `data/src/main/sqldelight/tachiyomi/migrations`.
- Add migrations for schema changes and run `./gradlew verifySqlDelightMigration`.
- Map generated database types into domain models before crossing module boundaries.
- Keep mapper functions small, predictable, and covered by tests when behavior is non-trivial.
- Prefer repository methods that reflect domain operations instead of exposing raw query details.

Antipatterns:

- Returning generated SQLDelight row types from domain repositories.
- Encoding business rules only in SQL when they are reused elsewhere.
- Updating schemas without numbered migrations.
- Mixing network DTOs, database rows, and domain models in the same public API.

## Dependency Injection and Services

Runtime dependencies are registered through Injekt modules in `app`.

- Register app-wide services in `AppModule` or a focused module if one exists.
- Register typed preferences in `PreferenceModule`.
- Prefer constructor parameters for dependencies and keep service construction centralized.
- Expose interfaces when a dependency crosses module boundaries or needs test substitution.

Antipatterns:

- Creating new singleton access patterns outside Injekt.
- Resolving dependencies deep inside pure functions.
- Adding service initialization side effects to unrelated modules.
- Passing Android services into domain code instead of wrapping them in app-level services.

## Error Handling and Logging

Make failures explicit at the right layer.

- Let domain operations return meaningful results or throw well-understood exceptions.
- Handle user-facing errors in screen models or app services where UI messages and retry behavior can be decided.
- Log useful context, not sensitive data.
- Avoid broad exception handling unless the caller has a clear fallback.

Antipatterns:

- Empty `catch` blocks.
- Logging credentials, tokens, cookies, or full backup payloads.
- Turning every failure into `null` and losing the reason.
- Showing low-level exception messages directly to users without filtering.

## Testing Expectations

Add tests where behavior is easy to regress.

- Domain interactors should have focused unit tests for business rules.
- Utility functions in `core/common` should be tested when edge cases matter.
- Mapper tests are useful when database rows, network DTOs, or flags are transformed.
- For schema changes, include migrations and verify them.

Run:

```shell
./gradlew testDebugUnitTest
./gradlew verifySqlDelightMigration
./gradlew spotlessCheck
./gradlew detekt
```

Antipatterns:

- Testing only implementation details while ignoring observable behavior.
- Adding broad mocks when a small fake or real value object would be clearer.
- Leaving time, dispatcher, or random behavior uncontrolled in tests.
- Skipping tests for flag parsing, sorting, filtering, migration, or interval logic.

## Review Checklist

Before submitting a change, check:

- The code is in the right module.
- Public APIs expose domain concepts, not persistence or UI details.
- Compose code receives state and emits events instead of owning business work.
- New dependencies are justified and registered centrally.
- SQL schema changes include migrations.
- Tests cover meaningful logic and edge cases.
- `spotlessCheck`, `detekt`, and relevant tests pass.
