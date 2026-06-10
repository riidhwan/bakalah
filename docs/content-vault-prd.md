# Content Vault PRD

## Summary

Bakalah needs a Content Vault for user-owned manga content stored on WebDAV-accessible storage, with Hetzner Storage Box as the first supported provider. The Content Vault is not a blind folder sync, not an app backup, not the existing Local Source directory, and not the Library. It is a separate Vault Feature with its own Vault Destination, Vault Catalogue, Vault Index, Local Content Cache, and device-local Vault Reading State.

The minimum v1 should let a user connect one WebDAV-backed Content Vault, import existing Local Manga content into it without modifying the original files, capture selected chapters from source-backed Library manga, browse the vault catalogue, edit basic vault metadata, cache chapters on demand, read cached chapters through the existing reader, and evict cached chapters under a user-controlled cache policy.

## Problem

The user wants a durable personal manga collection that outlives device storage limits. A plain backup or mirror is insufficient because:

- A phone cannot hold the entire collection forever.
- The app must show vault-owned content even when chapter files are not cached locally.
- The user needs explicit control over what is imported, cached, evicted, and eventually deleted.
- Local Source currently discovers physical files under the local source directory, so it cannot represent remote-only content by itself.
- Blind synchronization cannot safely express metadata authority, stable identity, cache state, integrity checks, or non-destructive deletion semantics.

## Goals

- Provide one authoritative Content Vault for user-owned manga content.
- Use generic WebDAV Vault Storage for v1, with Hetzner Storage Box documented as the initial provider.
- Keep the Vault Collection separate from Library, Local Source, Downloads, Backup, and external trackers.
- Support remote-only vault entries through a local Vault Index.
- Support chapter-level caching with cache-first reading.
- Prevent accidental data loss by keeping Vault upload/caching behavior explicit and cache eviction local-only.
- Store readable Vault chapter content as CBZ files only, converting selected Local Source directory chapters in place to CBZ before Local-to-Vault Import.
- Capture source-backed Library manga chapters into canonical validated CBZ files without turning capture staging into normal Downloads or Local Content Cache.
- Verify content integrity for vault uploads and cache downloads.
- Keep Vault Reading State device-local permanently.

## Non-Goals

- No blind synchronization of the Local Source directory.
- No Library membership for Vault Manga.
- No external tracker integration for Vault Manga.
- No arbitrary Browse/source Vault Capture in v1; source-backed capture is limited to manga already saved in the Library.
- No arbitrary remote-folder scanning in v1.
- No periodic background sync or auto-capture.
- No remote Vault Deletion in the minimum v1 slice.
- No app workflow that deletes the user's original Local Manga files, except the explicit Local-to-Vault Import conversion that replaces selected directory chapters with validated CBZ files in Local Manga storage before upload.
- No streaming remote chapter files directly from WebDAV in v1.
- No multi-vault support in v1.
- No physical deduplication of chapter content in v1.
- No rewriting imported or cached chapter files when Vault Metadata changes.
- Existing app backup behavior is out of scope for this PRD.

## Users

- A Bakalah user with a large personal manga collection.
- A user who already keeps some manga under Bakalah's Local Source directory.
- A user with WebDAV-accessible storage, initially Hetzner Storage Box.

## Product Shape

### Vault Destination

Bakalah should expose a distinct Vault Destination for browsing and managing the Vault Collection. It must not be hidden inside Library or the Local Destination.

The Vault Destination should support:

- Browse/search/filter from the local Vault Index.
- Show remote-only and cached chapter states.
- Open cached chapters in the existing reader.
- Trigger cache-first reading for vault-only chapters.
- Import existing Local Manga content into the vault.
- Add selected chapters from source-backed Library manga into the vault.
- Edit basic Vault Metadata and Vault Labels.
- Mark Vault Labels as sensitive so matching Vault Manga are hidden from default browsing unless sensitive content is explicitly included.
- Manually cache and evict selected chapters.
- Show local cache usage separately from remote vault storage usage.

### Setup

The v1 setup flow should support one configured Content Vault:

- Enter WebDAV server URL, username, password/token, and remote Vault Root path.
- Test connection.
- Validate the Vault Root.
- If the Vault Root is empty, ask before initializing a new Content Vault.
- If the Vault Root contains a valid vault, connect and refresh.
- If the Vault Root is non-empty and not a valid vault, reject it.
- Store Vault Credentials as device-local secrets, excluded from normal preferences and logs.
- Generate a Content Vault Identity and editable display name for new vaults.

Changing WebDAV URL or path must validate the Content Vault Identity before reusing the local Vault Index or Local Content Cache state.

## Functional Requirements

### Vault Catalogue and Index

- The remote Vault Catalogue must be authoritative for vault-owned manga, chapters, metadata, labels, covers, content integrity records, and provenance.
- Bakalah must maintain a local Vault Index for browsing, filtering, sorting, cache state, and job state.
- Browsing/searching must use the local Vault Index, not live WebDAV queries.
- Offline mode must allow browsing the last known Vault Index and reading Cached Chapters.
- Catalogue refresh must update the Vault Index from remote manifests.
- Publish operations must check the expected Vault Revision before applying changes.

### Vault Layout

- The Vault Layout must be versioned.
- Bakalah must open same-version vaults, migrate known older versions when migration exists, and refuse unknown newer versions.
- The Vault Root must be app-owned and not a mixed-use folder.
- The Vault Catalogue should use a hybrid manifest layout:
  - root manifest for vault identity, layout version, revision, writer ownership, summaries, and pointers
  - per-manga manifests for detailed metadata, chapters, integrity data, manga-level provenance, optional chapter-level provenance, labels, and cover references
- Local raw Vault Manifest Snapshots should be kept for diagnostics and rebuilding the Vault Index.
- Library-to-Vault Capture requires the next Vault Layout Version because newly captured or replaced chapters record chapter-level provenance in the remote Vault Catalogue.
- Existing manifests without chapter-level provenance should be treated as having no chapter provenance until touched by a future publish; migration should not rewrite every existing manga manifest just to add empty provenance.

### Local-to-Vault Import

- V1 import is limited to existing Local Manga files already recognized by Bakalah Local Source.
- The v1 UI entry point starts from Local Manga detail; a Vault Destination picker or launcher for choosing Local Manga is deferred.
- Local Manga detail should show the current Import Target Hint, or an unlinked target setup affordance, under the manga title using the same icon-and-text metadata-row style as author/artist rows, and allow changing that target from the same manga-scoped area.
- The under-title target row should show the target Vault Manga title when linked, "Link vault target" when unlinked, "Vault target unavailable" when stale, and "Set up content vault" when the Content Vault is unconfigured.
- Target setup should allow intentionally unlinking a Local Manga by clearing only the device-local Import Target Hint. Relinking from a stale state should replace the stale hint without a separate clear step.
- An unlinked Local Manga should not show Import Duplicate Candidate indicators from exact-title matching alone. Exact-title matches may be suggested during target setup, but a real selected or persisted Import Target is required for visible target state and duplicate indicators.
- Add to Vault from an unlinked Local Manga should require explicit target choice. Exact-title matches should be suggestions only, not auto-selected targets.
- If the Content Vault is not configured, the under-title target row should remain visible for Local Manga as a setup affordance that routes to vault setup.
- Local-to-Vault Import should not use a top-menu "Import to Vault" action; selected chapter import should be started from Local Manga chapter selection with an "Add to Vault" action.
- Add to Vault requires at least one selected chapter and is not available directly from the under-title target row.
- If selected chapter import starts before a Local Manga has a valid Import Target Hint, the flow should route through target setup while preserving the selected chapters.
- If Add to Vault starts with a valid linked target, target setup should be skipped and the flow should proceed directly to any required Vault Chapter Replacement confirmation.
- If Add to Vault starts with a stale target hint, it should behave like an unlinked manga and route through target setup while preserving selected chapters.
- Intentional unlinking should not disable Add to Vault; selected chapter import from an unlinked Local Manga still routes through target setup.
- If selected chapter import starts before the Content Vault is configured, the flow should route to vault setup while preserving selection, but should not auto-start import after setup completes.
- Changing the Import Target should preserve current chapter selection, immediately refresh Import Duplicate Candidate indicators for the new target, and rely on explicit Vault Chapter Replacement confirmation for any selected duplicates.
- Import Target Hints should be persisted only after a successful import publish, not when the user merely chooses a pending target.
- When target setup is opened directly from the under-title target row without a pending Add to Vault action, choosing an existing Vault Manga should persist the Import Target Hint immediately. Choosing "Create new Vault Manga" remains pending until an import succeeds.
- Direct target setup from the under-title row should offer existing targets and unlink only; "Create new Vault Manga" should appear only in target setup for a pending Add to Vault action with selected chapters.
- If an Import Target Hint points to a Vault Manga no longer present in the local Vault Index, Local Manga detail should present the manga as unlinked or stale and require target setup again.
- Import Target Hints should not carry across a configured Content Vault identity change; Local Manga target setup should be required again for the new vault.
- Manual target linking from the under-title row should update only the device-local Import Target Hint and should not publish vault metadata or provenance changes.
- Local Manga detail should live-update Import Target Hint validity and Import Duplicate Candidate indicators while the screen is open as the local Vault Index changes.
- Directory-to-CBZ conversion should not be indicated on normal Local Manga chapter rows.
- Import must use existing Local Source recognition/parsing behavior.
- Import must support chapter selection and default to all recognized chapters.
- Import must copy/upload CBZ content into the Content Vault. When selected Local Source chapters are directories, import should automatically replace each selected directory chapter with a validated CBZ file before upload without a separate conversion confirmation.
- Imported source files must not count as Local Content Cache unless separately cached into the Vault Cache Directory.
- Directory-to-CBZ conversion must stage writes, validate the archive, keep deterministic page ordering, avoid absolute archive entry paths, and leave the original directory intact if conversion fails.
- Repeated imports should first use a device-local Import Target Hint when available.
- If no Import Target Hint is available, repeated imports should use exact normalized title matching.
- If one exact normalized title match exists, import into that Vault Manga.
- If no exact match exists, create a new Vault Manga.
- If multiple exact matches exist, ask the user to choose or create new.
- Target setup should allow linking to any Vault Manga regardless of Vault Label sensitivity and should search target choices by title.
- Choosing "Create new Vault Manga" in target setup should not require an additional confirmation before Add to Vault.
- Import Duplicate Candidate chapters should be flagged, deselected by default, and still remain selectable.
- Import Duplicate Candidate indicators on Local Manga chapter rows apply only against the current valid Import Target.
- Local Manga chapter-row Import Duplicate Candidate indicators are informational; replacement details are shown in the explicit Vault Chapter Replacement confirmation.
- Import Duplicate Candidate indicators on Local Manga chapter rows should use a calm vault-status style rather than warning styling, and duplicate-indicated rows should remain visually selectable like normal rows.
- Select all includes duplicate-indicated chapters; explicit Vault Chapter Replacement confirmation remains the safety gate.
- Selected Import Duplicate Candidate chapters require explicit user confirmation before becoming Vault Chapter Replacements.
- Vault Chapter Replacement confirmation should list the selected duplicate Local Chapter titles, capped when needed, rather than comparing local and vault titles.
- Vault Chapter Replacement should preserve the replaced Vault Chapter identity and catalogue position while updating vault-owned readable content and integrity data.
- Vault Chapter Replacement should preserve existing Vault Chapter metadata and source order; metadata edits remain a separate vault catalogue workflow.
- New non-duplicate Local-to-Vault Import chapters should use Vault Import Filename Order across the full target manga chapter set so the latest full physical chapter file name appears first, matching Local and Library chapter lists.
- Each successful Local-to-Vault Import publish should normalize the full target manga's non-replacement catalogue order, repairing earlier unstable import order the next time that manga is imported.
- Catalogue refresh should not rewrite remote manifests to repair chapter order, and this ordering rule does not require a Vault Layout migration for existing manifests.
- Vault Chapter Replacement should publish replacement content at a new remote content path, update the existing Vault Chapter's content pointer and integrity data, invalidate stale local cache state unless the new content is separately verified in the Vault Cache Directory, and clean up the old remote content file after successful publish where possible.
- Import duplicate planning should use the physical chapter file name basename rather than checksums, chapter numbers, or parsed chapter titles.
- Import duplicate indicators should consider only chapters already present in the local Vault Index for the current Import Target, not in-flight uploads.
- Reimported chapters must never overwrite existing Vault Chapters silently.
- After an Add to Vault job is accepted, Local Manga chapter selection should clear. Failed jobs should not automatically restore previous selection.
- Add to Vault applies to all selected Local Manga chapters even if later filtering or sorting hides some selected chapters. Read, bookmark, and download state do not affect Add to Vault eligibility.
- Add to Vault should be available through Local Manga chapter multi-selection only, including the single selected chapter case. It should not add a separate per-row chapter action.
- Add to Vault should not be hidden based on network heuristics; remote publish failures should surface through the existing visible job or transfer failure path.
- While a Local-to-Vault Import job for the same Local Manga is running, the under-title target row may remain visible but target changes should be disabled.
- Add to Vault should respect the current global single Local-to-Vault Import job behavior and show a busy/in-progress state when another import job is already running.

### Library-to-Vault Capture

- Library-to-Vault Capture is limited to source-backed manga already saved in the Library. It is not available for arbitrary Browse/source manga.
- Local Manga continues to use Local-to-Vault Import. Stubbed, disabled, or unavailable sources cannot capture chapters, but the under-title Import Target Hint row may remain visible for linking or unlinking.
- Source-backed Library Manga detail should show the same under-title Import Target Hint row as Local Manga detail: linked target title, "Link vault target", "Vault target unavailable", or "Set up content vault".
- The under-title target row is manga-scoped state and should be visible even when no chapters are selected. Target changes should preserve current chapter selection and immediately refresh Import Duplicate Candidate indicators for the selected target.
- Add to Vault should be available from the same selected-chapter bottom action menu, including the single selected chapter case, and should keep the user-facing label "Add to Vault".
- Add to Vault requires at least one selected chapter. If the Content Vault is not configured, it routes to vault setup while preserving selection but must not auto-start capture after setup completes.
- Target setup should allow choosing any Vault Manga, including sensitive-label targets, or creating a new Vault Manga when setup is opened for a pending Add to Vault action.
- Exact normalized manga title matches may be suggested during target setup but must not create a visible linked target or duplicate indicators until the user chooses a target.
- Import Target Hints are shared by Local-to-Vault Import and Library-to-Vault Capture. They should be device-local, scoped to the configured Content Vault identity, keyed by the Manga Detail Screen manga row, and validated against the manga's source identity. Stale hints are shown as unavailable rather than silently rematched or cleared.
- Manual target linking from the under-title row persists the Import Target Hint immediately and does not publish vault metadata or provenance changes.
- Pending target choices made while starting Add to Vault should persist only after at least one chapter is successfully added or replaced.
- Library-to-Vault Capture should validate vault connectivity, configured vault identity, target availability, and source availability before staging any chapter content.
- If a selected chapter is already downloaded when capture starts, Bakalah should copy/reprocess that user-owned download into capture staging and leave the original download untouched.
- If a selected chapter is not fully downloaded when capture starts, including queued, downloading, failed, or not downloaded states, capture should use capture-owned staging and must not attach to, cancel, reorder, or delete normal Download Queue work.
- Capture staging must live outside normal Downloads and outside Local Content Cache. Staging files are disposable, cleaned after each chapter attempt with a final sweep at job end, and stale staging should be cleaned after interrupted jobs.
- Capture staging should create canonical validated CBZ content for every captured chapter, including chapters sourced from pre-existing downloads.
- Capture should use deterministic reader/download page order, generate canonical page names inside the CBZ, apply tall-image splitting unconditionally, and ignore normal download storage, naming, queue, and cleanup preferences.
- Capture should use conservative capture-specific concurrency for v1.
- Staging failures caused by local storage space should fail the current chapter and continue where possible rather than evicting Local Content Cache automatically.
- Captured source files must not become Local Content Cache entries. Newly captured Vault Chapters can be cached later through the normal Vault cache-first flow.
- Capture should use the source runtime path the app can already read/download from, including logged-in or cookie-backed sources, without introducing separate credential handling.
- Capture should not require an automatic source chapter refresh before starting; it acts on currently known selected chapter rows.
- Capture should copy manga metadata from the Library manga when creating a new Vault Manga: title, author, artist, description, status, and the currently displayed cover when available. Cover upload failure must not block chapter capture.
- Library categories, source genres/tags, read state, bookmarks, history, and tracker progress must not be copied into Vault Labels or Vault Reading State.
- New captured Vault Chapters should copy displayed chapter title, scanlator when available, chapter number, volume number, and source upload date as descriptive metadata.
- Chapter-level provenance for captured content should be stored in the remote Vault Catalogue, including source id, source display name, source manga URL, source chapter URL, and capture timestamp. Source URLs are private metadata and must not be logged or shown in notifications by default.
- When capture creates a new Vault Manga, manga-level provenance may describe the source-backed Library manga origin. When capture adds to an existing Vault Manga, it must not overwrite existing manga-level provenance.
- Library-to-Vault duplicate detection should compare normalized selected Library chapter titles against normalized Vault Chapter titles in the current Import Target. It must not use source URL, checksum, chapter number, source order, or download filename as duplicate authority.
- Duplicate indicators should appear only for a valid linked or pending Import Target, use the same calm vault-status styling as Local-to-Vault Import, be deselected by default in automatic selection, and remain selectable. Select all includes duplicate-indicated chapters.
- Selected duplicate candidates require explicit Vault Chapter Replacement confirmation before a capture job starts. If the user cancels confirmation, no staging or publish occurs; for v1, Bakalah should not continue with only non-duplicate selected chapters.
- Replacement confirmation should list selected duplicate Library chapter titles as displayed, capped when needed, and avoid showing source URL/checksum comparisons.
- If multiple Vault Chapters match the same normalized title, v1 may choose a deterministic match such as the first current catalogue match rather than adding special ambiguous-replacement UI.
- Confirmed Library-to-Vault replacements should preserve the replaced Vault Chapter identity, metadata, and catalogue position while updating readable CBZ content, integrity data, revision, and chapter-level provenance. Old remote content cleanup is best-effort and must not roll back a successful replacement.
- Library-to-Vault Capture should publish partial success. Each selected chapter is fetched, staged, validated, uploaded, and published independently, using fresh remote revision checks per one-chapter publish unit in v1.
- If every selected chapter fails, no new empty Vault Manga should be created. If at least one chapter succeeds, successful additions/replacements remain published even if other chapters fail.
- Per-chapter failures such as source fetch failure, no readable pages, CBZ validation failure, local staging-space failure, vault upload failure, or recoverable revision conflict should be recorded and capture should continue. Job-global failures such as vault identity change, target deletion, credentials failure, source unavailable, or cancellation should stop remaining work.
- Cancellation should stop before the next irreversible publish step, preserve already published chapters, and clean capture staging best-effort. Cancellation during a manifest publish may count the chapter as published if the publish succeeds.
- Final notification and Vault Transfer Queue details should report separate added, replaced, failed, and cancelled/unprocessed counts where applicable. Failed details should include chapter titles and sanitized failure categories, not raw exception text or source URLs.
- Failed capture jobs are terminal in v1. There is no automatic retry or one-click retry; the user can manually reselect chapters and start a fresh Add to Vault action.
- After a capture job is accepted, Manga Detail Screen chapter selection should clear. Failed chapters should not automatically restore the previous selection.
- Add to Vault applies to all selected chapters even if filters or sorting later hide some selected rows. Read, bookmark, and normal download state do not affect eligibility.
- Library-to-Vault Capture should use one global vault-add job limit shared with Local-to-Vault Import. While a job is running for the same Manga Detail Screen manga, target changes are disabled; other Manga Detail Screens may still inspect or prepare target hints but cannot start another vault-add job.
- New non-replacement Library-to-Vault captured chapters should be ordered by latest-first natural normalized chapter title across the full target manga. Source order is source-local metadata and must not order a Vault Manga shared by multiple source-backed manga. Deterministic tie-breaks may be used for rare equal normalized titles.
- Each successful Library-to-Vault Capture publish should normalize the full target manga's non-replacement catalogue order by that title-ordering rule. Confirmed replacements preserve the replaced chapter's catalogue position.

### Metadata, Labels, and Covers

- Vault Metadata is authoritative after import.
- V1 metadata editing should support basic manga-level fields such as title, author/artist, description, status, and labels.
- Vault Labels are vault-owned organization markers, separate from Library categories and genres.
- Vault Label identity must remain stable across renames so assigned Vault Manga and sensitivity survive display-name changes.
- Sensitive Vault Labels are vault-owned label metadata; a Vault Manga with any Sensitive Vault Label must be excluded from default Vault Destination results unless the user explicitly includes sensitive content or directly filters to that sensitive label.
- Vault Manga detail should show assigned Vault Labels as tag-like chips in the manga header, with sensitive labels visually distinguished by a different non-error color. Selecting an assigned label should open a bottom sheet or dialog with actions for label-scoped sensitivity toggling and manga-scoped removal of the Vault Label Assignment, ordered with the sensitivity action before removal. These label chip actions should publish immediately without an additional confirmation or save step.
- Vault Manga detail should always provide a compact add-label affordance near the assigned label chips, even when no labels are assigned. Selecting it should open a bottom sheet or dialog. Each add-label flow should add one label: either assign one existing unassigned Vault Label immediately or create one new non-sensitive Vault Label assigned to the current Vault Manga through a text field and Add action. The add-label picker should include sensitive labels even when the Vault Destination is not including sensitive content, and should visually mark sensitive labels.
- The user's include-sensitive browsing choice is device-local and must not change remote Vault Catalogue metadata.
- Adding Vault Label sensitivity bumps the Vault Layout Version; migration from older layouts treats all existing labels as non-sensitive until changed by the user.
- Vault Metadata edits must update manifests and the local Vault Index, not rewrite chapter content files.
- Vault Covers must be separate vault-owned catalogue assets.
- Local-to-Vault Import should import the Local Manga `cover.*` image as the initial Vault Cover when the target Vault Manga has no existing cover.
- Library-to-Vault Capture should use the currently displayed Manga Detail Screen cover as the initial Vault Cover when creating a new target or when the target has no cover, without replacing an existing target cover.
- Users set or replace a Vault Cover from an already-loaded Vault Reader page through the same long-press page action used by Library reading, rather than by selecting an arbitrary local image.
- Covers/thumbnails should be locally cached separately from chapter cache.

### Cache and Reading

- Cache unit is the chapter.
- Local Content Cache must live in an app-managed Vault Cache Directory, separate from Local Source and Downloads.
- The Vault Cache Directory should be under Bakalah's user-selected base storage directory.
- The Vault Catalogue must record readable chapter content as CBZ.
- Opening a Vault-only chapter from the Vault Destination must cache and verify it before launching the reader.
- Initial cache-before-launch uses the normal visible Vault Transfer Queue/cache state, with Vault Destination navigation to the reader chained after successful verification.
- Opening a Vault-only chapter with an existing queued or running cache job must attach to that job instead of enqueueing a duplicate cache job.
- Moving to an uncached adjacent chapter inside a Vault Reader Session may perform Cache-First Reading in the reader before displaying pages.
- Adjacent cache failures must keep the failed Vault Chapter visible in the reader sequence with retry rather than removing or silently skipping it.
- Cache-first reading must only hand verified CBZ Cached Chapters to the reader.
- Opening an already Cached Chapter must re-check local file existence, size, and checksum before handing it to the reader.
- Cached Chapter `lastOpenedAt` should update only after the reader successfully displays a page from the verified CBZ.
- Vault Reader Sessions must not automatically cache ahead into Vault-only chapters in v1; new cache transfers are created only when the user opens or navigates to that chapter.
- If Vault Catalogue state changes while a Vault Reader Session is open, the current verified chapter may remain readable, but navigation must not enter removed adjacent content.
- Vault Reader Session restore must use explicit Vault session identity and Vault Manga/Chapter identifiers, not temporary Library Manga/Chapter identifiers.
- V1 must reuse existing reader UI/infrastructure where possible.
- Reader integration should split shared reader UI/viewer orchestration from session-specific behavior through a small reader backend boundary, with separate Library and Vault implementations.
- Vault Reader Sessions use Vault Catalogue chapter order by `sourceOrder`; Library chapter sorting and filtering preferences do not apply.
- Vault Reading State must remain owned by the Vault Feature and device-local.
- Vault Reading State covers page progress, read markers, bookmarks, and last-read timestamps; v1 does not add Vault read-duration history or tracker progress.
- Vault Reading State should update `lastReadAt` when persisted page progress changes, including first displayed page and completion, while avoiding repeated writes for the same page index.
- Vault Reader Sessions use global reader defaults for reading mode and orientation in v1; per-Vault Manga viewer flags are out of scope for cache-first reading.
- Completing a Vault Chapter marks only that Vault Chapter read; Library duplicate-read propagation does not apply to Vault Reader Sessions.
- Vault Reader Sessions may keep generic page image actions such as save, share, copy, and set-as-cover when backed by a loaded page stream. Vault set-as-cover publishes a vault-owned cover asset and updates manifests; it must not write to Library custom cover storage.
- Cache eviction must remove only app-managed cached chapter content, never original Local Manga files and never vault-owned remote content.
- Default cache policy should cache opened chapters and evict oldest read cached chapters when the user-set size limit is exceeded.
- Cache policy enforcement after reader-triggered caching must protect the active Vault Reader Session's current chapter and immediate loaded neighbors.
- Manual cache eviction must not remove a Cached Chapter that is part of an active Vault Reader Session's current chapter or immediate loaded neighbors on the device.
- The user must be able to manually cache and evict selected chapters.
- Local cache usage and remote vault storage usage must be shown separately.
- Local cache limit is hard-enforced; remote vault quota is a soft warning.

### Vault Deletion

Vault manga deletion permanently removes manga-level Vault entries from the remote Vault Catalogue. It first removes the manga pointer from the root manifest as the authoritative deletion, then deletes the manga manifest, chapter CBZ files, and cover assets from WebDAV storage as cleanup.

Vault deletion must not delete original Local Manga files under `local/`, existing Downloads, or arbitrary user-managed storage. Missing remote files during cleanup count as already clean; other cleanup failures should be reported after returning to the Vault Destination without rolling back the root manifest deletion. Individual chapter deletion remains a separate future workflow.

### Transfers and Integrity

- Vault transfers must use staged upload/download paths.
- Partial transfers must never become visible vault content or Cached Chapters.
- Content Integrity must include at least size and checksum for each chapter content file.
- Cache downloads must verify integrity before marking a chapter cached.
- Open-time cache verification must mark missing local cache files as Vault-only and existing files with size/checksum mismatch as Integrity fault.
- Import publish must verify integrity before updating the catalogue.
- Library-to-Vault Capture publish should be represented as `CAPTURE_PUBLISH` work in the Vault Transfer Queue and use a single visible job for the user's bulk action.
- Transfer states should distinguish partial success, such as `PARTIALLY_SUCCEEDED`, when some Library-to-Vault Capture chapters publish and others fail.
- Cancelled capture jobs should use `CANCELLED` plus result counts rather than a separate partially-cancelled state.
- Cancellation must clean up unfinished staged artifacts where possible and must not roll back completed verified operations.
- Failed jobs must remain visible. Library-to-Vault Capture failed jobs are terminal in v1 and are rerun only by starting a new Add to Vault action.

### WebDAV Storage

- V1 must support generic WebDAV configuration rather than hard-coding Hetzner.
- Hetzner Storage Box should be documented as the first tested Vault Storage Provider.
- Required WebDAV operations include directory listing, directory creation, upload, download, delete where supported later, and file metadata checks.
- WebDAV is transport only; it must not define the domain model.

### Writer Ownership

- V1 should use explicit single-writer ownership for publishing.
- Other devices may refresh catalogue and cache content, but only the Vault Writer may publish authoritative changes.
- Vault Writer Takeover should be explicit and user-approved when implemented.
- Automatic lock expiry must not silently create multi-writer behavior.

## User-Visible States

Vault chapter states should be small and explicit:

- Vault-only: known in the Vault Catalogue but not cached on this device.
- Queued: waiting for a vault transfer.
- Caching or Publishing: transfer in progress.
- Cached: verified local chapter content exists.
- Failed: last vault operation failed and can be retried.
- Integrity fault: vault/cache data is unsafe or inconsistent.

Internal staging paths, revisions, and checksums should be hidden from normal UI and reserved for diagnostics/details.

## V1 Acceptance Criteria

- A user can configure one generic WebDAV Content Vault and initialize an empty Vault Root.
- A user can connect to an existing valid Content Vault and refresh its catalogue.
- A non-vault mixed-use folder is rejected during setup.
- A user can import selected chapters from an existing Local Manga into the Content Vault, with selected directory chapters first converted into validated CBZ archives in Local Manga storage.
- A user can add selected chapters from a source-backed Library manga into the Content Vault through the same Add to Vault selected-chapter action.
- Repeating an import can target an existing Vault Manga through Import Target Hint or exact normalized title matching.
- Import Duplicate Candidate chapters are flagged, deselected by default, still selectable, and replace existing Vault Chapters only after explicit confirmation.
- Library-to-Vault Capture uses capture-owned staging for chapters that are not already downloaded, leaves normal Downloads untouched, publishes successful chapters even when other selected chapters fail, and reports added/replaced/failed counts.
- The Vault Destination shows remote-only and cached chapters from the local Vault Index.
- A user can edit basic Vault Metadata and Vault Labels.
- A user can cache a vault-only chapter and read it after integrity verification.
- A user can evict cached chapter content without deleting vault content or original Local Manga files.
- Offline browsing of the last known Vault Index works.
- Offline reading works for Cached Chapters.
- Local cache usage and remote vault storage usage are displayed separately.
- Unknown newer Vault Layout versions are refused.

Release-readiness verification is tracked in `docs/content-vault-v1-readiness.md`.

## Future Work

- CBZ packaging for page-based captures without recompressing page bytes.
- Arbitrary Browse/source Vault Capture outside saved Library manga.
- Converging Local-to-Vault Import into the Vault Transfer Queue and partial-success behavior.
- Human-readable Vault Export View.
- Diagnostics and repair tools for Vault Integrity Faults.
- Multiple Content Vaults.
- SFTP or other storage transports.
- Advanced duplicate merge workflows.
- Optional remote storage quota provider integration.
