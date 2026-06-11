# Content Vault v1 Readiness Checklist

Use this checklist before closing Content Vault v1 release-readiness work. Automated commands remain the source of truth for unit and migration coverage; the manual steps cover WebDAV provider behavior and UI states that are not practical to verify with local JVM tests.

## Automated Verification

- Run `scripts/gradlew-compact :domain:testDebugUnitTest`.
- Run `scripts/gradlew-compact :app:testDebugUnitTest`.
- Run `scripts/gradlew-compact verifySqlDelightMigration`.
- Run `scripts/gradlew-compact :app:spotlessCheck :domain:spotlessCheck :data:spotlessCheck`.

## WebDAV Provider Verification

Use Hetzner Storage Box or another WebDAV provider with equivalent support for `PROPFIND`, `MKCOL`, `GET`, and `PUT`.

- Configure a valid server URL, username, password or token, and empty Vault Root path.
- Test connection and confirm incomplete credentials, bad credentials, and unreachable host each produce a non-success setup result without logging secrets.
- Validate an empty Vault Root without initialization and confirm setup asks for initialization instead of writing a manifest.
- Initialize the empty Vault Root, then confirm `content-vault.json` exists remotely and the local configured Content Vault Identity is recorded.
- Reconnect to the initialized Vault Root and confirm it is accepted without changing the local Vault Identity.
- Point the same configuration at a non-empty mixed-use folder without `content-vault.json` and confirm it is rejected as a non-vault root.
- Point an already configured device at a different valid Vault Root and confirm the identity-change warning is shown before local index or cache state is reused.
- Replace the remote root manifest with an unknown newer Vault Layout version and confirm setup/catalogue refresh refuses it.

## Local-to-Vault Import Smoke

- Start from a Local Manga detail screen and open Local-to-Vault Import.
- Confirm all recognized CBZ chapters are selected by default.
- Confirm selected directory chapters show a conversion warning, convert to validated CBZ files before upload, and leave the original directory intact if conversion fails.
- Import into a new Vault Manga and confirm the Vault Destination shows the manga, chapter count, remote-only state, imported metadata, and initial cover when one was available.
- Repeat import for the same Local Manga and confirm the Import Target Hint selects the existing Vault Manga.
- Remove the hint or use another local entry with the same normalized title and confirm a single exact match selects the existing Vault Manga.
- Create two exact normalized title matches and confirm the import UI requires an explicit target choice.
- Confirm Import Duplicate Candidate chapters are detected by physical chapter file name basename, visibly warned, deselected by default, and still selectable.
- Trigger an upload failure and confirm the import remains retryable without partially published catalogue content.
- After a successful import, use Open in Vault and confirm navigation lands on the imported Vault Manga.

## Library-to-Vault Capture Smoke

- Start from a source-backed manga saved in the Library and confirm the Manga Detail Screen shows the under-title Import Target Hint row.
- Confirm source-backed manga that are not saved in the Library do not expose Add to Vault, and stubbed or unavailable sources fail early without staging chapters.
- Link an existing Vault Manga from the under-title row and confirm the hint persists; change the source identity through migration or equivalent test data and confirm the hint is shown as unavailable rather than silently reused.
- Select one downloaded chapter and one not-downloaded chapter, start Add to Vault, and confirm capture leaves the existing user download untouched while using capture-owned staging for the missing chapter.
- Confirm normal queued or running Download Queue entries for selected chapters are not attached to, cancelled, reordered, marked downloaded, or deleted by capture.
- Confirm capture-created staging is cleaned after each chapter attempt and does not appear as a normal Download or Cached Chapter.
- Capture into a new Vault Manga and confirm the current Manga Detail Screen cover is used when available, Library categories and tracker/read/bookmark state are not copied, and the resulting Vault Chapters are remote-only until separately cached.
- Confirm captured CBZ content is validated, tall-image splitting is applied regardless of the normal download preference, and page order matches reader/download order.
- Confirm Library-to-Vault duplicate indicators are based on normalized chapter titles for the selected Import Target, use calm vault-status styling, and require explicit replacement confirmation.
- Confirm replacement preserves the existing Vault Chapter identity, metadata, and catalogue position while updating content, integrity, and chapter-level provenance.
- Capture a large selection with one induced per-chapter source or upload failure and confirm successful chapters remain published, failed chapters are reported by title/category, and the final notification/transfer job shows added, replaced, and failed counts.
- Cancel an in-progress capture and confirm already published chapters remain, unprocessed chapters are not published, staging is cleaned best-effort, and the transfer job records cancellation with counts.
- Confirm failed capture jobs do not auto-retry and have no one-click retry; manually reselecting chapters starts a fresh Add to Vault action with fresh duplicate detection.
- Confirm new non-replacement captured chapters are ordered by latest-first natural normalized chapter title, not by source order or capture completion order.
- Confirm source manga/chapter URLs are stored only as private provenance/diagnostic data and do not appear in notifications, logcat, screenshots, or user-facing failure text.

## Vault Destination And Reading Smoke

- Refresh the Vault Catalogue and confirm manga are listed from the local Vault Index, including offline after the last successful refresh.
- Confirm search, filter, and sort controls update the visible Vault Collection without using Library membership.
- Confirm the summary shows remote Vault Storage usage and chapter rows/actions still expose cached versus vault-only state.
- Open a remote-only chapter and confirm a cache job appears before reader launch.
- Confirm a cached chapter opens only after size and checksum verification.
- Corrupt or remove a cached file and confirm reopening demotes it to Vault-only or marks an integrity fault, then exposes retry instead of launching unsafe content.
- Evict a cached chapter and confirm only the app-managed Vault Cache Directory file is removed, not Local Manga files or Downloads.
- Set a low local cache limit, read multiple chapters, and confirm the oldest read cached chapters are evicted while the active reader chapter is protected.
- Confirm offline reading works for already verified Cached Chapters and fails clearly for Vault-only chapters.

## Release Notes

- Document the WebDAV provider used for manual verification and any provider-specific path or quota caveats.
- Record screenshots for Vault setup, import preview with duplicate warning, Vault Destination states, cache job progress, reader launch, and cache eviction.
- Keep credentials, tokens, host-specific private paths, and full remote manifest bodies out of screenshots and issue or PR comments.
