# Content Vault Architecture

This document describes the current technical architecture of Bakalah's Content Vault implementation. It complements the product requirements in `docs/content-vault-prd.md`, the release-readiness checklist in `docs/content-vault-v1-readiness.md`, and the architectural decision in `docs/adr/0004-model-content-vault-as-separate-feature.md`.

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

- `model`: vault identities, manifests, manga, chapters, covers, labels, cache states, reading state, import plans, revisions, transfer jobs, and WebDAV config.
- `repository/VaultRepository.kt`: the persistence boundary used by interactors, app services, and screen models.
- `interactor`: pure or mostly pure use cases such as catalogue refresh construction, import planning, revision checks, and simple read/write operations.
- `service/ContentVaultPreferences.kt`: typed preferences for configured WebDAV storage, configured vault identity, private credentials, and local cache size limit.

Domain code does not perform Android storage, WebDAV, or SQLDelight work directly.

### Data

`data/src/main/java/tachiyomi/data/vault` and `data/src/main/sqldelight/tachiyomi/data/vault.sq` implement the local Vault Index:

- `VaultRepositoryImpl` implements `VaultRepository`.
- `VaultMapper` maps SQLDelight rows into domain models.
- `vault.sq` defines dedicated vault tables that stay separate from Library manga and chapter tables.

`refreshCatalogue` is the main atomic index replacement workflow. It upserts the content vault record, labels, manga, covers, chapter rows, manga-label links, and manifest snapshots in one transaction.

### App

`app/src/main/java/eu/kanade/tachiyomi/data/vault` owns Android/runtime services:

- `ContentVaultSetupService`: validates WebDAV configuration, rejects mixed-use roots, initializes empty roots, connects existing roots, and persists the configured vault identity.
- `VaultCatalogueRefreshService`: downloads root and per-manga manifests, validates identity/layout compatibility, builds a domain refresh payload, and updates the local index.
- `LocalVaultImportJob`, `LocalVaultImportService`, `LocalVaultChapterPublisher`, `LocalVaultChapterStager`, and `VaultWebDavClient`: scan Local Source manga, plan duplicates and target selection, stage selected directory chapters as CBZ without mutating Local Source files, publish one Local-to-Vault chapter at a time through low-level WebDAV transport, refresh the index, write import target hints, and record visible `IMPORT_PUBLISH` results.
- `LibraryVaultCaptureJob`, `LibraryVaultCaptureService`, `LibraryVaultChapterPublisher`, `LibraryVaultChapterStager`, and `LibraryVaultCaptureWebDav`: capture selected chapters from source-backed Library manga through capture-owned staging, publish canonical CBZ content one chapter at a time, record `CAPTURE_PUBLISH` job state, and report partial results.
- `AddToVaultJobRunner`: shares durable Vault Import Request and WorkManager plumbing between Local-to-Vault Import and Library-to-Vault Capture without sharing workflow result types or publishing policy.
- `VaultTransferService`: performs staged uploads/downloads, integrity verification, transfer job state updates, and cache state updates.
- `VaultReaderOpenService`: verifies cached chapters or performs cache-first download before reader launch.
- `VaultCachePolicyService`: creates cache paths, marks opened cached chapters, evicts chapters, and enforces the local cache size limit.
- `VaultMetadataPublishService`, `VaultCoverPublishService`, and `VaultMangaDeletionService`: publish catalogue mutations and refresh the local index afterward.
  Vault label sensitivity is catalogue-owned metadata, while the user's include-sensitive Vault Destination setting is device-local.

`app/src/main/java/eu/kanade/tachiyomi/ui/vault` owns screen models and navigation. `app/src/main/java/eu/kanade/presentation/vault` owns Compose rendering.

## Remote Vault Layout

The remote Vault Root is app-owned. A valid root contains `content-vault.json`, the root manifest defined by `VaultRootManifest`.

Current layout constants live in `VaultManifest.kt`:

- `CONTENT_VAULT_APP_ID = "bakalah-content-vault"`
- `CURRENT_VAULT_LAYOUT_VERSION = 4`
- `ROOT_VAULT_MANIFEST_NAME = "content-vault.json"`

Library-to-Vault Capture requires the next layout version because newly captured or replaced chapters need chapter-level provenance in remote manga manifests. Existing version 3 manifests can be read by treating missing chapter provenance as absent; touched manifests are upgraded when written.

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
- `vault_chapters`: chapter metadata, ordering, remote content path, content format, size, checksum, and chapter revision.
- `vault_labels` and `vault_manga_labels`: vault-owned organization labels.
- `vault_covers`: current cover metadata and remote cover path.
- `vault_reading_state`: device-local read/bookmark/page state.
- `vault_chapter_cache_state`: device-local cache state, cache path, verified integrity, open timestamps, and failure reason.
- `vault_transfer_jobs`: visible upload/download/cache/publish job state, including source-backed capture result summaries.
- `vault_import_target_hints`: device-local mapping from a Manga Detail Screen manga to the Vault Manga it was previously added to, scoped to the configured Content Vault identity and guarded by manga source identity.
- `vault_manifest_snapshots`: raw fetched manifest bodies retained for diagnostics and index rebuilding.

Chapter-level provenance is remote catalogue metadata. It does not require dedicated SQL columns until a user-facing query or display needs it; manifest snapshots retain raw provenance for diagnostics.

Normal Vault browsing reads the local index through `VaultRepository`. It does not query WebDAV live.

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

`LocalVaultChapterStager` owns Local-to-Vault directory chapter staging and CBZ preparation. It converts directory chapters into validated flat numbered CBZ files in app-managed temporary storage, preserves original Local Source files, updates the staged chapter's content metadata, and leaves already-CBZ chapters unchanged. `LocalVaultChapterPublisher` owns the Local-to-Vault one-chapter publish boundary: remote manifest reads and identity validation, Local-to-Vault duplicate replacement checks, staging invocation, chapter and initial-cover upload, manga/root manifest writes, rollback after root publish failure, best-effort old content cleanup, replacement cache invalidation, and per-chapter publish result mapping. Prepared chapter and cover upload path creation is shared through `VaultContentUploader`, which depends on a narrow `VaultContentUploadStorage` port implemented by workflow-specific WebDAV wrappers. It writes prepared content to Remote Staged Upload Paths under `.staging/add-to-vault/...`, reads staged bytes back to verify size and SHA-256 integrity, and returns final `content/...` paths for manifest references. Initial cover upload uses the same remote staged verification and WebDAV `MOVE` promotion but remains non-fatal for Add to Vault when it fails. `VaultManifestPublishTransaction` promotes required verified staged chapter uploads with WebDAV `MOVE` before writing the manga manifest; if promotion or manifest publish fails, it deletes staged and final new content best-effort. `VaultWebDavClient` owns low-level WebDAV transport mechanics only and preserves nullable and boolean transport results; it does not own manifest validation, revision policy, rollback policy, refresh timing, or result semantics. `LocalVaultImportService` remains the workflow coordinator that owns preview/import sequencing, selected-chapter handling, progress, cancellation, transfer job result mapping, per-success catalogue refresh, target state, and Import Target Hint persistence.

Implementation debt: keep Local-to-Vault Import and Library-to-Vault Capture publishing workflow-specific until their finer-grained semantics are actually aligned. Shared scanner/stager mechanics and result abstraction remain deferred. `LocalVaultImportResult` and `LibraryVaultCaptureResult` cleanup and any shared result abstraction should wait until finer-grained Import and Capture result semantics are proven aligned beyond the coarse zero-success failure behavior.

Imported source files do not become Local Content Cache entries. Cache state is only established when a vault chapter is separately cached into the app-managed cache directory.
After an Add to Vault job is accepted, Local Manga chapter selection is cleared. Failed jobs do not automatically restore the previous selection.
Add to Vault uses all selected Local Manga chapters even if current filters or sorting hide some selected chapters. Local read, bookmark, and download state do not affect import eligibility.
Add to Vault is not hidden based on network heuristics; remote publish failures surface through the existing visible job or transfer failure path.
While an Add to Vault job for the same Manga Detail Screen manga is running, the under-title target row remains viewable but target changes are disabled. Add to Vault follows the current global single-job behavior shared by Local-to-Vault Import and Library-to-Vault Capture and shows a busy/in-progress state when another Add to Vault job is already running.
Partial success is expected. If at least one chapter is added or replaced, the job can finish as `PARTIALLY_SUCCEEDED` when other chapters fail. If every chapter fails, no empty Vault Manga is created and the workflow returns a failed publish result rather than a success-shaped counted result. For Create New imports, the first successfully imported chapter creates the Vault Manga; the first selected chapter is not required to be the first success. `NothingSelected` is reserved for Add to Vault attempts with no explicit accepted chapter selection; an accepted request whose selected chapters are missing is runtime failure work. Per-chapter failures include missing selected chapters, staging or CBZ validation failures, unconfirmed duplicates, chapter-specific read failures, and content upload failures before manifest publish. When all selected chapters fail before any publish succeeds, the visible transfer job's `failureReason` uses the first recorded per-chapter failure category, such as `missing_chapter`, unless a global failure supplies a more specific category. Global failures such as configured identity changes, target deletion, unreadable root manifests, root publish failure after a manga manifest write, credentials failure, or user cancellation stop remaining work.

Accepted Add to Vault actions are persisted as Vault Import Requests before WorkManager is enqueued. A request stores the source manga, workflow, Import Target, and selected chapters as durable child rows with each chapter's database id when available, workflow selection id, original order, and whether that selected chapter may become a Vault Chapter Replacement. WorkManager input carries only the request id; the worker reloads the request and current manga/chapter rows before calling the import or capture service. Missing selected chapters are handled by the workflow result path for both workflows and recorded as per-chapter failures, including when every selected chapter is missing. Terminal requests are deleted or pruned after their result has been represented in the workflow result trail.

`AddToVaultJobRunner` owns the duplicated WorkManager/request plumbing shared by `LocalVaultImportJob` and `LibraryVaultCaptureJob`: request creation, WorkManager request construction and enqueueing, request loading, workflow validation, foreground setup hooks, notification lifecycle hooks, and terminal request cleanup. The workflow adapters keep the shared running guard, unique work tag, stop action, foreground info shape, service call, `LocalVaultImportResult`/`LibraryVaultCaptureResult` interpretation, and result-to-notification mapping explicit. The runner is tested directly with fakes for missing request id, missing request, wrong workflow cleanup, missing manga cleanup, success cleanup, failure cleanup, exception cleanup, cancellation cleanup/rethrow, and request creation, rather than through full WorkManager integration tests.

Implementation debt: after the behavior-preserving `LocalVaultImportService` extraction lands and tests cover the extracted boundaries, revisit custom enforcement. Prefer enforcing responsibility signals with a focused Gradle quality check or detekt-style rule only after the target shape exists in code. A raw file-length rule can be a warning signal, but it should not be the primary standard for Vault workflow code.

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

Implementation debt: after Local-to-Vault Import and Library-to-Vault Capture publishing semantics are proven stable, revisit whether any remaining duplicated app-layer publishing mechanics should be extracted. Do not extract shared result abstractions until Import and Capture result semantics are aligned beyond coarse success/failure behavior.

`VaultMetadataPublishService` updates manga metadata, labels, and label sensitivity without rewriting chapter content. Vault Label identities remain stable across renames so label assignment and sensitivity are not tied to display names. Because labels are embedded in manga manifests in the current layout, renaming a label or changing sensitivity rewrites each manga manifest that already contains that label. `VaultCoverPublishService` uploads a new vault-owned cover asset and updates the manga/root manifests. `VaultMangaDeletionService` removes the manga pointer from the root manifest, refreshes the index, deletes the remote manga manifest, chapter CBZ files, and cover assets as cleanup, and removes app-managed local state for that Vault Manga. It does not delete Local Manga files, Downloads, or arbitrary local files.

## Transfers and Integrity

`VaultTransferService` models visible work through `vault_transfer_jobs`.

Transfer types are:

- `IMPORT_PUBLISH`
- `CAPTURE_PUBLISH`
- `METADATA_PUBLISH`
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

Uploads and downloads use staged paths. The service validates size and SHA-256 checksum before promoting staged content. Cache downloads update `vault_chapter_cache_state` to `CACHED` only after successful integrity verification. Failed or integrity-faulted work remains represented as job/cache state. Cache failures can expose retry; Library-to-Vault Capture failures are terminal in v1 and are rerun only through a fresh Add to Vault action.

## Cache-First Reading

Vault reading reuses the existing reader after a chapter has been verified as locally cached.

`VaultReaderOpenService.prepareChapter` is the gate:

1. Load the Vault Manga and Vault Chapter from the local index.
2. If cache state is `CACHED`, re-read the local file and verify size/checksum against the chapter content record.
3. If the local file is missing, demote the chapter to `VAULT_ONLY`.
4. If integrity mismatches, mark `INTEGRITY_FAULT`.
5. If not cached, enqueue or attach to a `CACHE_CHAPTER` transfer.
6. Return a verified local cache path only after successful download and verification.

`ReaderViewModel` then uses `VaultPageLoader` to read the verified cached CBZ through the existing reader flow. Vault progress remains in `vault_reading_state`; Library chapter state, history, and tracker behavior are not reused as authority for Vault chapters.

## Cache Policy

`VaultCachePolicyService` owns local cache paths and eviction rules.

Cache paths are derived from the local vault id, manga identity, chapter identity, and remote file name, with path segments sanitized before use. Eviction deletes only app-managed cached chapter files through the local staging abstraction and resets the chapter cache state to `VAULT_ONLY`.

The local cache limit is read from `ContentVaultPreferences.localCacheLimitBytes`, defaulting to 2 GiB. Limit enforcement evicts oldest read cached chapters first and accepts a protected chapter set for reader-sensitive flows.

## UI and State Flow

`VaultScreenModel` observes:

- available content vaults
- manga rows for the selected vault
- chapters for the selected vault
- cache states for the selected vault

It derives visible manga items, local cache usage, remote vault storage usage, failed/queued counts, search/filter/sort output, and cover cache requests.
By default, the Vault Destination excludes Vault Manga that have any Sensitive Vault Label; direct filtering to a sensitive label or enabling the device-local include-sensitive setting includes them.
Vault Label filtering is exposed through persistent chips in the Vault Destination content below the vault summary, not through a top-bar action. The chip area appears only when the Vault Index has at least one Vault Label, shows all labels from the index after an All chip, uses a horizontally scrollable two-line layout, and keeps the selected label as ephemeral screen state. Sensitive Vault Label chips use a distinct non-error outline color without adding sensitivity text to the chip label. If catalogue refresh or metadata changes remove the selected label from the current index, `VaultScreenModel` clears the selected label filter.

`VaultMangaScreenModel` observes chapters and cache states for one manga. It coordinates cache, retry, eviction, metadata publish, cover publish, and deletion actions through app services. Compose screens render these derived states and send explicit user actions back to the screen models.

Manga Detail Screen owns the Add to Vault entry point through a small UI-layer coordinator composed into `MangaScreenModel`. The coordinator is active for Local Manga and source-backed Library manga, observes the relevant Import Target Hint and local Vault Index state, derives linked-target validity and Import Duplicate Candidate indicators, handles target setup/change state, gates Vault Chapter Replacement confirmation, and dispatches either `LocalVaultImportJob` or `LibraryVaultCaptureJob` for selected chapters. Add to Vault requires at least one selected chapter and is not available directly from the under-title target row. Select all includes duplicate-indicated chapters. The previous standalone Local-to-Vault Import screen is removed rather than kept as an unreachable route.

## Dependency Injection

Vault dependencies are registered through Injekt:

- `PreferenceModule` registers `ContentVaultPreferences`.
- `AppModule` registers the setup, refresh, deletion, cover publish, metadata publish, import, and capture services.

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

Manual WebDAV, import, cache, and reading smoke coverage is tracked in `docs/content-vault-v1-readiness.md`.
