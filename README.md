# Bakalah

Bakalah is a personal Android comic reader fork derived from [Mihon](https://mihon.app/).

While it still builds on Mihon's reader, source, library, tracker, backup, and local-content foundations, Bakalah is moving in a different direction. The fork removes the bulk/background library update workflow and puts more weight on intentional browsing: visiting chosen extension-backed sources when looking for new content, closer to how someone would browse a site directly.

Bakalah also focuses more on user-owned local content and cloud-backed content. Cloud-backed local content is still being built; today the repository contains early WebDAV setup and storage model work, with the intended experience documented in [Content Vault PRD](./docs/content-vault-prd.md).

## Project Direction

- Personal Android comic reader fork with its own app name, icon, application ID, updater target, and release process.
- No Library Chapter Update System: no bulk/background chapter polling for library entries, recent-updates screens or widgets, update badges, update notifications, or related scheduling/settings.
- Intentional Source Browsing remains central: users visit extension-backed sources when they want to find new content.
- Local content remains important, with ongoing work toward a Content Vault for cloud-backed user-owned content.
- Internal source package names still include Mihon and Tachiyomi namespaces where changing them would not affect the installed app identity.

For project-specific language, see [CONTEXT.md](./CONTEXT.md). For architecture and module ownership, see [docs/architecture.md](./docs/architecture.md).

## Build Locally

Use the Gradle wrapper from the repository root:

```shell
./gradlew assembleDebug
```

Useful checks:

```shell
./gradlew testDebugUnitTest
./gradlew verifySqlDelightMigration
./gradlew spotlessCheck
```

Release preparation and publication are documented in [docs/release-process.md](./docs/release-process.md).

## Contributing and Support

Bakalah is mainly maintained for personal use. There is no guarantee that outside issues or pull requests will be reviewed, accepted, or aligned with the maintainer's current direction.

This repository is not the upstream Mihon support channel. For Mihon itself, use Mihon's own website, repository, and community channels.

## Upstream and License

Bakalah is derived from Mihon and keeps the same Apache-2.0 license. See [LICENSE](./LICENSE).

The app does not host content and is not affiliated with content providers used through sources or extensions.
