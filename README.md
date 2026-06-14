# Bakalah

Bakalah is a personal Android comic reader forked from [Mihon](https://mihon.app/).

It is built around intentional reading and user-owned content — not background library polling.
It keeps Mihon's reading experience, extension-backed browsing, local file support, tracking, and backup.

The main addition is Vault: a longer-lived collection for comics you want to own, organize, and move between devices.

## What Bakalah Focuses On

- Reading comics comfortably on Android.
- Browsing sources when you choose to look for something, instead of relying on bulk background chapter checks.
- Keeping local files important for user-owned content already on your device.
- Using Vault as a longer-lived collection for comics you want to keep beyond one device.
- Adding selected chapters to Vault from local files or from series already saved in your Library.
- Reading cached Vault chapters and choosing what stays on the device.
- Keeping original local files and normal Downloads untouched when adding content to Vault.

## What Changed From Mihon

Bakalah does not include Mihon's old bulk/background Library Chapter Update System. There are no background library chapter checks, recent-updates screens or widgets, update badges, update notifications, or related scheduling settings.

You can still browse extension-backed sources, read from your Library, use local files, track reading progress, and use the reader experience inherited from Mihon.

## Vault

Vault is Bakalah's main feature direction. It is meant for comics you want to treat as yours, not just something temporarily downloaded from a source.

With Vault, Bakalah is working toward a collection where you can:

- keep comics in a user-owned Vault Collection
- organize comics with Vault Labels
- edit Vault Metadata
- cache chapters for reading on the current device
- move between devices through WebDAV-backed storage
- add chapters without rewriting your original local files or normal downloads

## Support

Bakalah is mainly maintained for personal use. Outside issues and pull requests may not be reviewed, accepted, or aligned with the maintainer's current direction.

This repository is not the upstream Mihon support channel. For Mihon itself, use Mihon's own website, repository, and community channels.

## Upstream and License

Bakalah is derived from Mihon and keeps the same Apache-2.0 license. See [LICENSE](./LICENSE).

The app does not host content and is not affiliated with content providers used through sources or extensions.
