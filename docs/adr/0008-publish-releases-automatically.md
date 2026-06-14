# Publish Releases Automatically

Bakalah release automation should publish the GitHub Release directly after the tag-triggered release workflow validates release metadata, builds and signs all release APKs, and verifies the complete artifact set. This supersedes the draft-only publication checkpoint from [ADR 0001](./0001-create-releases-as-drafts.md).

Release intent remains a reviewed `release/MAJOR.MINOR.PATCH` pull request merged into `main`, as described in [ADR 0002](./0002-auto-tag-merged-release-branches.md). Because that review and the release workflow now provide the release gate, the generated GitHub Release should be visible to users and updater clients as soon as artifact verification succeeds. Post-publication smoke testing still verifies the public release page, default APK download, install or upgrade behavior, and updater visibility.
