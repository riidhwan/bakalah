# Content Vault PRD

## Summary

Bakalah needs a Content Vault for user-owned manga content stored on WebDAV-accessible storage, with Hetzner Storage Box as the first supported provider. The Content Vault is not a blind folder sync, not an app backup, not the existing Local Source directory, and not the Library. It is a separate Vault Feature with its own Vault Surface, Vault Catalogue, Vault Index, Local Content Cache, and device-local Vault Reading State.

The minimum v1 should let a user connect one WebDAV-backed Content Vault, import existing Local Manga content into it without modifying the original files, browse the vault catalogue, edit basic vault metadata, cache chapters on demand, read cached chapters through the existing reader, and evict cached chapters under a user-controlled cache policy.

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
- Verify content integrity for vault uploads and cache downloads.
- Keep Vault Reading State device-local permanently.

## Non-Goals

- No blind synchronization of the Local Source directory.
- No Library membership for Vault Manga.
- No external tracker integration for Vault Manga.
- No online-source Vault Capture in v1.
- No arbitrary remote-folder scanning in v1.
- No periodic background sync or auto-capture.
- No remote Vault Deletion or Vault Trash in the minimum v1 slice.
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

### Vault Surface

Bakalah should expose a distinct Vault Surface for browsing and managing the Vault Collection. It must not be hidden inside Library or the Local tab.

The Vault Surface should support:

- Browse/search/filter from the local Vault Index.
- Show remote-only and cached chapter states.
- Open cached chapters in the existing reader.
- Trigger cache-first reading for vault-only chapters.
- Import existing Local Manga content into the vault.
- Edit basic Vault Metadata and Vault Labels.
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
  - per-manga manifests for detailed metadata, chapters, integrity data, provenance, labels, and cover references
- Local raw Vault Manifest Snapshots should be kept for diagnostics and rebuilding the Vault Index.

### Local-to-Vault Import

- V1 import is limited to existing Local Manga files already recognized by Bakalah Local Source.
- The v1 UI entry point starts from Local Manga detail; a Vault Surface picker or launcher for choosing Local Manga is deferred.
- Import must use existing Local Source recognition/parsing behavior.
- Import must support chapter selection and default to all recognized chapters.
- Import must copy/upload CBZ content into the Content Vault. When selected Local Source chapters are directories, import must clearly warn the user and replace each selected directory chapter with a validated CBZ file before upload.
- Imported source files must not count as Local Content Cache unless separately cached into the Vault Cache Directory.
- Directory-to-CBZ conversion must stage writes, validate the archive, keep deterministic page ordering, avoid absolute archive entry paths, and leave the original directory intact if conversion fails.
- Repeated imports should first use a device-local Import Target Hint when available.
- If no Import Target Hint is available, repeated imports should use exact normalized title matching.
- If one exact normalized title match exists, import into that Vault Manga.
- If no exact match exists, create a new Vault Manga.
- If multiple exact matches exist, ask the user to choose or create new.
- Exact duplicate chapters should be skipped by default.
- Possible duplicate chapters should be flagged and never overwrite silently.

### Metadata, Labels, and Covers

- Vault Metadata is authoritative after import.
- V1 metadata editing should support basic manga-level fields such as title, author/artist, description, status, and labels.
- Vault Labels are vault-owned organization markers, separate from Library categories and genres.
- Vault Metadata edits must update manifests and the local Vault Index, not rewrite chapter content files.
- Vault Covers must be separate vault-owned catalogue assets.
- Local-to-Vault Import should import the Local Manga `cover.*` image as the initial Vault Cover when the target Vault Manga has no existing cover.
- Users set or replace a Vault Cover from an already-loaded Vault Reader page through the same long-press page action used by Library reading, rather than by selecting an arbitrary local image.
- Covers/thumbnails should be locally cached separately from chapter cache.

### Cache and Reading

- Cache unit is the chapter.
- Local Content Cache must live in an app-managed Vault Cache Directory, separate from Local Source and Downloads.
- The Vault Cache Directory should be under Bakalah's user-selected base storage directory.
- The Vault Catalogue must record readable chapter content as CBZ.
- Opening a Vault-only chapter from the Vault Surface must cache and verify it before launching the reader.
- Initial cache-before-launch uses the normal visible Vault Transfer Queue/cache state, with Vault Surface navigation to the reader chained after successful verification.
- Opening a Vault-only chapter with an existing queued or running cache job must attach to that job instead of enqueueing a duplicate cache job.
- Moving to an uncached adjacent chapter inside a Vault Reader Session may perform Cache-First Reading in the reader before displaying pages.
- Adjacent cache failures must keep the failed Vault Chapter visible in the reader sequence with retry rather than removing or silently skipping it.
- Cache-first reading must only hand verified CBZ Cached Chapters to the reader.
- Opening an already Cached Chapter must re-check local file existence, size, and checksum before handing it to the reader.
- Cached Chapter `lastOpenedAt` should update only after the reader successfully displays a page from the verified CBZ.
- Vault Reader Sessions must not automatically cache ahead into Vault-only chapters in v1; new cache transfers are created only when the user opens or navigates to that chapter.
- If Vault Catalogue state changes while a Vault Reader Session is open, the current verified chapter may remain readable, but navigation must not enter newly trashed or removed adjacent content.
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

### Vault Deletion and Trash

Vault manga deletion is a follow-up workflow outside the minimum v1 slice. It moves manga-level Vault entries into a recoverable Trash state in the remote Vault Catalogue, removes them from normal Vault browsing after publish and refresh, and invalidates only app-managed Local Content Cache entries for that Vault Manga on the current device.

Vault deletion must not delete original Local Manga files under `local/`, existing Downloads, or arbitrary user-managed storage. Permanent deletion, empty-trash behavior, individual chapter deletion, and automatic trash cleanup remain separate future workflows.

### Transfers and Integrity

- Vault transfers must use staged upload/download paths.
- Partial transfers must never become visible vault content or Cached Chapters.
- Content Integrity must include at least size and checksum for each chapter content file.
- Cache downloads must verify integrity before marking a chapter cached.
- Open-time cache verification must mark missing local cache files as Vault-only and existing files with size/checksum mismatch as Integrity fault.
- Import publish must verify integrity before updating the catalogue.
- Cancellation must clean up unfinished staged artifacts where possible and must not roll back completed verified operations.
- Failed jobs must remain visible and retryable.

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
- Repeating an import can target an existing Vault Manga through Import Target Hint or exact normalized title matching.
- Exact duplicate chapters are skipped by default.
- The Vault Surface shows remote-only and cached chapters from the local Vault Index.
- A user can edit basic Vault Metadata and Vault Labels.
- A user can cache a vault-only chapter and read it after integrity verification.
- A user can evict cached chapter content without deleting vault content or original Local Manga files.
- Offline browsing of the last known Vault Index works.
- Offline reading works for Cached Chapters.
- Local cache usage and remote vault storage usage are displayed separately.
- Unknown newer Vault Layout versions are refused.

Release-readiness verification is tracked in `docs/content-vault-v1-readiness.md`.

## Future Work

- Online-source Vault Capture.
- CBZ packaging for page-based captures without recompressing page bytes.
- Restore from Vault Trash and permanent trash emptying.
- Human-readable Vault Export View.
- Diagnostics and repair tools for Vault Integrity Faults.
- Multiple Content Vaults.
- SFTP or other storage transports.
- Advanced duplicate merge workflows.
- Optional remote storage quota provider integration.
