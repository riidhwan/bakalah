# Bakalah

Bakalah is a personal Android reader fork derived from Mihon. This context captures project-specific language for distinguishing the personal fork from upstream Mihon.

## Language

**Personal App Identity**:
The complete public identity that makes Bakalah behave as a distinct app from upstream Mihon on a user's device and in app integrations.
_Avoid_: Package rename, rebrand, app id change

**Bakalah-Owned Link**:
A deep link whose scheme names Bakalah directly and is intended to open Bakalah without competing with upstream Mihon link ownership.
_Avoid_: Custom link, fork link

**Upstream Compatibility Link**:
A deep link originally owned by Mihon or Tachiyomi that Bakalah may still handle for compatibility with existing extension-store links or tracker redirects.
_Avoid_: Legacy link, old link

**User-Facing Branding**:
Names, text, icons, and release artifacts that identify the installed app to a person using or installing Bakalah.
_Avoid_: Internal package name, namespace

**Personal-Driven Maintenance**:
Bakalah's maintenance stance where project direction, review attention, and accepted changes are primarily determined by the maintainer's personal use.
_Avoid_: Community roadmap, open contribution queue, upstream support channel

**Release Process**:
The end-to-end practice for shipping a Bakalah version to users, from deciding the version through making release artifacts available.
_Avoid_: Release workflow, build workflow, CI workflow

**Release Intent**:
The explicit project decision that a reviewed Bakalah revision should become a user-available version.
_Avoid_: Build trigger, publish action

**Release Branch**:
A short-lived preparation branch named for the Release Version it intends to ship, using `release/MAJOR.MINOR.PATCH`.
_Avoid_: Release prep branch, version branch

**Release Version**:
The public version name assigned to one Bakalah release and used consistently for the release tag and user-facing artifacts.
_Avoid_: Build number, version code, tag name

**Release Artifact Set**:
The complete group of installable Bakalah files that together represent one shipped version.
_Avoid_: APKs, build outputs, assets

**Internal Namespace**:
Source code package names and module identifiers inherited from Mihon or Tachiyomi that do not by themselves determine Android install identity.
_Avoid_: App identity, launcher name

**Library Chapter Update System**:
The feature area that discovers, records, presents, and notifies about new or changed chapters for manga already in the user's library.
_Avoid_: App updates, extension updates, generic update operations

**Legacy Library Update Work**:
Previously scheduled background or manual work that bulk-refreshes library manga for chapter changes.
_Avoid_: Per-manga refresh, metadata update

**Per-Manga Chapter Refresh**:
Refreshing the chapter list for one manga while the user is interacting with that manga or a workflow that needs its chapters.
_Avoid_: Library update, background update

**Recent Updates Surface**:
A user-facing view or widget that lists recently fetched chapters across the library.
_Avoid_: Chapter list, history

**Top-Level App Destination**:
A primary user-facing area reachable from the app's main navigation bar or rail. Use Destination for top-level navigation areas, not pushed detail or full-screen experiences.
_Avoid_: Screen, tab, page

**Library Destination**:
The top-level navigation destination for browsing and managing manga saved to the user's library.
_Avoid_: Library tab, library screen

**Local Destination**:
The top-level navigation destination for browsing manga stored in the user's local source directory.
_Avoid_: Local source tab, downloads

**History Destination**:
The top-level navigation destination for revisiting manga and chapters from the user's reading history.
_Avoid_: Recent manga tab, recently read screen

**Browse Destination**:
The top-level navigation destination for finding source manga, managing extensions, and starting migration workflows.
_Avoid_: Browse tab, sources screen

**Browse Subsection**:
One of the internal areas within the Browse Destination, such as Sources, Extensions, or Migration.
_Avoid_: Top-level destination, browse screen

**More Destination**:
The top-level navigation destination for secondary app actions, preferences, statistics, downloads, and app information.
_Avoid_: More tab, settings screen

**Manga Detail Screen**:
A pushed screen for viewing and acting on one Library, Local, or source manga. Use Screen for pushed detail or full-screen experiences, not top-level navigation areas.
_Avoid_: Manga page, title screen

**Vault Manga Detail Screen**:
A pushed screen for viewing and managing one Vault Manga.
_Avoid_: Vault title screen, vault details

**Reader**:
The full-screen reading experience for chapter pages.
_Avoid_: Reading screen, page viewer

**Settings Screen**:
A pushed screen for app preferences and configuration.
_Avoid_: More Destination, preferences page

**Download Queue Screen**:
A pushed screen for viewing and controlling queued chapter downloads.
_Avoid_: Downloads tab, download manager page

**Local Manga**:
A manga whose chapters and metadata are owned by the user as local files rather than supplied by a remote source.
_Avoid_: Downloaded manga, offline manga

**Source-Backed Library Manga**:
A manga saved in the Library whose chapters are supplied by an app source rather than owned as Local Manga files.
_Avoid_: Library Destination manga, downloaded manga, source manga

**Content Vault**:
The authoritative user-owned collection of manga content that can outlive any one device's local storage.
_Avoid_: Backup, cloud mirror, remote local source

**Content Vault Identity**:
The stable generated identity of one Content Vault, independent from its storage URL, path, or display name.
_Avoid_: Vault path, provider account, vault title

**Vault Feature**:
The Bakalah feature area for browsing, capturing, publishing, caching, and evicting Content Vault content.
_Avoid_: Local Source sync, backup sync, cloud downloads

**Vault Collection**:
The user's manga content collection in the Vault Feature, separate from the app Library.
_Avoid_: Library, cloud library, synced library

**Vault Label**:
A user-owned organization marker for grouping Vault Manga in the Vault Collection.
_Avoid_: Vault category, Library category, genre, source tag

**Vault Label Assignment**:
The association that makes one Vault Label apply to one Vault Manga.
_Avoid_: Label deletion, manga label, tag deletion

**Sensitive Vault Label**:
A Vault Label with sensitivity metadata that marks assigned Vault Manga as excluded from the default Vault Destination unless the user explicitly includes sensitive content.
_Avoid_: 18+ label, hidden category, private genre

**Vault Destination**:
The top-level app destination for browsing and managing the Vault Collection.
_Avoid_: Vault Surface, Local Destination, Library Destination, sync settings

**Vault Reading State**:
The device-local page progress, read markers, bookmarks, and last-read timestamps for Vault Collection content.
_Avoid_: Library state, history sync, tracking state, read-duration history

**Vault Layout**:
The versioned organization of catalogue records, metadata, and content files inside a Content Vault.
_Avoid_: Folder mirror, storage box structure, local source tree

**Vault Layout Version**:
The compatibility marker that tells Bakalah whether it can safely read or migrate a Vault Layout.
_Avoid_: App version, sync version, schema guess

**Vault Root**:
The storage location whose contents are owned by one Bakalah Content Vault.
_Avoid_: Sync folder, import folder, cloud directory

**WebDAV Vault Storage**:
A WebDAV-accessible storage location used to hold a Bakalah Content Vault.
_Avoid_: Hetzner sync, cloud source, remote filesystem

**Vault Storage Provider**:
A service that hosts WebDAV Vault Storage, such as Hetzner Storage Box.
_Avoid_: Vault, source, sync target

**Vault Credentials**:
The device-local secret used to access WebDAV Vault Storage.
_Avoid_: Backup setting, sync password, vault metadata

**Vault Export View**:
An optional human-readable representation of vault-owned content that is convenient to inspect but not authoritative.
_Avoid_: Vault layout, source of truth, synced folder

**Local Content Cache**:
The subset of Content Vault manga content currently present on a device for browsing or reading.
_Avoid_: Synced copy, offline backup, downloaded manga

**Vault Cache Directory**:
The app-managed device storage location for Local Content Cache files.
_Avoid_: Local source directory, downloads directory, user manga folder

**Local Cache Usage**:
The amount of device storage currently used by Cached Chapters and vault transfer staging.
_Avoid_: Vault size, downloads size, backup size

**Vault Storage Usage**:
The amount of storage currently used by the Content Vault on its Vault Storage Provider.
_Avoid_: Cache size, local storage, download size

**Vault Manga Storage Usage**:
The amount of storage used by one Vault Manga's vault-owned chapter content.
_Avoid_: Vault Storage Usage, local cache usage, manga cache size

**Vault Storage Quota**:
The user's storage budget or provider-reported limit for a Content Vault.
_Avoid_: Cache limit, download limit, device storage

**Vault Catalogue**:
The browseable record of Content Vault manga and chapters, including entries whose files are not currently on the device.
_Avoid_: Local source listing, remote file list, cloud folder

**Vault Revision**:
The catalogue generation Bakalah uses to detect whether a local Vault Index is stale before publishing changes.
_Avoid_: Sync timestamp, file modified time, app version

**Vault Index**:
Bakalah's local indexed copy of the Vault Catalogue and device-specific cache state.
_Avoid_: Vault catalogue, local source cache, remote manifest

**Vault Manifest Snapshot**:
A local copy of a remote vault manifest kept for diagnostics or rebuilding the Vault Index.
_Avoid_: Vault index, backup, editable metadata

**Vault Metadata**:
The user-owned descriptive information in the Vault Catalogue that is authoritative after content has been imported.
_Avoid_: File metadata, local metadata, ComicInfo truth

**Vault Cover**:
The vault-owned image used to represent a manga in the Vault Catalogue.
_Avoid_: Chapter cover, local thumbnail, cached page

**Cached Chapter**:
A chapter from the Content Vault whose readable content file is currently present on the device.
_Avoid_: Downloaded chapter, synced chapter, offline chapter

**Original Chapter File**:
The CBZ chapter content file accepted into the Content Vault as readable vault content.
_Avoid_: Chapter folder, synced file

**Vault CBZ Chapter**:
A Vault Chapter whose readable content is a validated CBZ file recorded in the Vault Catalogue and eligible for Cache-First Reading.
_Avoid_: Directory chapter

**Captured Chapter File**:
The chapter file Bakalah creates when Vault Capture collects page-based source content.
_Avoid_: Download folder, normalized original, source file

**Content Integrity**:
The recorded evidence that vault-owned chapter content is complete and unchanged, such as file size and checksum.
_Avoid_: Download status, file metadata, cache state

**Chapter-Level Provenance**:
The vault-owned record of where one Vault Chapter's current readable content came from.
_Avoid_: Manga provenance, duplicate key, source truth

**Staged Transfer**:
A vault upload or download that remains hidden from normal catalogue and reading flows until content integrity is verified.
_Avoid_: Partial sync, in-progress content, temporary chapter

**Vault Transfer Queue**:
The visible queue of vault operations that move or verify catalogue and chapter content.
_Avoid_: Download queue, sync queue, background update

**Vault Integrity Fault**:
A detected problem showing that the Content Vault cannot be safely trusted or modified without user attention.
_Avoid_: Sync error, download error, cache miss

**Vault Import**:
Adding user-owned manga content into the Content Vault from files already available to Bakalah.
_Avoid_: Upload sync, backup, local import

**Vault Import Filename Order**:
The catalogue order assigned to newly imported Vault Chapters from the full physical Local Manga chapter file names, using latest-first natural filename comparison to match Local and Library chapter lists.
_Avoid_: Chapter name order, source order, random order

**Local-to-Vault Import**:
Vault Import that copies existing Local Manga content into the Content Vault without general cleanup or migration of the original Local Manga, except that selected directory chapters are converted into validated CBZ files in Local Manga storage before upload.
_Avoid_: Local cleanup, migration, local sync

**Library-to-Vault Capture**:
Vault Capture that adds selected chapters from a source-backed manga saved in the Library to the Content Vault.
_Avoid_: Local-to-Vault Import, downloaded manga import, library sync

**Capture Staging Download**:
A disposable chapter download created by Library-to-Vault Capture, separate from the normal Download Queue, so Bakalah can obtain readable source content for publishing to the Content Vault.
_Avoid_: User download, cached chapter, Local Content Cache

**Import Target**:
The Vault Manga that a Local-to-Vault Import or Library-to-Vault Capture will add selected chapters to, or the decision to create a new Vault Manga.
_Avoid_: Import destination, matched manga, sync target

**Import Target Hint**:
A device-local remembered association between a Manga Detail Screen manga and the Vault Manga it was previously added to.
_Avoid_: Vault identity, metadata match, source truth

**Import Duplicate Candidate**:
A selected chapter whose workflow-specific duplicate key appears to match a chapter already present in the chosen Import Target.
_Avoid_: Exact duplicate, checksum duplicate

**Vault Chapter Replacement**:
Replacing an existing Vault Chapter's vault-owned readable content through an explicit user-approved Add to Vault action for an Import Duplicate Candidate.
_Avoid_: Silent overwrite, duplicate import

**Vault Capture**:
The explicit act of adding manga or chapter content from an app source into the Content Vault.
_Avoid_: Auto backup, download, library add

**Vault Publish**:
Sending Bakalah-owned catalogue, metadata, or content changes to the Content Vault.
_Avoid_: Sync upload, save to cloud

**Vault Writer**:
The Bakalah installation currently allowed to publish authoritative changes to a Content Vault.
_Avoid_: Sync client, primary device, owner device

**Vault Writer Takeover**:
The explicit user-approved replacement of the Vault Writer for a Content Vault.
_Avoid_: Lock expiry, device sync, automatic failover

**Vault Catalogue Refresh**:
Updating Bakalah's local view of the Vault Catalogue without necessarily caching chapter content.
_Avoid_: Pull sync, download all, local source refresh

**Cache Eviction**:
Removing Cached Chapter content from the device while preserving its Vault Catalogue record.
_Avoid_: Delete chapter, unsync, remove from vault

**Vault Deletion**:
Permanently removing vault-owned manga content from the authoritative Vault Collection through a Vault-owned workflow.
_Avoid_: Soft delete, move to trash, delete download, remove from device, cleanup

**Vault Trash**:
Retired name for a recoverable holding area for vault-owned content removed from normal Vault browsing but still represented in the authoritative Vault Catalogue.
_Avoid_: Cache eviction, delete downloads, archive

**Cache-First Reading**:
Opening vault-owned chapter content by caching the chapter on the device before handing it to the reader.
_Avoid_: Streaming, remote reading, direct cloud read

**Vault Reader Session**:
A reading session for Vault Collection content that reuses Bakalah's reader experience while keeping progress, bookmarks, cache metadata, history, and tracker behavior owned by the Vault Feature rather than the Library.
_Avoid_: Library reading, fake manga session, synced reader session

**Cache Policy**:
The user's rules for which Content Vault chapters should remain cached on a device and which cached chapters may be evicted.
_Avoid_: Auto sync settings, download settings, cleanup settings

**Local Manga File Identity**:
The stable identity of a Local Manga as a user-owned local series folder, independent from its editable display title.
_Avoid_: Local manga title, folder title

**Vault Identity**:
The stable generated identity of vault-owned manga or chapter content, independent from titles, folder names, source URLs, and filenames.
_Avoid_: File identity, source URL identity, title identity

**Content Provenance**:
Optional information about where vault-owned content originally came from, without controlling its identity or readability.
_Avoid_: Source identity, canonical URL, required source

**Local Manga Metadata**:
User-owned descriptive information for a Local Manga, such as title, creators, description, genres, and publication status.
_Avoid_: Local details JSON, source metadata

**Series-Level Metadata**:
Local Manga Metadata that describes the manga as a whole rather than a specific chapter or local file operation.
_Avoid_: Chapter metadata, cover metadata, folder metadata

**Canonical Local Metadata File**:
The user-editable file that represents Series-Level Metadata for a Local Manga.
_Avoid_: Details JSON, chapter metadata file

**Browse Area**:
The feature area for remote sources, extension management, global search, and migration.
_Avoid_: Local Destination

**Intentional Source Browsing**:
A user-driven reading-discovery workflow where the user visits and browses chosen extension-backed sources when they want new content, instead of relying on bulk or background library chapter polling.
_Avoid_: Site browsing, auto updates replacement, manual updates

**Catalogue Latest Browsing**:
Browsing a source-provided list of recently added or modified catalogue entries.
_Avoid_: Library update, recent updates

**Legacy Updates Shortcut**:
An Android launcher or intent entry point that previously opened the Updates tab.
_Avoid_: Local Destination shortcut
