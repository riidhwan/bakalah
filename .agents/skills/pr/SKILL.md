---
name: pr
description: Use when preparing a Bakalah pull request end to end, including branch setup, changelog decisions, commits, pushes, and PR creation. Covers repo-specific Git hygiene, branch naming, release PR rules, PR bodies, and safety checks.
metadata:
  short-description: Prepare Bakalah commits and PRs
---

# Bakalah PR Workflow

Use this skill whenever the user invokes `$pr` or asks to prepare a commit, create or update a branch, push work, draft or open a pull request, prepare release PR changes, or decide whether a changelog entry is needed.

Invoking `$pr` means the user wants the normal PR flow completed end to end: create or switch to a suitable branch, make the needed commit or commits, push the branch, and open the pull request. Do not stop to ask separately whether to commit, push, or open the PR unless the user explicitly requested a dry run, asked for only part of the flow, or a safety rule below requires confirmation.

## Hard Safety Rules

- Treat explicit `$pr` invocation as authorization to commit, push, and open a normal PR for the current requested work.
- Do not commit, push, or open a PR when the user asked for a dry run, review, plan, status check, or partial preparation only.
- Never open a PR from `main`; create or switch to a properly named branch first.
- Preserve unrelated user changes. Inspect the worktree before staging or committing and stage only files that belong to the requested work.
- Stop and ask before any force push or history rewrite of a branch that already exists on `origin`.
- Do not use destructive Git commands such as `git reset --hard` or `git checkout --` unless the user explicitly requested that exact operation.

## Default PR Flow

1. Inspect local state with `git status --short --branch`.
2. Run `git fetch origin main`.
3. Identify the requested work and separate it from unrelated user changes.
4. If currently on `main`, create a properly named branch before committing.
5. If already on a work branch, ensure it is current with `origin/main`; prefer rebasing work branches onto `origin/main`.
6. Check whether the current branch already exists on `origin` before any rebase, force push, or history rewrite.
7. Review the diff and stage only relevant files.
8. Decide whether `CHANGELOG.md` needs an `Unreleased` entry.
9. Run verification appropriate to the change.
10. Commit the relevant changes with a short imperative subject.
11. Push the branch.
12. Open the PR with the required title/body details.

## Required Checks

1. Inspect local state with `git status --short --branch`.
2. Run `git fetch origin main`.
3. Ensure the work branch is current with `origin/main`; prefer rebasing work branches onto `origin/main`.
4. Check whether the current branch already exists on `origin` before any rebase, force push, or history rewrite.
5. Review the diff and stage only relevant files.
6. Run verification appropriate to the change and record the exact commands for the PR body.

For Gradle, test, build, CI, release, or other noisy command workflows, also follow `$compact-operations`; prefer `scripts/gradlew-compact` for Gradle commands.

## Branch Names

Normal work branches must use:

```text
<type>/<short-kebab-summary>
```

Allowed prefixes:

- `feature/`
- `fix/`
- `docs/`
- `refactor/`
- `chore/`
- `cleanup/`
- `ci/`
- `patch/`

Issue-backed branches should use:

```text
<type>/issue-N-short-kebab-summary
```

Release branches must be exactly:

```text
release/MAJOR.MINOR.PATCH
```

## Commits

- Use short, imperative commit subjects, such as `Add vertical chapter navigator` or `Drop kotlinx-collections-immutable usage`.
- For patch release PRs that combine an urgent fix with release metadata, use the normal fix summary as the commit subject, such as `Fix obsolete extension detection`; do not use a generic release-prep subject like `Prepare 0.31.1 patch release`.
- Keep commits focused.
- Commit only files that belong to the user-requested operation.
- If unrelated changes exist in files you must touch, read them carefully and work with them instead of reverting them.

## Changelog

For normal non-release PRs, add a `CHANGELOG.md` `Unreleased` entry only for user-facing features, fixes, removals, behavior changes, or visible UX changes.

Skip changelog edits for pure refactors, tests, formatting, docs-only changes, tooling-only changes, and internal cleanup.

Use these categories when adding entries:

- `Added`
- `Changed`
- `Improved`
- `Removed`
- `Fixed`
- `Other`

## Release Branches And Release PRs

Assume a normal non-release PR unless the user explicitly asks for a release, names a `release/MAJOR.MINOR.PATCH` branch, or asks to change release metadata.

Confirm the intended release version with the maintainer before changing release metadata. Do not infer whether the next release is major, minor, or patch from the previous tag alone.

For every release branch:

- Branch name must be exactly `release/MAJOR.MINOR.PATCH`.
- Branch version must match Android `versionName` in `app/build.gradle.kts`.
- Public release `versionCode` must increase.
- `CHANGELOG.md` must contain a non-empty `## [vMAJOR.MINOR.PATCH] - YYYY-MM-DD` section.
- Move relevant `Unreleased` entries into the versioned release section.
- Do not push release preparation commits directly to `main`.
- Do not run GitHub workflow or release validation scripts such as `.github/scripts/validate-release-branch.sh` or `.github/scripts/validate-release-inputs.sh` during local agent preparation, even without remote-check flags. Inspect edited files directly and let PR CI run release metadata validation, including duplicate tag and GitHub Release checks.

Release PR title rules:

- Major/minor release PR title: `Release <version>`, for example `Release 0.31.0`.
- Patch release PR title: use the normal change-summary title, because patch PRs combine urgent fix code and release prep in one PR.

## PR Body

Fill PR bodies with:

- Summary of the user-visible or technical change.
- Linked issue when applicable.
- Tests/checks run, with exact commands and important failures or skipped checks.
- Brief self-review notes about risk, migrations, changelog, docs, screenshots, and rollout concerns.
- Screenshots only for UI changes; verify relevant themes and tablet mode for UI work.

CI expects formatting, Detekt, unit tests, and SQLDelight migration checks to pass when relevant to the change. Choose the verification set by risk and touched areas, and state any concrete blocker or residual risk if a check was not practical.
