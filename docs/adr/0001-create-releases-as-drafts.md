# Create Releases as Drafts

Bakalah release automation should treat a pushed version tag as release intent, build and sign the release artifacts, and create a draft GitHub Release. The release stays private until a maintainer verifies the artifacts and manually publishes it, because signed APK distribution benefits from a final human checkpoint before users or updater clients can see the version.
