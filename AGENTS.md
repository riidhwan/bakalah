# Repository Guidelines

## Project Structure & Module Organization

This is a multi-module Kotlin/Android Gradle project. Key modules are `app/` for the Android app, `domain/` for business contracts and interactors, `data/` for repositories and SQLDelight, `core/*` for shared primitives, `presentation-*` for reusable UI and widgets, `source-*` for source APIs/local source support, `i18n/` for translations, and `macrobenchmark/` for benchmarks. Production Kotlin is under `src/main/java` or `src/*Main/kotlin`; tests are under `src/test/java`; Android resources are under `src/main/res`.

## Build, Test, and Development Commands

- `./gradlew assembleDebug` builds a local debug APK.
- `./gradlew testDebugUnitTest` runs JVM unit tests for the debug variant.
- `./gradlew verifySqlDelightMigration` validates database migrations.
- `./gradlew spotlessCheck` checks formatting; `./gradlew spotlessApply` fixes supported formatting issues.
- `./gradlew detekt` runs advisory Kotlin code-smell analysis.
- `scripts/check-presentation-screens` warns about bloated Compose screen files and blocks new oversized `*Screen.kt` files.
- `./gradlew clean` removes Gradle build outputs.

Use Android Studio with the project Gradle wrapper for day-to-day development and device/emulator testing.

## Coding Style & Naming Conventions

Kotlin uses official Kotlin style (`kotlin.code.style=official`), Spotless with ktlint, and advisory Detekt checks for code smells. Keep files newline-terminated, avoid trailing whitespace, follow existing package boundaries (`eu.kanade.tachiyomi`, `tachiyomi`, `mihon`), and prefer module-local patterns. For detailed conventions and antipatterns, read `docs/code-style.md`.

Keep Compose `*Screen.kt` files thin. Split independently reviewable UI areas such as headers, list rows, dialogs, sheets, metadata editors, and control clusters into the feature's `components` subpackage before a screen file grows into a multi-feature container. Run `scripts/check-presentation-screens` when making presentation changes.

## Testing Guidelines

Tests use JUnit Jupiter, Kotest assertions, MockK, and coroutine test utilities. Place tests in the relevant module's `src/test/java`, mirroring production packages. Name tests after the unit under test, such as `FetchIntervalTest`.

Treat unit-testability as a design requirement for new and changed code. Prefer constructor-injected collaborators, small pure helpers, deterministic time/randomness seams, and fakeable boundary interfaces for network, storage, database, decoder, and Android runtime work. Avoid hiding meaningful behavior in private methods that can only be exercised through a device or full integration flow.

Add focused tests whenever reasonably possible for new behavior, bug fixes, workflow services, mappers, policies, and edge cases. If tests are not practical in the same change, state the concrete blocker and residual risk in the handoff or final summary. Run `./gradlew testDebugUnitTest` and `./gradlew detekt` before submitting Kotlin changes.

## Commit & Pull Request Guidelines

Use `$pr` for Bakalah-specific branch, commit, changelog, push, pull request, and release PR preparation conventions.

## Security & Configuration Tips

Do not commit personal credentials or replacement service keys. Bakalah has its own app name, icon, `applicationId`, updater target, and no Mihon Firebase configuration; keep future identity-sensitive changes aligned with that fork identity.

## Agent-Specific Instructions

### Compact Operations

For command-heavy work, follow `$compact-operations` even when another skill is active. Do not start large-repo exploration with broad `rg -n PATTERN .`; discover candidate files/modules first with `rg --files`, targeted roots, `rg -l`, or counts. Avoid watch modes, full logs, full JSON blobs, and repeated noisy polling unless needed. For Gradle/test/build commands, use `scripts/gradlew-compact` so full output goes to a gitignored temporary log and only the tail plus log path returns unless more output is needed.

Before making non-trivial code changes, read the relevant docs:

- Read `docs/architecture.md` when changing module boundaries, dependency direction, persistence, DI, source/extension behavior, UI architecture, or feature placement.
- Read `docs/code-style.md` when writing or reviewing Kotlin, Compose, coroutine/Flow code, SQLDelight changes, tests, or shared services.

Keep edits aligned with those documents. If implementation needs to diverge, explain the reason in the change summary.

When editing Kotlin or Kotlin Gradle script files, inspect Detekt findings for the affected scope and fix findings in files you materially changed. If a changed file has unrelated historical Detekt findings that would materially expand the task, report the exact findings and why they should be deferred, then ask the user for a decision before leaving them unresolved.

When making code or configuration changes, also review `AGENTS.md` and the relevant files under `docs/`. Update them in the same change whenever commands, architecture, module ownership, workflow expectations, branding, release behavior, or coding guidance would otherwise become stale.

When the user asks to split work into GitHub issues, draft the proposed issue decomposition first and get explicit user approval before creating or syncing issues on GitHub. The draft should include parent/sub-issue boundaries, scope, out-of-scope notes, and any native dependency relationships that will be created. Each issue body must be self-contained enough for a separate agent with no conversation context to pick it up: include the outcome, parent context, relevant decisions/docs, concrete files or areas likely involved, explicit exclusions, dependencies, and verification commands.
