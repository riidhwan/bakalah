# Release Process

Bakalah releases are started by a maintainer pushing a version tag. Release automation builds and signs the release artifacts, creates a draft GitHub Release, and leaves final publication to a manual verification step.

## Version Rules

- Use a `vMAJOR.MINOR.PATCH` tag, such as `v0.19.9`.
- Confirm the intended next release version with the maintainer before changing release metadata. Do not infer whether the next release is major, minor, or patch from the previous tag alone.
- The tag without its leading `v` must match `versionName` in `app/build.gradle.kts`.
- Increase `versionCode` for every public release version.

## Release Checklist

Use this checklist for every public release:

- Confirm the intended release version with the maintainer.
- Confirm that no tag or GitHub Release already exists for that version.
- Create a release-prep branch; do not push release-prep commits directly to `main`.
- Update `app/build.gradle.kts` so `versionName` matches the tag without `v` and `versionCode` is higher than the previous public release.
- Move the relevant `Unreleased` entries in `CHANGELOG.md` into a non-empty release section for the confirmed version.
- Open a pull request for the release-prep branch.
- Run and record the pre-tag verification commands in the pull request.
- Merge the release-prep pull request only after review and required checks pass.
- Update local `main` to the merged release-prep commit.
- Create a signed annotated tag on the merged release-prep commit.
- Push only the release tag after confirming it points at the intended commit.
- Wait for release automation to create the draft GitHub Release.
- Verify all expected artifacts, artifact names, release notes, and install or upgrade behavior before publishing the draft.
- Publish the GitHub Release only after the draft passes verification.
- Run the post-publish smoke test.

## Pre-Tag Verification

Before creating the release tag, update `CHANGELOG.md`:

- Move relevant `Unreleased` entries into a new section for the release version.
- Use a heading in the form `## [vMAJOR.MINOR.PATCH] - YYYY-MM-DD`.
- Keep the existing changelog categories when they apply.
- Keep the release section non-empty; the workflow uses it to generate the draft GitHub Release notes.

## Repository Settings

Protect release tags so only maintainers can create or update `v*` tags. This keeps a pushed release tag aligned with Release Intent.

Maintainers who create release tags must follow `playbooks/release-tag-signing.md`.

Maintainers who manage APK signing secrets must follow `playbooks/apk-signing.md`.

## Release Trigger

Create and push a signed annotated tag for the release version:

```shell
git tag -s v0.19.9 -m "Bakalah v0.19.9"
git push origin v0.19.9
```

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

If a draft release fails verification, delete the draft, fix the issue, and rerun the release workflow or create a corrected tag as appropriate.

If a published release is bad, prefer publishing a new patch release that supersedes it. Delete or hide published assets only when the artifact is actively harmful, such as a signing, security, or install-breaking issue.
