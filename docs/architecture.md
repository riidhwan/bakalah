# Code Architecture

This document describes the current code architecture of the Mihon Android application. The project is organized as a multi-module Gradle build with a layered structure: Android/UI code at the top, domain use cases and contracts in the middle, persistence/network/source integrations below that, and shared utilities in core modules.

## Module Map

The root `settings.gradle.kts` includes these main modules:

- `app`: Android application entry point, feature screens, app-specific services, dependency wiring, download/backup/reader flows, and integration with Android components.
- `domain`: business models, repository interfaces, preferences, services, and interactors/use cases.
- `data`: repository implementations, SQLDelight database access, mappers, paging sources, and release/extension-store network data models.
- `core/common`: shared utilities for networking, storage, preferences, logging, image helpers, coroutines, serialization, and platform support.
- `core/archive`: archive and EPUB/ZIP reading primitives.
- `core-metadata`: metadata models for formats such as Tachiyomi metadata and ComicInfo.
- `presentation-core`: reusable Compose UI components, theme primitives, icons, and presentation utilities.
- `source-api`: multiplatform source contracts used by remote/local content providers.
- `source-local`: local content source implementation, file-system access, archive detection, and local cover handling.
- `i18n`: shared localized resources generated through Moko resources.
- `telemetry`: telemetry abstraction with `noop` and Firebase-backed source sets.
- `macrobenchmark`: startup and baseline-profile benchmarks.

## Layering and Dependency Direction

The intended dependency direction is mostly top-down:

```text
app
  -> presentation-core
  -> data
  -> domain
  -> source-api / source-local
  -> core/common / core/archive / core-metadata / i18n / telemetry
```

`domain` defines stable business contracts and depends only on low-level shared modules such as `core/common` and `source-api`. `data` depends on `domain` so it can implement repository interfaces. `app` depends on all feature and infrastructure modules because it assembles the final Android application.

When adding new behavior, prefer this order:

1. Put pure business rules, repository interfaces, and use cases in `domain`.
2. Put database/network-backed implementations in `data`.
3. Put Android platform coordination and screen state in `app`.
4. Put reusable UI building blocks in `presentation-core`, not directly in feature packages.

## Application Layer

The `app` module contains the Android application shell and most feature-level UI. Important package areas include:

- `eu.kanade.tachiyomi.ui`: screen models, tabs, activities, reader, library, local, browse, history, settings, and manga detail flows.
- `eu.kanade.presentation`: Compose screen content and UI components that are feature-specific.
- `eu.kanade.tachiyomi.data`: app-owned data services such as downloads, backups, cache, notifications, and trackers.
- `eu.kanade.tachiyomi.source`: Android source manager integration.
- `eu.kanade.tachiyomi.di`: dependency registration.
- `mihon.feature.*`: newer Mihon feature packages such as migration and upcoming views.

Feature code generally follows a pattern where screen models coordinate domain interactors, repositories, preferences, and app services, while Compose functions render state and dispatch user events back to the screen model.

## Domain Layer

The `domain` module is the boundary for business operations. It contains:

- Repository interfaces such as `MangaRepository`, `ChapterRepository`, `HistoryRepository`, `SourceRepository`, and `TrackRepository`.
- Interactors such as `GetManga`, `GetChaptersByMangaId`, `FetchInterval`, and `SetMangaCategories`.
- Domain models for manga, chapters, categories, history, downloads, sources, tracking, releases, storage, and backup behavior.
- Preference service classes such as `LibraryPreferences`, `DownloadPreferences`, and `StoragePreferences`.

Interactors should stay small and focused. They are the preferred place for reusable business workflows because they keep UI code from reaching directly into persistence details.

## Data Layer and Persistence

The `data` module implements domain repositories and owns SQLDelight database definitions.

Key areas:

- `data/src/main/java/tachiyomi/data/*`: repository implementations and mappers.
- `data/src/main/sqldelight/tachiyomi/data`: table definitions such as `mangas.sq`, `chapters.sq`, `categories.sq`, `history.sq`, and `sources.sq`.
- `data/src/main/sqldelight/tachiyomi/view`: query views used for library and history screens.
- `data/src/main/sqldelight/tachiyomi/migrations`: numbered schema migrations.

SQLDelight generates the `tachiyomi.data.Database` API. `AppModule` creates the Android SQL driver and registers `Database` with adapters for custom column types such as date values, genre lists, and update strategies.

Repository implementations should map generated database rows into domain models through mapper files like `MangaMapper.kt`, `HistoryMapper.kt`, and `TrackMapper.kt`. Avoid leaking SQLDelight generated types into the domain or UI layers.

## Dependency Injection

The project uses Injekt for dependency registration. The main modules are:

- `AppModule`: registers the application instance, SQLDelight driver/database, JSON/XML/ProtoBuf serializers, caches, network helper, source manager, extension manager, download services, tracker manager, image saver, storage services, and local source helpers.
- `PreferenceModule`: registers `PreferenceStore` and typed preference services.

Dependencies are resolved through Injekt in app/runtime code. New shared services should be registered in the smallest appropriate module and exposed through interfaces when they cross architectural boundaries.

## Source and Extension Architecture

`source-api` defines source contracts such as `Source`, `CatalogueSource`, `HttpSource`, `ParsedHttpSource`, source models, filters, and page metadata. It is multiplatform, with common contracts in `commonMain` and Android-specific utilities in `androidMain`.

`source-local` implements local library support. It handles local file-system discovery, archive formats, local covers, and local metadata extraction.

The `app` module's `AndroidSourceManager` and `ExtensionManager` coordinate installed extensions, source discovery, source preferences, and runtime Android integration. Domain code should depend on source abstractions rather than concrete extension-loading details.

## Content Vault Architecture

The Content Vault is a separate user-owned content feature with dedicated domain models, SQLDelight index tables, app services, and UI surfaces. It keeps remote Vault Catalogue manifests, local Vault Index rows, Local Content Cache files, and Vault Reading State separate from Library, Local Source, Downloads, Backup, and trackers.

The detailed current design is documented in `docs/content-vault-architecture.md`.

## UI Architecture

UI is primarily Jetpack Compose, with some Android views and activities where needed for legacy or platform-specific flows. Reusable design components belong in `presentation-core`, including theme colors, typography, material wrappers, common list/grid components, screens, and icons.

Feature-specific UI belongs in `app/src/main/java/eu/kanade/presentation/*` or the relevant `mihon/feature/*` package. Keep composables mostly stateless: pass state in, emit events out, and let screen models own loading, mutation, and navigation decisions.

Bakalah currently has no app widget module; recent-updates widgets were removed with the Recent Updates Surface.

## Networking and Serialization

Networking utilities live in `core/common` and app-specific network setup lives in `app`. `NetworkHelper` is registered by `AppModule` and provides app-wide HTTP support. Serialization is standardized through registered `Json`, `XML`, and `ProtoBuf` instances so callers share behavior such as unknown-key tolerance and XML policy.

When adding new remote models, keep DTOs close to their integration area and map them into domain models before exposing them broadly.

## Preferences and Configuration

Preferences use `PreferenceStore` from `core/common`, backed by `AndroidPreferenceStore` in the app. Typed preference wrappers live across domain and app-specific packages:

- Domain-level examples: library, downloads, backup, source, track, storage, UI, and base preferences.
- App-level examples: network, security, privacy, and reader preferences.

Prefer adding typed preference accessors over reading raw keys throughout feature code.

## Background Work and Long-Running Flows

The app contains long-running workflows for metadata refreshes, downloads, backups, restore, notifications, extension installation, tracking sync, and reader page loading. These are mostly in `app/src/main/java/eu/kanade/tachiyomi/data` and related UI packages.

Keep these flows explicit about ownership:

- Domain interactors decide what should happen.
- App services coordinate Android jobs, notifications, storage, and lifecycle.
- Data repositories persist state and expose query results.
- UI observes state and sends user actions.

## Testing Strategy

Unit tests live under each module's `src/test/java`. Current examples include domain tests such as `FetchIntervalTest`, `LibraryFlagsTest`, `MissingChaptersTest`, and core utility tests such as `TallImageSplitCalculatorTest`.

Use JUnit Jupiter, Kotest assertions, MockK, and coroutine test utilities where appropriate. Favor domain and mapper tests for business logic. For database-impacting changes, update SQLDelight migrations and run:

```shell
./gradlew testDebugUnitTest
./gradlew verifySqlDelightMigration
```

Run `./gradlew spotlessCheck` before opening a pull request.

## Adding New Features

For a new feature, start by identifying the layer that owns the change:

1. Add or extend domain models, repository contracts, and interactors in `domain`.
2. Implement persistence or network-backed behavior in `data`.
3. Register new app services or preferences through DI if runtime wiring is needed.
4. Add feature state and UI in `app`, reusing `presentation-core` components.
5. Add focused tests for business rules, repository mapping, and migrations.

Avoid placing reusable business logic directly in Compose functions or Android activities. If multiple screens need the same operation, promote it to a domain interactor or a shared app service.
