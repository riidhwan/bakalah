# Content Vault Architecture

This document describes the current technical architecture of Bakalah's Content Vault implementation. It complements the architectural decision in `docs/adr/0004-model-content-vault-as-separate-feature.md`.

## Architectural Intent

The Content Vault is a separate feature for user-owned manga content stored in WebDAV-accessible remote storage. It is not the Library, not Local Source, not Downloads, not Backup, and not a blind folder sync.

The core split is:

- Remote Vault Catalogue: authoritative vault-owned records and content pointers stored as JSON manifests under the Vault Root.
- Local Vault Index: device-local SQLDelight tables used for browsing, filtering, cache state, transfer state, and offline access to the last refreshed catalogue.
- Local Content Cache: app-managed cached CBZ chapter files used for reading on the current device.
- Vault Reading State: device-local read progress, bookmarks, and last-read timestamps, separate from Library history and tracking.

The architecture deliberately allows a manga or chapter to exist in the Vault Catalogue even when the readable chapter file is not cached on the device.

## Module Ownership

The implementation follows the repository layering in `docs/architecture.md`.

### Domain

`domain/src/main/java/tachiyomi/domain/vault` owns stable vault contracts and business concepts:

- `model`: vault identities, manifests, manga, chapters, manga covers, chapter thumbnails, labels, cache states, reading state, import plans, revisions, transfer jobs, and WebDAV config.
- `repository/VaultRepository.kt`: the persistence boundary used by interactors, app services, and screen models.
- `interactor`: pure or mostly pure use cases such as catalogue refresh construction, import planning, revision checks, and simple read/write operations.
- `service/ContentVaultPreferences.kt`: typed preferences for configured WebDAV storage, configured vault identity, private credentials, and local cache size limit.

Domain code does not perform Android storage, WebDAV, or SQLDelight work directly.

### Data

`data/src/main/java/tachiyomi/data/vault` and `data/src/main/sqldelight/tachiyomi/data/vault.sq` implement the local Vault Index:

- `VaultRepositoryImpl` implements `VaultRepository`.
- `VaultMapper` maps SQLDelight rows into domain models.
- `vault.sq` defines dedicated vault tables that stay separate from Library manga and chapter tables.

`refreshCatalogue` is the main atomic index replacement workflow. It upserts the content vault record, labels, manga, covers, chapter thumbnails, chapter rows, manga-label links, and manifest snapshots in one transaction.

### App

`app/src/main/java/eu/kanade/tachiyomi/data/vault` owns Android/runtime services, organized by responsibility:

- `setup`: `ContentVaultSetupService` validates WebDAV configuration, rejects mixed-use roots, initializes empty roots, connects existing roots, and persists the configured vault identity.
- `refresh`: `VaultCatalogueRefreshService` downloads root and per-manga manifests, validates identity/layout compatibility, builds a domain refresh payload, and updates the local index. `AddToVaultIndexRefresher` shares per-success index refresh mechanics for Add to Vault workflows.
- `localimport`: `LocalVaultImportJob`, `LocalVaultImportService`, `LocalVaultChapterPublisher`, Local Source scanning, Local-to-Vault directory chapter staging, ordering policy, and local import failure-detail helpers.
- `capture`: `LibraryVaultCaptureJob`, `LibraryVaultCaptureService`, `LibraryVaultChapterPublisher`, and `LibraryVaultChapterStager` capture selected chapters from source-backed Library manga through capture-owned staging, publish canonical CBZ content one chapter at a time, record `CAPTURE_PUBLISH` job state, and report partial results.
- `add`: `AddToVaultJobRunner` shares durable Vault Import Request and WorkManager plumbing between Local-to-Vault Import and Library-to-Vault Capture without sharing workflow result types or publishing policy. `AddToVaultNotifier` and Add-to-Vault progress types own shared foreground progress notification mechanics.
- `staging`: shared Add-to-Vault file mechanics such as CBZ entry writing/validation, flat numbered page entry names, `UniFile` digest/request-body helpers, and temporary file cleanup. Workflow-specific scanning and staging policy remains in `localimport` and `capture`.
- `transfer`: `VaultTransferService` performs staged uploads/downloads, integrity verification, transfer job state updates, and cache state updates. `AddToVaultTransferFinalizer` shares workflow-neutral transfer job finalization.
- `remote`: `VaultRemoteStorage` owns low-level remote transport operations such as list, read, write, delete, directory creation, staged promotion, and shared remote path construction. `remote.webdav` contains the WebDAV implementation and path handling.
- `webdav`: temporary compatibility adapters over `VaultRemoteStorage` preserve existing nullable and boolean call-site contracts during migration.
- `publishing`: `VaultContentUploader`, `VaultManifestPublishTransaction`, `VaultMetadataPublishService`, `VaultCoverPublishService`, Vault Chapter Thumbnail publishing, and `VaultMangaDeletionService` publish catalogue/content mutations and refresh the local index afterward.
- `cache`: `VaultCachePolicyService` creates cache paths, marks opened cached chapters, evicts chapters, and enforces the local cache size limit.
- `reader`: `VaultReaderOpenService` verifies cached chapters or performs cache-first download before reader launch, and `ActiveVaultReaderSessions` tracks reader-adjacent deletion guards.
  Vault label sensitivity is catalogue-owned metadata, while the user's include-sensitive Vault Destination setting is device-local.

`app/src/main/java/eu/kanade/tachiyomi/ui/vault` owns screen models and navigation. `app/src/main/java/eu/kanade/presentation/vault` owns Compose rendering.

## Remote Vault Layout

The remote Vault Root is app-owned. A valid root contains `content-vault.json`, the root manifest defined by `VaultRootManifest`.

Current layout constants live in `VaultManifest.kt`:

- `CONTENT_VAULT_APP_ID = "bakalah-content-vault"`
- `CURRENT_VAULT_LAYOUT_VERSION = 5`
- `ROOT_VAULT_MANIFEST_NAME = "content-vault.json"`

Vault Chapter Thumbnail-aware writes require layout version 5 because chapter records can reference optional vault-owned thumbnail assets. Existing version 4 manifests can be read by treating missing chapter thumbnails as absent; touched manifests are upgraded when written.

The layout is hybrid:

- Root manifest: app marker, content vault identity, display name, layout version, root revision, writer id, summary counts, timestamps, and per-manga manifest pointers.
- Manga manifest: manga identity, metadata, collection state, labels, cover reference, chapter records, manga-level provenance, optional chapter-level provenance, revision, and timestamps.
- Content files: chapter CBZ files and cover assets referenced by manifest paths under `content/...`.

Current import paths follow this shape:

```text
content-vault.json
manga/<manifest-id>.json
content/<manga-identity>/<chapter-identity>/<chapter-file>.cbz
content/<manga-identity>/cover/<cover-identity>.<extension>
content/<manga-identity>/<chapter-identity>/thumbnail/<thumbnail-identity>.jpg
```

The root and manga manifests are the authoritative catalogue. Remote folder listing is used for setup and targeted reads/writes, not as the browse model.

## Manifest Compatibility and Identity

`VaultManifestCodec` decodes root and manga manifests and rejects:

- non-vault manifests where `app` is not Bakalah's Content Vault app id
- older unsupported layout versions
- newer unsupported layout versions
- malformed JSON

Every Content Vault has a stable `ContentVaultIdentity`. Setup, refresh, import, metadata publish, cover publish, deletion, and reader-adjacent workflows compare the configured local identity against the remote root identity before reusing local state or publishing changes.

Manga manifests are also checked against their root pointers. A manga manifest must match both the root vault identity and the pointer's manga identity.

## Local Vault Index

The local index is stored in dedicated SQLDelight tables:

- `content_vaults`: configured/refreshed vault metadata and root revision.
- `vault_mangas`: vault manga metadata, sort key, cover pointer, and manga revision.
- `vault_chapters`: chapter metadata, ordering, remote content path, content format, size, checksum, optional thumbnail pointer, and chapter revision.
- `vault_labels` and `vault_manga_labels`: vault-owned organization labels.
- `vault_covers`: current cover metadata and remote cover path.
- `vault_chapter_thumbnails`: current chapter thumbnail metadata and remote thumbnail path.
- `vault_reading_state`: device-local read/bookmark/page state.
- `vault_chapter_cache_state`: device-local cache state, cache path, verified integrity, open timestamps, and failure reason.
- `vault_transfer_jobs`: visible upload/download/cache/publish job state, including source-backed capture result summaries.
- `vault_import_target_hints`: device-local mapping from a Manga Detail Screen manga to the Vault Manga it was previously added to, scoped to the configured Content Vault identity and guarded by manga source identity.
- `vault_manifest_snapshots`: raw fetched manifest bodies retained for diagnostics and index rebuilding.

Chapter-level provenance is remote catalogue metadata. It does not require dedicated SQL columns until a user-facing query or display needs it; manifest snapshots retain raw provenance for diagnostics.

Normal Vault browsing reads the local index through `VaultRepository`. It does not query WebDAV live. Catalogue refresh indexes Vault Chapter Thumbnail metadata but does not download thumbnail image bytes.

## Catalogue Refresh

Catalogue refresh is the bridge from remote authority to local index:

1. `VaultCatalogueRefreshService` reads `content-vault.json`.
2. It validates layout compatibility and configured identity.
3. It downloads each manga manifest referenced by the root manifest.
4. `BuildVaultCatalogueRefresh` validates pointer/manifest identities and converts manifests into domain index rows.
5. `VaultRepository.refreshCatalogue` transactionally replaces stale index rows, upserts current rows, and stores manifest snapshots.

Only manga referenced by the current remote root manifest remain represented in the local index after catalogue refresh. The local index does not keep a separate collection-state flag for deleted manga.

## Local-to-Vault Import

Local-to-Vault Import starts from an existing Local Manga detail screen.
The Local Manga detail screen shows the current Import Target Hint, an unlinked target setup affordance, or an unconfigured-vault setup affordance under the manga title using the same icon-and-text metadata-row style as the author/artist rows, and treats target changes as manga-scoped state separate from selected chapter actions. The row text is the target Vault Manga title when linked, "Link vault target" when unlinked, "Vault target unavailable" when stale, and "Set up content vault" when the Content Vault is unconfigured. Local-to-Vault Import does not use a top-menu "Import to Vault" action; selected chapter import starts from Local Manga chapter multi-selection through an "Add to Vault" action, including the single selected chapter case. If selected chapter import starts with a valid linked target, target setup is skipped and the flow proceeds directly to any required Vault Chapter Replacement confirmation. If selected chapter import starts before a valid target is linked, or with a stale target hint, the flow routes through target setup while preserving the selected chapters, including after intentional unlinking. If it starts before the Content Vault is configured, the flow routes to vault setup while preserving selection but does not auto-start import after setup completes. Changing the Import Target preserves current chapter selection, immediately refreshes Import Duplicate Candidate indicators for the new target, and relies on explicit Vault Chapter Replacement confirmation for any selected duplicates. Target setup opened directly from the under-title target row persists an existing Vault Manga hint immediately and offers existing targets plus unlink only. "Create new Vault Manga" appears only in target setup for a pending Add to Vault action with selected chapters, remains pending until an import succeeds, and does not require an additional confirmation before Add to Vault. Target setup can link any Vault Manga regardless of Vault Label sensitivity, searches target choices by title, suggests exact-title matches without using them as visible auto-links or auto-selected Add to Vault targets, and can intentionally unlink by clearing only the device-local Import Target Hint. Manual target linking updates only the device-local Import Target Hint and does not publish vault metadata or provenance changes. Hints do not carry across configured Content Vault identity changes. Stale hints whose Vault Manga no longer exists in the local Vault Index are shown as unlinked or stale rather than silently rematched; relinking replaces the stale hint without a separate clear step. Import Target Hint validity and Import Duplicate Candidate indicators update while the screen is open as the local Vault Index changes.

`LocalVaultImportService.preview` scans Local Source-recognized files and uses `BuildLocalVaultImportPlan` to resolve:

- target manga through an import target hint when available
- otherwise one exact normalized title match
- target-choice-required when multiple exact matches exist
- create-new when no match exists

`BuildLocalVaultImportPlan` and `BuildLibraryVaultCapturePlan` share internal Add-to-Vault target planning mechanics for guarded Import Target Hint resolution, exact normalized title matching, explicit target choice, create-new fallback, and default duplicate deselection. The public Local-to-Vault Import and Library-to-Vault Capture plan models remain separate, and each workflow keeps its own duplicate key policy.

Chapter duplicate planning compares the physical chapter file name basename against existing remote chapter content path basenames already present in the local Vault Index for the current valid Import Target. Local Manga storage is expected to provide one physical chapter file name per chapter, so Local-to-Vault Import does not define additional ordering tie-breaks for duplicate physical file names within the same Local Manga. In-flight uploads are not considered duplicate authority. Matching Import Duplicate Candidate chapters are flagged, deselected by default, and remain selectable. Local Manga chapter rows show duplicate indicators as informational replacement-risk markers, not detailed replacement explanations.

Directory-to-CBZ conversion is automatic during import and is not shown as a normal chapter-row indicator. Conversion happens in app-managed import staging and never rewrites or deletes the original Local Source directory or chapter file. Staged CBZ page entries use the same flat numbered page naming and tall-image splitting as Library-to-Vault Capture so Vault Manga can contain consistently shaped chapters from both workflows. If selected and explicitly confirmed, duplicate candidates become Vault Chapter Replacements that preserve the replaced Vault Chapter identity, metadata, source order, and catalogue position while updating readable content and integrity data. Replacement confirmation lists the selected duplicate Local Chapter titles, capped when needed, and does not compare local and vault titles. Replacement uploads write a new remote content path and update the existing Vault Chapter content pointer instead of overwriting the old remote path. After successful publish, stale local cache state is invalidated unless the new content is separately verified in the Vault Cache Directory, and the old remote content file is cleaned up where possible. Missing old files count as already clean; cleanup failures are reported separately and do not roll back the published replacement. New non-duplicate imports use Vault Import Filename Order across the full target manga chapter set so the latest full physical chapter file name appears first, matching Local and Library chapter lists. Each successful import publish also normalizes the full target manga's non-replacement catalogue order, so earlier unstable import order is repaired the next time that manga is imported. Catalogue refresh does not rewrite remote manifests for ordering repair, and no layout migration is required for existing manifests. Checksums are reserved for content integrity and are not used to plan import duplicates.

`LocalVaultImportService.import` publishes selected chapters one chapter at a time:

1. Resolve the target manga or require explicit target selection before publishing starts.
2. Create one visible `IMPORT_PUBLISH` transfer job for the accepted Add to Vault action.
3. For each selected chapter, re-read and validate the configured remote root manifest.
4. Resolve the current target manifest, or create the target manga on the first successfully imported chapter when Create New was selected.
5. Stage the selected chapter as validated CBZ content in app-managed temporary storage when required, without mutating Local Source files.
6. Upload the staged chapter content under `content/<manga>/<chapter>/...`.
7. Upload an initial cover from Local Manga cover storage when the target has no remote cover.
8. Write or update the manga manifest.
9. Write the updated root manifest with incremented revision and summary counts.
10. Refresh the local Vault Index.
11. Persist an import target hint after the first successful publish for repeated imports from the same Local Manga.
12. Record added, replaced, failed, and cancelled counts plus sanitized failure details on the `IMPORT_PUBLISH` job.

`LocalVaultChapterStager` owns Local-to-Vault directory chapter staging and CBZ preparation. It converts directory chapters into validated flat numbered CBZ files in app-managed temporary storage, preserves original Local Source files, updates the staged chapter's content metadata, and leaves already-CBZ chapters unchanged. `LocalVaultChapterPublisher` owns the Local-to-Vault one-chapter publish boundary: remote manifest reads and identity validation, Local-to-Vault duplicate replacement checks, staging invocation, chapter and initial-cover upload, manga/root manifest writes, rollback after root publish failure, best-effort old content cleanup, replacement cache invalidation, and per-chapter publish result mapping. Prepared chapter and cover upload path creation is shared through `VaultContentUploader`, which depends on a narrow `VaultContentUploadStorage` port that is currently satisfied by compatibility adapters over `VaultRemoteStorage`. It writes prepared content to Remote Staged Upload Paths under `.staging/add-to-vault/...`, reads staged bytes back to verify size and SHA-256 integrity, and returns final `content/...` paths for manifest references. Initial cover upload uses the same remote staged verification and WebDAV `MOVE` promotion but remains non-fatal for Add to Vault when it fails. `VaultManifestPublishTransaction` promotes required verified staged chapter uploads with WebDAV `MOVE` before writing the manga manifest; if promotion or manifest publish fails, it deletes staged and final new content best-effort. `VaultRemoteStorage` owns low-level remote transport mechanics only; it does not own manifest validation, revision policy, rollback policy, refresh timing, or result semantics. `LocalVaultImportService` remains the workflow coordinator that owns preview/import sequencing, selected-chapter handling, progress, cancellation, transfer job result mapping, per-success catalogue refresh, target state, and Import Target Hint persistence.

Intentional boundary: Local-to-Vault Import and Library-to-Vault Capture keep separate scanner/stager mechanics, one-chapter publishing policy, public result types, and notification result mapping. Their shared code is limited to proven workflow-neutral mechanics such as Vault Import Request plumbing, foreground progress notification mechanics, CBZ/file staging primitives, remote path construction, transfer finalization, index refresh lookup, staged content upload, and manifest publish transactions. Do not introduce a shared Import/Capture result abstraction or shared scanner/stager layer unless a future workflow makes their finer-grained semantics align.

Imported source files do not become Local Content Cache entries. Cache state is only established when a vault chapter is separately cached into the app-managed cache directory.
Local Manga Deletion is independent from the Vault Feature. Deleting a Local Manga removes the original local files and app-owned state for that Local Manga, including any Import Target Hint, but does not delete, unlink, or mutate any Vault Manga, retained Add to Vault Task Record, or already-published Vault content created from it. Local Manga Deletion is blocked by a running Local-to-Vault Import for that same Local Manga, but not by unrelated Add to Vault work for another manga. Terminal Add to Vault Task Records remain as historical records and must tolerate the deleted source Local Manga no longer existing.
After an Add to Vault job is accepted, Local Manga chapter selection is cleared. Failed jobs do not automatically restore the previous selection.
Add to Vault uses all selected Local Manga chapters even if current filters or sorting hide some selected chapters. Local read, bookmark, and download state do not affect import eligibility.
Add to Vault is not hidden based on network heuristics; remote publish failures surface through the existing visible job or transfer failure path.
While an Add to Vault job for the same Manga Detail Screen manga is running, the under-title target row remains viewable but target changes are disabled. Add to Vault follows the current global single-job behavior shared by Local-to-Vault Import and Library-to-Vault Capture and shows a busy/in-progress state when another Add to Vault job is already running.
Partial success is expected. If at least one chapter is added or replaced, the job can finish as `PARTIALLY_SUCCEEDED` when other chapters fail. If every chapter fails, no empty Vault Manga is created and the workflow returns a failed publish result rather than a success-shaped counted result. For Create New imports, the first successfully imported chapter creates the Vault Manga; the first selected chapter is not required to be the first success. `NothingSelected` is reserved for Add to Vault attempts with no explicit accepted chapter selection; an accepted request whose selected chapters are missing is runtime failure work. Per-chapter failures include missing selected chapters, staging or CBZ validation failures, unconfirmed duplicates, chapter-specific read failures, and content upload failures before manifest publish. When all selected chapters fail before any publish succeeds, the visible transfer job's `failureReason` uses the first recorded per-chapter failure category, such as `missing_chapter`, unless a global failure supplies a more specific category. Global failures such as configured identity changes, target deletion, unreadable root manifests, root publish failure after a manga manifest write, credentials failure, or user cancellation stop remaining work.

Accepted Add to Vault actions are persisted as Vault Import Requests before WorkManager is enqueued. A request stores the source manga, workflow, Import Target, and selected chapters as durable child rows with each chapter's database id when available, workflow selection id, original order, whether that selected chapter may become a Vault Chapter Replacement, and the chapter's Task Item state. WorkManager input carries only the request id; the worker reloads the request and current manga/chapter rows before calling the import or capture service. Add to Vault Task Items move from pending to running at the start of the current worker's per-chapter attempt, before staging or chapter-specific validation, then to completed or failed when that attempt reaches a terminal per-chapter outcome. This pending/running/completed/failed Task Item lifecycle applies to both Local-to-Vault Import and Library-to-Vault Capture. If a request is resumed with stale running items from an interrupted worker, those items are reset to pending before normal request-order processing continues. If the worker exits through user-visible cancellation and the Add to Vault Task Record is terminal rather than retryable, the currently running item and any selected-but-unprocessed items are marked failed with a sanitized cancellation category. Missing selected chapters are handled by the workflow result path for both workflows and recorded as per-chapter failures, including when every selected chapter is missing. Terminal requests are retained after the job finishes so the Task Destination can inspect the accepted Add to Vault Task Record and its Add to Vault Task Items.

The Task Destination is the read-only user-visible inspection surface for Task Records that are still present in the local database. It currently lists Add to Vault Task Records with workflow, source manga, target/create-new status, chapter counts, and timestamps, and opens the Task Detail Screen for Add to Vault Task Items in request order. Summary counts keep pending and running separate: pending means accepted but not yet actively attempted, while running means the current live worker is actively attempting that item. Task surfaces use "Running" as the user-facing status label for Running Task Items. Running Task Item state is represented through the domain model and repository contract, not only as a raw database value, so repository readers, summaries, and UI state can distinguish it from pending. The `running` storage value is additive for existing retained requests; unknown stored item states should surface as invalid or diagnostic state instead of silently collapsing to pending. It does not retry, cancel, delete, refresh WebDAV, or infer job state beyond the retained Vault Import Request and its child rows.

`AddToVaultJobRunner` owns the duplicated WorkManager/request plumbing shared by `LocalVaultImportJob` and `LibraryVaultCaptureJob`: request creation, WorkManager request construction and enqueueing, request loading, workflow validation, foreground setup hooks, and notification lifecycle hooks. The workflow adapters keep the shared running guard, unique work tag, stop action, foreground info shape, service call, `LocalVaultImportResult`/`LibraryVaultCaptureResult` interpretation, and result-to-notification mapping explicit. The runner is tested directly with fakes for missing request id, missing request, wrong workflow retention, missing manga retention, success retention, failure retention, exception retention, cancellation retention/rethrow, and request creation, rather than through full WorkManager integration tests.

The shared Add to Vault foreground notification exposes collapsed status-bar progress through a generic Vault small-icon family rather than in-app progress UI. Running jobs choose 0, 25, 50, or 75 percent icon buckets from the completed-chapter count; the 100 percent icon is reserved for successful or partially successful terminal notifications. Failed jobs use the warning notification icon and cancelled jobs use the cancel icon.

Implementation debt: keep custom enforcement as the final cleanup step after the remaining Vault workflow debt has settled and the standard worth enforcing is clear. Prefer enforcing responsibility signals with a focused Gradle quality check or detekt-style rule only after the final target shape exists in code. A raw file-length rule can be a warning signal, but it should not be the primary standard for Vault workflow code.

## Library-to-Vault Capture

Library-to-Vault Capture starts from the Manga Detail Screen for a source-backed manga saved in the Library. It uses the same under-title Import Target Hint row and selected-chapter Add to Vault action as Local-to-Vault Import, but it is a separate workflow because source-backed chapters may not exist as user-owned files.

`MangaScreenModel` dispatches the shared Add to Vault UI action by manga/source type:

- Local Manga uses Local-to-Vault Import.
- Source-backed manga saved in the Library with an available source uses Library-to-Vault Capture.
- Source-backed manga not saved in the Library does not expose Add to Vault.
- Stubbed, disabled, or unavailable sources may still show the target row, but capture fails early or is unavailable.

The generalized Import Target Hint is keyed by the local manga row and validated against the configured Content Vault identity and manga source identity. For source-backed Library manga, the source identity guard is the source id plus manga URL; for Local Manga, it is the local file identity. Title changes do not invalidate a hint. Source identity mismatch, missing Vault Manga identity, or configured vault identity mismatch produces a stale target state rather than silent rematching.

Target setup is shared with Local-to-Vault Import: direct target-row linking persists an existing target immediately, pending Add to Vault target choices persist only after successful publish, exact normalized title matches are suggestions only, sensitive Vault Manga can be selected, and Create New appears only for pending selected-chapter actions.

`LibraryVaultCaptureJob` owns the Capture workflow adapter around the shared Add to Vault job runner: Android foreground info, cancellation entry point, notification progress callbacks, and Capture result mapping after the accepted Vault Import Request is loaded by `AddToVaultJobRunner`. `LibraryVaultCaptureService` owns capture planning and publication. The Vault Transfer Queue stores one `CAPTURE_PUBLISH` job for the bulk user action with added, replaced, failed, and cancelled/unprocessed counts plus sanitized failed chapter details. WorkManager remains the Android runtime boundary; `vault_transfer_jobs` is the domain-visible job/result trail.

Normal Downloads are not capture staging. At capture start:

- Already downloaded chapters are copied or read into capture staging and the original user download is never modified or deleted.
- Not downloaded, queued, downloading, or failed chapters are fetched through capture-owned staging. Capture does not attach to, cancel, reorder, mark, or clean up normal Download Queue entries.

Capture staging lives under an app-managed vault staging area, separate from normal Downloads and Local Content Cache. Staging uses conservative capture-specific concurrency, creates canonical validated CBZ files, applies tall-image splitting unconditionally, generates deterministic page names in reader/download order, and cleans files after each chapter attempt with a final sweep for interrupted jobs. Staging files do not become Cached Chapters; Vault cache state is established only through the normal cache-first flow.

Library-to-Vault duplicate planning compares normalized selected Library chapter titles against normalized Vault Chapter titles for the current Import Target. It does not use source URL, checksum, chapter number, source order, or download filename. If multiple Vault Chapters match the same normalized title, v1 can choose the first current catalogue match deterministically rather than exposing ambiguous replacement UI.

Confirmed Library-to-Vault replacements preserve the existing Vault Chapter identity, metadata, and catalogue position while updating content pointer, integrity, revision, and chapter-level provenance. Old remote content cleanup is best-effort after successful publish and does not roll back the replacement.

New captured chapters copy displayed chapter title, scanlator, chapter number, volume number, and source upload date when available. Chapter-level provenance records source id, source display name, source manga URL, source chapter URL, and capture timestamp in the remote manga manifest. Source URLs are app-private provenance: they may be stored in manifests and app-private job details, but they are not logged, displayed in notifications, or used as duplicate authority.

Library-to-Vault Capture publishes one chapter at a time in v1. Before starting staging it validates vault connectivity, configured identity, target availability, and source availability. Each one-chapter publish re-reads current remote manifests, validates identity/revision, merges against the latest target manifest, writes a new content path, updates manifests, refreshes the local index, and records progress. Per-chapter failures are recorded and the job continues; global failures such as vault identity change, target deletion, credentials failure, source unavailability, or user cancellation stop remaining work.

Partial success is expected. If at least one chapter is added or replaced, the job can finish as `PARTIALLY_SUCCEEDED` when other chapters fail. If every chapter fails, no empty Vault Manga is created. Failed capture jobs are terminal in v1; the user manually starts a fresh Add to Vault action instead of retrying a stored job intent.

New non-replacement Library-to-Vault captured chapters are ordered by latest-first natural normalized chapter title across the full target manga. Source order remains descriptive/source-local metadata and does not order a Vault Manga shared by multiple source-backed manga. Each successful capture publish normalizes the full target manga's non-replacement order by that rule; replacements keep the replaced chapter's catalogue position.

## Publishing Mutations

Metadata, cover, and deletion operations follow the same publish shape:

1. Read the remote root manifest.
2. Validate configured vault identity.
3. Compare the local root revision against the remote root revision.
4. Read and validate the target manga manifest.
5. Upgrade written manifests to the current Vault Layout Version.
6. Write staged remote content or manifests.
7. Promote staged writes into the final remote paths.
8. Refresh the local index.

Optimistic Background Publish Vault Operations are serialized through one FIFO queue per configured Content Vault because every short manifest publish writes the root manifest revision. The queue identity is the stable Content Vault Identity rather than the device-local vault database id; jobs still store the local vault id for repository queries and UI state. If the configured Content Vault Identity changes while jobs are queued, handlers fail or ignore stale jobs through normal identity validation instead of silently publishing them to the newly configured vault. This queue covers short user-triggered manifest publishes such as metadata and label edits, cover publishing, chapter thumbnail publishing, chapter deletion, and manga deletion when that workflow uses the reusable operation path. Cover and chapter thumbnail publishing are part of the target standard even if the first implementation slice lands the queue primitive with metadata and chapter deletion before migrating asset-backed short publishes. The first implementation slice covers queue infrastructure and the existing metadata and chapter deletion operations: schema and migration for the queue serialization key, repository queue methods, queue key helpers, manifest publish gate, `VaultOperationManager` conversion, worker drain-by-queue-key behavior, worker-owned refresh, and tests for ordering, coalescing, continuing after semantic failure, and metadata/deletion execution. The reusable enqueue path owns durable operation semantics: queue identity, coalescing policy, payload storage, job state, WorkManager wake-up, and ordering. It uses one internal generic enqueue primitive with explicit public methods per operation type, so call sites cannot construct inconsistent generic jobs. Handler policy declares whether a transfer type is eligible for Optimistic Background Publish execution, while enqueue calls provide operation-specific behavior such as queue key, coalescing key, coalescing mode, target ids, and payload. Pending presentation state is derived by screen models from active `vault_transfer_jobs`, such as metadata overlays, disabled chapter rows, and pending cover or thumbnail indicators. The local database queue is the source of truth, ordered by job creation time and id; WorkManager is only the wake-up mechanism. The repository exposes narrow queue queries for active jobs by queue key, queued coalescing candidates by queue key plus operation key, and active-job existence for Add-to-Vault drain checks instead of making callers scan all transfer jobs for a vault. The database keeps the queue serialization key separate from any operation-level coalescing or target key, because "all optimistic publishes for this Content Vault" and "the replaceable metadata edit for this Vault Manga" are different identities. The queue serialization key is nullable for general transfer jobs and terminal history, but the Optimistic Background Publish enqueue path requires it for every accepted queued job. Queue keys use a stable prefixed string such as `content-vault:<identity>`, and WorkManager unique names derive from that queue key. Schema migration preserves accepted non-terminal operation jobs by populating their queue serialization key from their Content Vault Identity where possible, while preserving their operation-level key for coalescing and target identity. Active operation jobs whose queue key cannot be derived during migration are marked failed with a stable sanitized migration failure reason instead of remaining active without queue serialization. One unique worker per Content Vault Identity drains active queued or running jobs until none remain. A worker marks the selected job running before waiting on the manifest publish gate, because the operation has left the queue and is actively being processed from the user's perspective. Individual operation types may still define their own queued intent policy: metadata and label publishes coalesce by replacing only queued payloads with the latest full desired state, while confirmed destructive operations such as chapter deletion remain distinct queued operations. Once a job is running, the worker owns that payload; later edits enqueue or coalesce into a separate queued job behind it. Coalescing preserves the queued job's original FIFO position rather than moving it behind later destructive operations. Queued operations are independent desired operations rather than dependent transaction steps; if two manifest changes must succeed or fail together, they belong in one operation payload and handler instead of separate queue entries. Handlers re-read fresh remote manifests when each queued operation runs, so call sites do not need to manually protect against manifest editing races. The worker owns refresh-after-success: each successful queued publish refreshes the Local Vault Index before advancing to the next job, so pending presentation and later operation preconditions see the latest catalogue state. Operation handlers own remote mutation and operation-specific local side effects, such as cache or reading-state cleanup after a successful chapter deletion, but they do not independently refresh the catalogue. A stale local root revision is not itself a terminal failure for queued Optimistic Background Publish work: the worker applies the queued operation to the current remote manifests at execution time, refreshing and retrying once when local index freshness is required. Failed semantic operations refresh the Local Vault Index only when the failure was discovered from a valid current remote manifest and indicates local state may be stale, such as a target already being absent remotely. Invalid payloads, identity changes, unsupported layouts, malformed manifests, credentials failures, and local validation failures do not trigger failure-refresh handling. Transient infrastructure failures such as temporary network, storage, timeout, or remote write failures may retry the same job with a bounded attempt budget. Semantic failures such as a missing target, last-chapter deletion, invalid payload, label conflict, identity change, unsupported layout, or malformed manifest are terminal for that job. A terminal failure or exhausted retry budget for one queued operation does not stop later independent operations from running.

Vault Operation Notifications make this queue visible while preserving Vault privacy. The user-visible Vault Operation Notification is owned by accepted non-terminal Optimistic Background Publish queue state rather than by one WorkManager worker lifecycle: it is shown while queued or running metadata, chapter rename, chapter deletion, or future short publish operations exist for a Content Vault operation queue key, and it excludes Add-to-Vault import/capture publish jobs, cache work, exports, and terminal history records. WorkManager foreground-service notifications are a runtime requirement and must not own the durable user-visible notification identity. App-layer operation notification coordination belongs beside the operation queue runtime, separate from the worker and handlers: it observes or queries active queue state, updates generic notification text, and removes the notification only when no accepted non-terminal jobs remain. It does not publish manifests, decide ordering, mutate payloads, or expose Compose UI state. Notification text is derived from the active queue snapshot: a running job can show its generic action text such as saving metadata, renaming a chapter, or deleting a chapter, an only-queued snapshot shows publishing vault changes, and worker phases such as refreshing may temporarily refine the text without owning notification lifetime. The notification has no cancel action because running manifest publishes do not yet have user-cancellation semantics. Queue completion handling is also queue-state-owned: when the active queue becomes empty, an all-success drain quietly removes the progress notification, while failed or partially successful work leaves one dismissible aggregate failure notification without manga titles, chapter titles, remote paths, or raw internal failure reasons. If another job is queued before or during the completion window, progress remains visible and the next drain cycle decides completion. Tapping the aggregate failure notification opens Bakalah normally rather than deep-linking to a specific Vault Manga or operation detail.

The target implementation is a minimal queue-state notification coordinator rather than a long-lived operation actor. The coordinator should use a user-visible progress notification identity separate from WorkManager's foreground-service notification identity, and should be invoked after enqueue and operation job state transitions. This fixes notification lifetime without changing the documented database-backed FIFO queue, manifest publish ordering, retry semantics, or WorkManager wake-up model.

Local-to-Vault Import and Library-to-Vault Capture deliberately keep workflow-specific one-chapter publishing services. Shared publishing helpers should stay limited to mechanics that do not hide duplicate policy, staging source, provenance, ordering, cover selection, replacement behavior, or workflow result semantics.
Even though Add-to-Vault publish workflows remain outside the Optimistic Background Publish queue, they must respect that queue before writing root or manga manifests. Before each one-chapter manifest publish, Add-to-Vault publishing waits for the Content Vault's Optimistic Background Publish queue to drain so long-running import or capture work does not race short queued manifest edits. Drained means there are no queued or running Optimistic Background Publish jobs for the same Content Vault queue key. The initial drain wait is a small cancellable suspending poll hidden behind a service method: it wakes the operation worker, checks active queue jobs, delays between checks, and can later switch to a flow-based implementation without changing callers. New short Optimistic Background Publish operations may still be accepted while Add-to-Vault work is active; they remain pending and run between one-chapter manifest publishes or after the current manifest publish section completes. A shared process-local manifest publish gate, keyed by Content Vault Identity, protects the actual root and manga manifest read/write section for both the queue worker and Add-to-Vault one-chapter publishers. Expensive preparation, downloading, image encoding, and upload-to-staging happen before taking the gate. The gate is held while reading current manifests, rebasing the operation, promoting already-prepared staged assets whose final paths will be referenced by the manifest, writing manga and root manifests, and rolling back or cleaning up that publish attempt. To avoid deadlock, Add-to-Vault waits for queue drain before acquiring the manifest publish gate and never holds the gate while waiting for queued short operations. The gate is a focused low-level app service rather than part of `VaultOperationManager`, because Add-to-Vault publishers need the same manifest critical section without becoming queued operations. Remote multi-device conflicts are still handled by manifest identity, revision, and rebase behavior rather than by the process-local gate.

`VaultMetadataPublishService` updates manga metadata, labels, and label sensitivity without rewriting chapter content. Vault Manga Detail starts metadata and label edits through the reusable Vault Operation path, which stores the latest publish intent in `vault_transfer_jobs`, runs the publish through WorkManager, and shows an explicit pending overlay until the refreshed Local Vault Index catches up. Vault Label identities remain stable across renames so label assignment and sensitivity are not tied to display names. Because labels are embedded in manga manifests in the current layout, renaming a label or changing sensitivity rewrites each manga manifest that already contains that label. `VaultCoverPublishService` uploads a new vault-owned cover asset and updates the manga/root manifests. Vault Chapter Thumbnail publishing uploads a new vault-owned thumbnail asset and updates the owning chapter record in the manga manifest without rewriting readable chapter content. `VaultMangaDeletionService` removes the manga pointer from the root manifest, refreshes the index, deletes the remote manga manifest, chapter CBZ files, cover assets, and thumbnail assets as cleanup, and removes app-managed local state for that Vault Manga.

Vault Chapter Deletion runs through the reusable Vault Operation path rather than a screen-local coroutine. It is a single-chapter operation that removes one chapter from its manga manifest while leaving the Vault Manga in the Vault Collection. It must not leave a Vault Manga with no chapters, and after the authoritative publish succeeds it removes the deleted chapter's app-managed local reading state and local cached chapter file on the current device.

Publishing a Vault Chapter Deletion updates the manga manifest, bumps the manga manifest revision, updates the root summary chapter count, bumps the root revision, and refreshes the Local Vault Index. Confirmed Vault Chapter Deletion operations are distinct, non-coalesced Vault Operations so one destructive confirmation cannot silently replace another queued deletion.

Vault Chapter Rename also runs through the reusable Vault Operation path, but unlike deletion it is coalesced per Vault Chapter because the latest confirmed title replaces earlier pending rename intent for the same chapter. The operation updates only the target chapter title in the manga manifest, bumps the manga and root revisions, and refreshes the Local Vault Index; it does not rewrite, move, recache, or replace readable chapter content or thumbnail assets. Rename operations for different Vault Chapters remain independent. Rename is exposed as a single-title-field dialog, not a broader chapter metadata editor. It trims leading and trailing whitespace, rejects blank titles, and otherwise allows duplicate titles and punctuation as entered. It does not recalculate chapter number, catalogue order, duplicate keys, scanlator, upload date, volume, or filenames from the edited title. Rename is not blocked merely because the owning Vault Manga is open in the Reader, because it preserves chapter identity and readable content, but it still follows operation queue serialization for active publish work against the same Vault Manga or exact Vault Chapter. While a rename is queued or running, Vault Manga Detail shows the latest pending title optimistically on that chapter row, disables rename, delete, and read/open for that chapter, and keeps the row visible until catalogue refresh confirms the title. If the rename fails, the row falls back to the authoritative title from the Local Vault Index and the screen shows a failure message.

User-facing UI may use the plain action label "Delete chapter", but confirmation copy must make clear that the chapter is permanently deleted from the Vault rather than only removed from the current device. After a deletion operation is queued, the chapter row remains visible in a pending/deleting disabled state until the refreshed Local Vault Index no longer contains that chapter.

If the remote manga manifest no longer contains the target chapter when the operation runs, Vault Chapter Deletion can complete successfully when the Vault Manga still has at least one remaining chapter and catalogue refresh confirms the chapter is absent from the Local Vault Index. Vault Chapter Deletion is blocked while the owning Vault Manga is open in the reader because Vault Reader sessions can navigate within the manga's loaded chapter list. It is also blocked by active work for that exact Vault Chapter and by active manga-manifest mutations for the same Vault Manga, but not by unrelated cache work for another chapter in the same manga.

Missing remote chapter content or thumbnail files count as already clean; other remote cleanup failures do not roll back the published deletion and are reported as one-shot warnings rather than durable retryable transfer jobs. These deletion workflows do not delete Local Manga files, Downloads, or arbitrary local files.

## Transfers and Integrity

`VaultTransferService` models visible work through `vault_transfer_jobs`.

Transfer types are:

- `IMPORT_PUBLISH`
- `CAPTURE_PUBLISH`
- `METADATA_PUBLISH`
- `THUMBNAIL_PUBLISH`
- `CHAPTER_DELETE`
- `CACHE_CHAPTER`
- `CATALOGUE_REFRESH`

Transfer states are:

- `QUEUED`
- `RUNNING`
- `SUCCEEDED`
- `PARTIALLY_SUCCEEDED`
- `FAILED`
- `CANCELLED`
- `INTEGRITY_FAULT`

Uploads and downloads use staged paths. The service validates size and SHA-256 checksum before promoting staged content. Cache downloads update `vault_chapter_cache_state` to `CACHED` only after successful integrity verification. Failed or integrity-faulted cache work remains represented as job/cache state and can be retried by opening the chapter again. Library-to-Vault Capture failures are terminal in v1 and are rerun only through a fresh Add to Vault action.

`CACHE_CHAPTER` remains a visible transfer type even though users do not manually start cache jobs from Vault Manga Detail. Read-driven cache work can still be long-running, interrupted, or failed, so it remains represented through the Vault Transfer Queue and cache state.

## Vault Operation Policy

Vault work that can take longer than an immediate screen interaction should be modeled as a typed Vault Operation rather than as one-off screen-model coroutine logic. A Vault Operation is a user-requested or app-started unit of Vault Feature work whose progress, completion, failure, or pending effect may need to remain visible beyond one immediate tap.

The existing `vault_transfer_jobs` table is the current domain-visible operation trail for transfer-like and publish-like work. Future implementation may broaden this concept into a Vault Operation Queue, but new work should first reuse or extend the same lifecycle instead of inventing separate per-feature job state.

Every Vault Operation declares an execution policy before implementation:

- Blocking Foreground: for short work where the user cannot proceed until the result is known.
- Visible Background: for durable work such as uploads, downloads, refreshes, imports, captures, and publishes that should survive navigation or app process interruption.
- Optimistic Background Publish: for reversible metadata-style mutations where the UI may show a pending local effect while the remote manifest publish runs in the background.
- Fire And Report: for non-critical derived work where canonical state is unaffected until the work succeeds.

The policy determines the default durability, UI blocking behavior, queue visibility, notification behavior, pending UI overlay, retry and cancel capability, conflict display, success feedback, and failure feedback. Feature-specific code supplies only the operation type, payload, validation rules, executor, optional optimistic overlay, index reconciliation, and sanitized failure mapping.

Screen models may request Vault Operations, observe operation state, and render authoritative index state together with pending overlays. Screen models should not own remote publish lifecycles, retry semantics, notification plumbing, WorkManager details, or durable result trails. App services own execution, Android runtime coordination, progress, cancellation, and result mapping. Domain interactors own reusable planning and conflict rules. Repositories expose authoritative Local Vault Index state and, where supported, pending operation overlays.

Optimistic UI is allowed only when pending state is explicit. The UI may reflect a pending mutation before remote success, but it must remain visually distinguishable from confirmed catalogue state and failures must not silently collapse into success. Remote manifests remain catalogue authority; successful operations reconcile by refreshing or updating the Local Vault Index and clearing the pending overlay.

### Reusable Vault Operation Code

The reusable operation implementation currently lives in `app/src/main/java/eu/kanade/tachiyomi/data/vault/operation` and reuses `vault_transfer_jobs` as the durable queue. The table name remains unchanged, but operation-capable jobs carry optional `manga_id`, `operation_key`, and `payload_json` fields in addition to the existing transfer type, state, counts, detail JSON, timestamps, and failure reason.

The main reusable pieces are:

- `VaultOperationPolicy`: documents the execution policy for each operation handler. The first active policy is `OptimisticBackgroundPublish`.
- `VaultOperationHandler`: binds one `VaultTransferType` to payload decoding, execution, and sanitized failure mapping.
- `VaultOperationManager`: creates or updates durable jobs, coalesces queued work by operation key when the operation type allows replacement, and enqueues the generic WorkManager worker.
- `VaultOperationWorker`: loads active jobs for an operation key, marks queued jobs running, dispatches them to the registered handler, records terminal state, and continues into a queued follow-up job for the same key.
- Operation payload models, such as `VaultMetadataPublishPayload`, are versioned JSON contracts stored in `payload_json`.

Metadata and label publishing is the first reusable operation. Its operation key is `vault-metadata:<mangaId>`. If a matching job is still `QUEUED`, enqueueing replaces that job's payload with the latest user intent. If a matching job is `RUNNING`, enqueueing leaves it alone and maintains at most one queued follow-up job with the newest payload. Vault Manga Detail observes manga-scoped transfer jobs, decodes the newest non-terminal metadata payload, and overlays pending metadata and labels on top of the authoritative Local Vault Index state. A successful publish refreshes the Local Vault Index and clears the overlay when no non-terminal payload remains; a failed job leaves the confirmed index state authoritative and reports sanitized failure feedback.

Future reusable operations should follow this pattern:

1. Add or reuse a `VaultTransferType`.
2. Define a small versioned payload model whose JSON can survive process death and app upgrades.
3. Pick an operation key that represents the conflict boundary and decide whether queued jobs for that key may be coalesced. Coalescing is appropriate when newer user intent replaces earlier intent, such as metadata edits, but destructive confirmations such as Vault Chapter Deletion must remain distinct.
4. Implement a `VaultOperationHandler` that validates and decodes the payload, calls the existing workflow service, and maps failures to stable sanitized reason strings.
5. Register the handler in `AppModule`.
6. Expose pending state from repository job flows rather than mutating Local Vault Index rows optimistically.
7. Keep the existing workflow service responsible for remote manifest validation, publish mechanics, catalogue refresh, cleanup, and result mapping.

Vault Chapter Export is not a `VaultTransferService` job and does not update Local Content Cache state. From the Vault Manga Detail Screen, a user can download one CBZ chapter into the device-visible Downloads directory. Export prefers an already verified Cached Chapter when available, otherwise fetches the root-inclusive Vault Chapter Remote Path from WebDAV, verifies size and SHA-256 against the Vault Catalogue record, writes a unique browser-style filename, and reports sanitized failure feedback. Successful exports show immediate feedback and a completion notification so the browser-style download is visible after the sheet or screen is dismissed.

## Cache-First Reading

Vault reading reuses the existing reader after a chapter has been verified as locally cached.

`VaultReaderOpenService.prepareChapter` is the gate:

1. Load the Vault Manga and Vault Chapter from the local index.
2. If cache state is `CACHED`, re-read the local file and verify size/checksum against the chapter content record.
3. If the local file is missing, demote the chapter to `VAULT_ONLY`.
4. If integrity mismatches, mark `INTEGRITY_FAULT`.
5. If not cached, enqueue or attach to a `CACHE_CHAPTER` transfer.
6. Return a verified local cache path only after successful download and verification.

When cache-first reading fails, the chapter's cache state remains visible on Vault Manga Detail as device state. The retry path is another read/open attempt for that chapter, not a separate cache retry action. A later successful cache-first read replaces the failure state with `CACHED`.

`ReaderViewModel` then uses `VaultPageLoader` to read the verified cached CBZ through the existing reader flow. Vault progress remains in `vault_reading_state`; Library chapter state, history, and tracker behavior are not reused as authority for Vault chapters.

Vault Chapter Thumbnails are optional, user-selected, vault-owned square images for individual Vault Chapters. They are created only from a Vault Reader Session for an existing Vault Chapter backed by verified cached content; Library and Local reader sessions do not expose the action. When the current page is ready, the Reader bottom bar exposes a dedicated image/thumbnail action, distinct from the existing crop-borders action, that opens a full-screen crop overlay for the invoked page on all form factors. The crop UI does not offer page switching. It maps a square selection back to the original loaded page pixels for that `ReaderPage`, independent of reader crop-borders, fit mode, zoom, rotation, and display scaling. If the cached CBZ contains split page entries, the invoked split page is the source; Bakalah does not reconstruct a pre-split source image. The default selection is the center square of the original loaded page, and v1 supports pan/zoom under a fixed square frame with confirm/cancel only, without a separate final-thumbnail preview. The overlay opens with the same centered page framing each time, but pinch zoom may move inward or outward as long as the fixed square crop frame remains fully covered by page pixels.

After confirmation, Bakalah processes the crop to a normalized 256 x 256 JPEG without preserving source EXIF or page metadata, then returns to reading while publishing continues. Confirming a thumbnail crop does not add any extra Vault Reading State progress or read-marker side effect beyond whatever normal reading already recorded. Animated source pages use the decoded static frame only. Thumbnail publishing is visible in the Vault Transfer Queue as `THUMBNAIL_PUBLISH`. Publishing is blocked while there is an active non-terminal Vault transfer for the same Vault Manga. The service reads and validates the remote root and manga manifests, rejects configured identity or revision mismatches instead of silently merging, uploads a new thumbnail asset under the chapter's content area, updates the `VaultManifestChapter.thumbnail` pointer and the chapter revision, updates the manga/root revisions, refreshes the local Vault Index, and cleans up any replaced remote thumbnail best-effort after success. Thumbnail metadata stores the derived asset reference only, not the source page number or crop rectangle. Replacing a thumbnail preserves the Vault Chapter identity, title, ordering, content pointer, reading state, and cached chapter content. Failed thumbnail publishes record failure state and reason but do not keep the cropped image bytes as a durable retry payload; retrying requires a fresh Reader crop action. Thumbnail publish jobs are not user-cancellable in v1 beyond normal app or job interruption, and interruption performs best-effort staged cleanup.

## Cache Policy

`VaultCachePolicyService` owns local cache paths and eviction rules.

Cache paths are derived from the local vault id, manga identity, chapter identity, and remote file name, with path segments sanitized before use. Eviction deletes only app-managed cached chapter files through the local staging abstraction and resets the chapter cache state to `VAULT_ONLY`.

Eviction primitives remain internal cache policy mechanics. Removing manual cache control from Vault UI does not remove app-owned eviction APIs needed for limit enforcement, deletion cleanup, or other maintenance flows.

Vault Chapter Thumbnails are not Local Content Cache entries and are not removed by Cache Eviction. They use a small lazy local display cache, like Vault Covers, populated when Vault UI needs to render them. Thumbnail file sizes count toward Vault Manga Storage Usage and Vault Storage Usage because they are vault-owned remote content. Vault Deletion cleans thumbnail assets along with other vault-owned manga assets.

The local cache limit is read from `ContentVaultPreferences.localCacheLimitBytes`, defaulting to 2 GiB. Limit enforcement evicts oldest read cached chapters first and accepts a protected chapter set for reader-sensitive flows. Cached Chapters remain disposable device state even if they were created by a read attempt and never finished reading; only the active reader/open flow's protected chapter set prevents eviction.

## UI and State Flow

`VaultScreenModel` observes:

- available content vaults
- manga rows for the selected vault
- chapters for the selected vault
- cache states for the selected vault

It derives visible manga items, local cache usage, remote vault storage usage, failed/queued counts, search/filter/sort output, and cover cache requests.
By default, the Vault Destination excludes Vault Manga that have any Sensitive Vault Label; direct filtering to a sensitive label or enabling the device-local include-sensitive setting includes them.
Vault Label filtering is exposed through persistent chips in the Vault Destination content below the vault summary, not through a top-bar action. The chip area appears only when the Vault Index has at least one Vault Label, shows all labels from the index after an All chip, uses a horizontally scrollable two-line layout, and keeps the selected label as ephemeral screen state. Sensitive Vault Label chips use a distinct non-error outline color without adding sensitivity text to the chip label. If catalogue refresh or metadata changes remove the selected label from the current index, `VaultScreenModel` clears the selected label filter.

`VaultMangaScreenModel` observes chapters, chapter thumbnails, and cache states for one manga. It coordinates read-driven cache recovery, metadata publish, cover publish, thumbnail display-cache loading, and deletion actions through app services. Compose screens render these derived states and send explicit user actions back to the screen models.

Each Vault Manga Detail chapter row exposes an overflow menu. Properties is available for every chapter and opens a modal bottom sheet that shows the root-inclusive Vault Chapter Remote Path when the Content Vault configuration is complete, otherwise the catalogue content path read-only. The sheet also shows the Thumbnail remote path when available, content size, and device state. Remote paths can be copied by long-pressing the path text, and the path rows expose trailing export/download buttons when a full remote path is available. These actions write user-visible files outside Bakalah's Local Content Cache and are Vault Chapter Export behavior, not cache control. Rename chapter appears between Properties and Delete chapter in the overflow menu. It is a queued Vault Operation that changes only the Vault Chapter title in the manga manifest, preserves the chapter identity, content path, thumbnail, reading state, cache state, catalogue order, and provenance, and refreshes the Local Vault Index after publish.

Vault Manga Detail does not expose manual Local Content Cache controls. Tapping a chapter is always a read/open intent; Cache-First Reading automatically verifies existing cached content, downloads or re-downloads when needed, and reports failures through the normal reader-open path. Chapter rows may still show cache availability and failure state as informational device state, but they do not expose separate cache, retry-cache, or evict-cache actions. Cache Eviction is app-controlled through Cache Policy, including local cache limit enforcement. App-owned cache control in v1 does not include proactive chapter prefetch; caching starts from read intent.

The Vault Manga Detail primary action is a read action, not a cache action. Its label should not mention cache availability; if the target chapter is not already cached, Cache-First Reading handles caching before opening the Reader. The target chapter is selected by Vault Reading State first: use the chapter in the current manga with the latest non-null `lastReadAt`, then fall back to the first chapter in the current Vault Manga catalogue order. Do not prefer Cached Chapters only because they are already cached.

The Vault Manga Detail Screen shows existing Vault Chapter Thumbnails as leading square images in chapter rows, with aligned placeholders for chapters without thumbnails. Thumbnails are visual metadata only in this screen: tapping a chapter row keeps the read/open behavior, and setting or replacing a thumbnail starts from the Reader rather than from the detail row. V1 supports setting and replacing thumbnails, not clearing them without replacement. Sensitive Vault Labels do not add thumbnail-specific hiding or blurring; if the user can view the Vault Manga Detail Screen, its chapter thumbnails render normally.

Implementation can land UI-first for reviewability. Early slices may add production UI contracts with narrow placeholder implementations that compile and return explicit mock or not-implemented thumbnail states, while the real manifest, SQLDelight, publishing, transfer, and display-cache work lands behind those contracts in later slices. Placeholder behavior must remain easy to identify and remove before the feature is considered complete, and UI-first slices should surface an honest not-wired result instead of a real success message when publishing is still mocked.

Manga Detail Screen owns the Add to Vault entry point through a small UI-layer coordinator composed into `MangaScreenModel`. The coordinator is active for Local Manga and source-backed Library manga, observes the relevant Import Target Hint and local Vault Index state, derives linked-target validity and Import Duplicate Candidate indicators, handles target setup/change state, gates Vault Chapter Replacement confirmation, and dispatches either `LocalVaultImportJob` or `LibraryVaultCaptureJob` for selected chapters. Add to Vault requires at least one selected chapter and is not available directly from the under-title target row. Select all includes duplicate-indicated chapters. The previous standalone Local-to-Vault Import screen is removed rather than kept as an unreachable route.

## Dependency Injection

Vault dependencies are registered through Injekt:

- `PreferenceModule` registers `ContentVaultPreferences`.
- `AppModule` registers `VaultRemoteStorageFactory` plus the setup, refresh, deletion, cover publish, metadata publish, import, and capture services.

Some transfer and reader-open services are constructed at use sites because they need runtime storage roots or WebDAV configuration objects.

## Boundaries and Non-Authority

The current architecture keeps these boundaries explicit:

- WebDAV is a storage transport, not the domain model.
- Remote manifests are catalogue authority; local SQLDelight rows are an index/cache of that authority.
- Local Source is an import source, not the Vault storage model.
- Source-backed Library manga are capture sources, not Vault identity or catalogue authority.
- Library manga/chapter tables are not used for Vault Manga identity or catalogue state.
- Normal Downloads are user-owned state and are not capture staging.
- Local Content Cache is device-local and disposable.
- Capture staging is temporary transfer input and is not Local Content Cache.
- Vault Reading State is device-local and not published to the remote vault.
- Vault Labels are separate from Library categories.
- Vault Covers are separate from Library custom covers.

## Verification Focus

For architecture-affecting changes, verify the relevant layer:

- Domain model/interactor changes: `scripts/gradlew-compact :domain:testDebugUnitTest`.
- App service or reader/cache changes: `scripts/gradlew-compact :app:testDebugUnitTest`.
- SQLDelight schema changes: `scripts/gradlew-compact verifySqlDelightMigration`.
- Formatting: `scripts/gradlew-compact :app:spotlessCheck :domain:spotlessCheck :data:spotlessCheck`.

Manual WebDAV, import, cache, and reading smoke coverage should be tracked with the implementation or release work that changes those flows.
