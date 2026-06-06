# Model Content Vault as Separate Feature

Bakalah will model user-owned remote manga content as a Content Vault with a versioned Vault Layout, Vault Catalogue, local Vault Index, chapter-level Local Content Cache, Cache-First Reading, Vault Reading State, and Vault Trash. The Vault Feature is separate from Local Source, Library, and external tracker integration: Local Source remains file-directory browsing, Library remains the active reading collection, and the Vault Surface manages vault-owned content through explicit capture, publish, catalogue refresh, cache, eviction, deletion, restore, and trash-emptying operations. WebDAV is the first storage transport, with Hetzner Storage Box treated as an initial Vault Storage Provider rather than the domain model, because blind folder synchronization cannot express remote-only catalogue entries, cache policy, stable vault identity, metadata authority, safe deletion semantics, staged integrity verification, size-limited cache eviction, or explicit single-writer ownership with manual takeover.

Local Content Cache lives in an app-managed Vault Cache Directory rather than the user-managed Local Source directory, so cache eviction does not masquerade as deletion of Local Manga files.

For v1, Vault Import is limited to content Bakalah can already read through existing Local Manga files; arbitrary remote-folder scanning and online-source Vault Capture are out of scope. Imported chapter files are preserved in their existing supported format. Later page-based source captures should be packaged as CBZ Captured Chapter Files before publishing without recompressing or transforming page bytes.

Vault Import from existing Local Manga files is copy-only and never moves or deletes the user's Local Manga files. Imported source files do not count as Local Content Cache unless Bakalah separately caches the vault-owned chapter into the app-managed Vault Cache Directory.

Local-to-Vault Import uses existing Local Source recognition for v1 and supports chapter selection. Repeated imports first use a device-local Import Target Hint when available, then exact normalized title matching to find a single existing Vault Manga; if none exists, Bakalah creates a new Vault Manga, and if multiple exist, the user must choose or create new. Exact duplicate chapters are skipped by default and possible duplicates are flagged rather than overwritten silently.

Vault workflows never offer to delete the user's original Local Manga files; any cleanup of those files remains outside Bakalah's vault behavior.

Vault Metadata edits update vault manifests and the local Vault Index in v1; they do not rewrite imported or cached chapter content files. Vault Covers are separate vault-owned catalogue assets rather than data derived from cached chapter availability.

Vault network work is explicit and queue-visible in v1: catalogue refresh, import publish, cache, metadata publish, and eviction operations are user-initiated rather than periodic background synchronization. The minimum v1 slice supports one configured Content Vault and includes connecting to WebDAV Vault Storage, initializing or validating a Vault Root, importing existing Local Manga files, refreshing the Vault Catalogue into the Vault Index, browsing the Vault Surface, editing basic Vault Metadata and Vault Labels, cache-first reading, manual cache eviction, size-limited cache policy, staged transfers, and content integrity checks. Vault Deletion and Vault Trash are designed safety concepts but are deferred beyond the minimum v1 slice.

The Vault Catalogue uses a hybrid manifest layout: a root manifest for vault version, revision, writer ownership, summaries, and pointers, plus per-manga manifests for detailed metadata, chapter records, integrity data, provenance, and Vault Labels. Vault Reading State is always device-local and is not part of the remote Content Vault.

Each Content Vault has a stable generated Content Vault Identity in its root manifest. Reconfiguring WebDAV URL or path must validate that identity before reusing the local Vault Index or Local Content Cache state.

The Vault Index should be persisted in dedicated Vault tables in the existing SQLDelight database, keeping Vault models separate from existing Library manga tables.

Vault manga and chapter concepts should use separate domain models from the existing Manga and Chapter models, with adapters only where existing reader infrastructure requires them.

Vault reading should reuse existing reader UI and infrastructure where possible after Cache-First Reading has produced a verified cached chapter, while Vault Reading State remains owned by the Vault Feature.
