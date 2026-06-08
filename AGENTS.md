# Repository Guidelines

## Project Structure & Module Organization

This is a multi-module Kotlin/Android Gradle project. Key modules are `app/` for the Android app, `domain/` for business contracts and interactors, `data/` for repositories and SQLDelight, `core/*` for shared primitives, `presentation-*` for reusable UI and widgets, `source-*` for source APIs/local source support, `i18n/` for translations, and `macrobenchmark/` for benchmarks. Production Kotlin is under `src/main/java` or `src/*Main/kotlin`; tests are under `src/test/java`; Android resources are under `src/main/res`.

## Build, Test, and Development Commands

- `./gradlew assembleDebug` builds a local debug APK.
- `./gradlew assembleRelease -Penable-updater` builds the CI-style release APK.
- `./gradlew testDebugUnitTest` runs JVM unit tests for the debug variant.
- `./gradlew verifySqlDelightMigration` validates database migrations.
- `./gradlew spotlessCheck` checks formatting; `./gradlew spotlessApply` fixes supported formatting issues.
- `./gradlew clean` removes Gradle build outputs.

Use Android Studio with the project Gradle wrapper for day-to-day development and device/emulator testing.
For release preparation, follow `docs/release-process.md`.

## Coding Style & Naming Conventions

Kotlin uses official Kotlin style (`kotlin.code.style=official`) and Spotless with ktlint. Keep files newline-terminated, avoid trailing whitespace, follow existing package boundaries (`eu.kanade.tachiyomi`, `tachiyomi`, `mihon`), and prefer module-local patterns. For detailed conventions and antipatterns, read `docs/code-style.md`.

## Testing Guidelines

Tests use JUnit Jupiter, Kotest assertions, MockK, and coroutine test utilities. Place tests in the relevant module's `src/test/java`, mirroring production packages. Name tests after the unit under test, such as `FetchIntervalTest`. Run `./gradlew testDebugUnitTest` before submitting.

## Commit & Pull Request Guidelines

Recent commits use short, imperative subjects such as `Add vertical chapter navigator` or `Drop kotlinx-collections-immutable usage`. Keep commits focused.

Pull requests should include a summary, linked issue when applicable, testing performed, and a brief self-review. For UI changes, include screenshots and verify relevant themes and tablet mode. CI expects formatting, unit tests, SQLDelight migration checks, and release assembly to pass.

Release versions use `release/MAJOR.MINOR.PATCH` branches merged into `main`; automation creates annotated `vMAJOR.MINOR.PATCH` tags. The branch and tag versions must match the Android `versionName`, `versionCode` must increase for public releases, and GitHub Releases are created as drafts for manual verification before publishing.

## Security & Configuration Tips

Do not commit personal credentials or replacement service keys. Bakalah has its own app name, icon, `applicationId`, updater target, and no Mihon Firebase configuration; keep future identity-sensitive changes aligned with that fork identity.

## Agent-Specific Instructions

### Compact Operations

For command-heavy work, follow `$compact-operations` even when another skill is active. Do not start large-repo exploration with broad `rg -n PATTERN .`; discover candidate files/modules first with `rg --files`, targeted roots, `rg -l`, or counts. Avoid watch modes, full logs, full JSON blobs, and repeated noisy polling unless needed. For Gradle/test/build commands, use `scripts/gradlew-compact` so full output goes to a gitignored temporary log and only the tail plus log path returns unless more output is needed.

Before making non-trivial code changes, read the relevant docs:

- Read `docs/architecture.md` when changing module boundaries, dependency direction, persistence, DI, source/extension behavior, UI architecture, or feature placement.
- Read `docs/code-style.md` when writing or reviewing Kotlin, Compose, coroutine/Flow code, SQLDelight changes, tests, or shared services.

Keep edits aligned with those documents. If implementation needs to diverge, explain the reason in the change summary.

When making code or configuration changes, also review `AGENTS.md` and the relevant files under `docs/`. Update them in the same change whenever commands, architecture, module ownership, workflow expectations, branding, release behavior, or coding guidance would otherwise become stale.

Do not create commits, push branches, or open pull requests unless the user explicitly asks for that Git/GitHub operation. It is fine to stage files only when preparing a user-requested commit.

When the user asks to split work into GitHub issues, draft the proposed issue decomposition first and get explicit user approval before creating or syncing issues on GitHub. The draft should include parent/sub-issue boundaries, scope, out-of-scope notes, and any native dependency relationships that will be created. Each issue body must be self-contained enough for a separate agent with no conversation context to pick it up: include the outcome, parent context, relevant decisions/docs, concrete files or areas likely involved, explicit exclusions, dependencies, and verification commands.
