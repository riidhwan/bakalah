# Release Process

Bakalah releases are started by merging a reviewed release branch into `main`. Release automation validates the release metadata, creates a version tag, builds and signs the release artifacts, creates a draft GitHub Release, and leaves final publication to a manual verification step.

## Version Rules

- Use a `vMAJOR.MINOR.PATCH` tag, such as `v0.19.9`.
- Confirm the intended next release version with the maintainer before changing release metadata. Do not infer whether the next release is major, minor, or patch from the previous tag alone.
- Name the release branch `release/MAJOR.MINOR.PATCH`, such as `release/0.19.9`.
- The release branch version must match `versionName` in `app/build.gradle.kts`.
- The tag without its leading `v` must match `versionName` in `app/build.gradle.kts`.
- Increase `versionCode` for every public release version.

## Release Checklist

Use this checklist for every public release:

- Confirm the intended release version with the maintainer.
- Confirm that no tag or GitHub Release already exists for that version.
- Create a release branch named `release/MAJOR.MINOR.PATCH`; do not push release preparation commits directly to `main`.
- Update `app/build.gradle.kts` so `versionName` matches the tag without `v` and `versionCode` is higher than the previous public release.
- Move the relevant `Unreleased` entries in `CHANGELOG.md` into a non-empty release section for the confirmed version.
- Open a pull request from the release branch to `main`.
- Wait for the release metadata check to verify the branch name, `versionName`, changelog section, and absence of an existing tag or GitHub Release for the version.
- Run and record any additional pre-release verification commands in the pull request.
- Merge the release pull request only after review and required checks pass.
- Wait for release automation to create the annotated release tag on the merged revision.
- Wait for release automation to create the draft GitHub Release.
- Verify all expected artifacts, artifact names, release notes, and install or upgrade behavior before publishing the draft.
- Publish the GitHub Release only after the draft passes verification.
- Run the post-publish smoke test.

## Release Metadata Verification

Before merging the release branch, update `CHANGELOG.md`:

- Move relevant `Unreleased` entries into a new section for the release version.
- Use a heading in the form `## [vMAJOR.MINOR.PATCH] - YYYY-MM-DD`.
- Keep the existing changelog categories when they apply.
- Keep the release section non-empty; release automation uses it to generate the draft GitHub Release notes.

For a release branch named `release/0.19.9`, release metadata must resolve to:

- branch version `0.19.9`
- release tag `v0.19.9`
- Android `versionName = "0.19.9"`
- a non-empty `CHANGELOG.md` section for `v0.19.9`

## Repository Settings

Protect release tags so only maintainers and the release GitHub App can create `v*` tags. This keeps generated release tags aligned with Release Intent.

Configure release automation with a GitHub App installed only on this repository. The app must have `Contents: Read and write` permission, and the repository must provide `RELEASE_APP_ID` as an Actions variable and `RELEASE_APP_PRIVATE_KEY` as an Actions secret. Automated release tags are annotated but not GPG-signed.

Maintainers who manage APK signing secrets must follow `playbooks/apk-signing.md`.

## Release Trigger

Merge the reviewed release pull request into `main`. If the pull request source branch is named `release/0.19.9`, release automation creates the annotated tag `v0.19.9` on the merged revision.

The release workflow accepts only `vMAJOR.MINOR.PATCH` tags, verifies that the tag matches the Android `versionName`, and fails before release builds if `CHANGELOG.md` does not have a non-empty section for the tag.

## Release Artifacts

Each release must include this complete release artifact set:

- `bakalah-{tag}.apk`
- `bakalah-{tag}-foss.apk`
- `bakalah-arm64-v8a-{tag}.apk`
- `bakalah-armeabi-v7a-{tag}.apk`
- `bakalah-x86-{tag}.apk`
- `bakalah-x86_64-{tag}.apk`

Use `bakalah-{tag}.apk` as the default recommendation for users who are unsure which file to install.

## Publication

The workflow creates the GitHub Release as a draft and fills its notes from the matching `CHANGELOG.md` release section. Before publishing it, verify that:

- All six expected artifacts are attached.
- The artifact names use the intended release tag.
- The release APK installs or upgrades successfully on a test device.
- The FOSS APK installs or upgrades successfully when testing that variant.
- The release notes match the release section in `CHANGELOG.md`.

Publish the draft only after these checks pass.

## Post-Publish Smoke Test

After publishing the GitHub Release, verify that:

- The public release page shows the intended release version.
- The default APK is downloadable from the public release page.
- The app updater can see the new release when updater behavior is expected for that version.

## Bad Release Handling

If a draft release fails verification because of transient infrastructure or signing issues, keep the tag and rerun the release workflow for that tag after fixing the issue. If the failure is due to bad release metadata that somehow passed checks and no draft or published release exists, delete the tag, fix the issue through a new release pull request, and let automation recreate the tag.

If a published release is bad, prefer publishing a new patch release that supersedes it. Delete or hide published assets only when the artifact is actively harmful, such as a signing, security, or install-breaking issue.
