# Create Releases as Drafts

Superseded in part by [ADR 0002](./0002-auto-tag-merged-release-branches.md): release intent now comes from merging a reviewed release branch, not from a maintainer manually pushing a version tag.

Bakalah release automation should treat a pushed version tag as release intent, build and sign the release artifacts, and create a draft GitHub Release. The release stays private until a maintainer verifies the artifacts and manually publishes it, because signed APK distribution benefits from a final human checkpoint before users or updater clients can see the version.
