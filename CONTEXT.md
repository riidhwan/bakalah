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

**Local Tab**:
The top-level navigation destination for browsing manga stored in the user's local source directory.
_Avoid_: Local source tab, downloads

**Browse Area**:
The feature area for remote sources, extension management, global search, and migration.
_Avoid_: Local tab

**Catalogue Latest Browsing**:
Browsing a source-provided list of recently added or modified catalogue entries.
_Avoid_: Library update, recent updates

**Legacy Updates Shortcut**:
An Android launcher or intent entry point that previously opened the Updates tab.
_Avoid_: Local tab shortcut
