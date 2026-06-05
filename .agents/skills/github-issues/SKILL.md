---
name: github-issues
description: Use when creating or editing GitHub issues, decomposing work into parent issues and sub-issues, starting issue-backed work, opening issue-backed PRs, managing blocking relationships, or auditing issue relationships before handoff.
---

# GitHub Issues

Use this skill for issue creation, issue decomposition, issue-backed implementation, PRs for issue-backed work, native sub-issue relationships, native blocking relationships, and final relationship audits.

## Core Rules

- GitHub-native relationships are the source of truth.
- Use native sub-issues for work that is part of a larger goal.
- Use native blocked-by/blocking relationships for real sequencing dependencies.
- Do not substitute issue-body prose, checklists, or `Refs #N` links for native relationships when native relationships are needed.
- If a required native relationship cannot be created, read, or verified through available GitHub tooling, stop and report the limitation. Include the exact relationship that must be created manually.
- If repository policy requires an issue for the work and issue lookup or creation is unavailable, stop before implementation for new work and stop before opening a PR for completed work. Report the blocker to the user instead of creating an untracked non-trivial PR.
- PR bodies should contain actual issue references only. Do not write "None" as a linked-issue substitute when an issue is required, and do not include agent/tooling failure notes such as failed issue creation attempts in PR descriptions.
- Link ADRs and relevant docs from issues when they constrain the work. If none apply, say `None identified`.
- Keep each sub-issue independently reviewable and able to leave `master` buildable, testable, deployable, and safe for normal users.

## Choose a Mode

### Creation or Decomposition

Use when the user asks to create issues, break work down, plan a larger change, or add blockers.

1. Decide whether the work is standalone or needs a parent issue.
2. Use a parent issue for an initiative, migration, feature set, broad refactor, risky behaviour change, persistence/data-flow change, or architectural change.
3. Use native sub-issues for independently shippable slices. Keep tiny same-PR tasks as checklist items.
4. Use native blocking relationships only for hard dependencies, not preference ordering.
5. Add ADRs/docs in `Related Decisions` where applicable.
6. Create and link sub-issues sequentially, especially for large decompositions. GitHub can apply secondary rate limits when creating or mutating issue relationships too quickly.
7. Keep issue-body `Slices` and `Dependencies` sections as human summaries only. Update them when the planned decomposition, dependency rationale, or completion criteria changes, but never use them as a substitute for native relationships.
8. Verify the issue graph after creation: parent/sub-issue links and blocked-by/blocking links.

### Issue Work

Use when the user asks to implement, fix, review, branch, commit, push, or open a PR for an existing issue.

Before implementation:

1. Inspect the target issue body, labels, state, comments, and checklist.
2. Inspect the parent issue, if any. A `404` from the parent endpoint means the issue has no native parent; do not treat that as a failure by itself.
3. Inspect child/sub-issues if the target is a parent or belongs to a parent.
4. Inspect native blocked-by/blocking relationships.
5. Inspect linked PRs, branches, and recent commit context when visible.
6. Read ADRs explicitly linked from the issue, parent issue, comments, or PR.
7. If no ADR is linked but the work touches architecture, persistence, routing, data flow, feature flags, or workflow policy, inspect `docs/adr/` and decide whether any apply.
8. Read relevant repository docs named by `AGENTS.md` for the area being changed.
9. State the big picture before editing: parent goal, this issue's slice, blockers, and related ADRs.

Do not start implementation if a blocking issue is unresolved and the current work cannot safely proceed.

## Relationship Rules

Use sub-issues for "part of" relationships:

- Parent: end goal, context, constraints, completion criteria.
- Sub-issue: an independently reviewable work slice.

Use blocked-by/blocking for "must happen first" relationships:

- A sub-issue can be blocked by a sibling sub-issue.
- A standalone issue can block or be blocked by another standalone issue.
- A parent can have dependency notes, but native blocking relationships should still identify the issues that create the dependency.

Use both relationships when both are true.

## Current GitHub CLI / API Playbook

As of GitHub CLI 2.83.2, `gh issue` does not provide first-class sub-issue or dependency subcommands. Prefer the scripts in `scripts/` for native relationship work so command approvals can stay narrow and task-shaped. Use raw `gh api` only when a script does not cover the required operation, and keep the raw commands below as reference for script behavior and fallback handling.

Run script paths relative to this skill directory:

```sh
.agents/skills/github-issues/scripts/issue-id.sh ISSUE_NUMBER
.agents/skills/github-issues/scripts/list-subissues.sh PARENT_ISSUE_NUMBER
.agents/skills/github-issues/scripts/get-parent.sh CHILD_ISSUE_NUMBER
.agents/skills/github-issues/scripts/add-subissue.sh [--replace-parent] PARENT_ISSUE_NUMBER CHILD_ISSUE_NUMBER
.agents/skills/github-issues/scripts/remove-subissue.sh PARENT_ISSUE_NUMBER CHILD_ISSUE_NUMBER
.agents/skills/github-issues/scripts/reorder-subissue.sh PARENT_ISSUE_NUMBER CHILD_ISSUE_NUMBER --after|--before SIBLING_ISSUE_NUMBER
.agents/skills/github-issues/scripts/list-blocked-by.sh ISSUE_NUMBER
.agents/skills/github-issues/scripts/list-blocking.sh ISSUE_NUMBER
.agents/skills/github-issues/scripts/add-blocked-by.sh ISSUE_NUMBER BLOCKING_ISSUE_NUMBER
.agents/skills/github-issues/scripts/remove-blocked-by.sh ISSUE_NUMBER BLOCKING_ISSUE_NUMBER
```

Script rules:

- Scripts validate issue-number arguments and resolve REST integer IDs internally when needed.
- Mutation scripts verify the resulting relationship by reading the relevant relationship endpoints.
- `get-parent.sh` prints `null` when GitHub returns `404`, which means the issue has no native parent.
- Keep read-only scripts (`issue-id.sh`, `list-*`, `get-parent.sh`) separate from mutation scripts for approval purposes.

Important identifiers:

- Issue numbers are the human `#123` values used in URLs.
- REST issue IDs are integer database IDs required by sub-issue and dependency bodies.
- GraphQL node IDs are strings and are not accepted by the REST sub-issue endpoints.

Inspect the target issue, including closing PR references exposed by `gh issue view`:

```sh
gh issue view ISSUE_NUMBER \
  --json number,title,state,labels,assignees,milestone,body,comments,closedByPullRequestsReferences
```

Get an issue's REST integer ID:

```sh
gh api 'repos/{owner}/{repo}/issues/123' --jq '.id'
```

Create a new issue and capture the fields needed for relationship commands:

```sh
gh api 'repos/{owner}/{repo}/issues' \
  -f title='Slice title' \
  -f body='Issue body in the repository template format.' \
  --jq '{number, id, html_url}'
```

Add an existing issue as a native sub-issue of parent `#PARENT_NUMBER`:

```sh
gh api -X POST 'repos/{owner}/{repo}/issues/PARENT_NUMBER/sub_issues' \
  -H 'Accept: application/vnd.github+json' \
  -F sub_issue_id=SUB_ISSUE_REST_ID
```

Move a sub-issue from another parent to this parent only when that is intentional:

```sh
gh api -X POST 'repos/{owner}/{repo}/issues/PARENT_NUMBER/sub_issues' \
  -H 'Accept: application/vnd.github+json' \
  -F sub_issue_id=SUB_ISSUE_REST_ID \
  -F replace_parent=true
```

List native sub-issues for a parent:

```sh
gh api 'repos/{owner}/{repo}/issues/PARENT_NUMBER/sub_issues?per_page=100' \
  --jq '.[] | {number, id, title, state, html_url}'
```

Get a child issue's native parent:

```sh
gh api 'repos/{owner}/{repo}/issues/CHILD_NUMBER/parent' \
  --jq '{number, id, title, state, html_url}'
```

If this returns `404`, treat the child as having no native parent. If an expected parent is missing, stop and report the missing relationship.

Remove a native sub-issue relationship:

```sh
gh api -X DELETE 'repos/{owner}/{repo}/issues/PARENT_NUMBER/sub_issue' \
  -H 'Accept: application/vnd.github+json' \
  -F sub_issue_id=SUB_ISSUE_REST_ID
```

Reorder a sub-issue under a parent:

```sh
gh api -X PATCH 'repos/{owner}/{repo}/issues/PARENT_NUMBER/sub_issues/priority' \
  -H 'Accept: application/vnd.github+json' \
  -F sub_issue_id=SUB_ISSUE_REST_ID \
  -F after_id=PREVIOUS_SUB_ISSUE_REST_ID
```

Use `before_id=NEXT_SUB_ISSUE_REST_ID` instead of `after_id` when positioning before another sub-issue.

Add a native blocked-by dependency, meaning `ISSUE_NUMBER` is blocked by `BLOCKING_ISSUE_REST_ID`:

```sh
gh api -X POST 'repos/{owner}/{repo}/issues/ISSUE_NUMBER/dependencies/blocked_by' \
  -H 'Accept: application/vnd.github+json' \
  -F issue_id=BLOCKING_ISSUE_REST_ID
```

List dependencies an issue is blocked by:

```sh
gh api 'repos/{owner}/{repo}/issues/ISSUE_NUMBER/dependencies/blocked_by?per_page=100' \
  --jq '.[] | {number, id, title, state, html_url}'
```

List issues that an issue is blocking:

```sh
gh api 'repos/{owner}/{repo}/issues/ISSUE_NUMBER/dependencies/blocking?per_page=100' \
  --jq '.[] | {number, id, title, state, html_url}'
```

Remove a native blocked-by dependency:

```sh
gh api -X DELETE 'repos/{owner}/{repo}/issues/ISSUE_NUMBER/dependencies/blocked_by/BLOCKING_ISSUE_REST_ID' \
  -H 'Accept: application/vnd.github+json'
```

Operational notes:

- After any relationship mutation, verify by reading both directions where possible: list the parent's sub-issues, get the child's parent, and list dependency endpoints for each issue involved.
- For expected relationships, empty arrays from list endpoints mean the relationship is absent and must be created or reported.
- For mutation commands, treat `403`, `404`, `410`, and `422` as failures unless the user explicitly asked to tolerate the missing relationship. Stop and report the exact operation that failed.
- For cross-repository sub-issues, verify owner/repository constraints before mutating. The REST add-sub-issue endpoint requires the sub-issue to belong to the same repository owner as the parent issue, and cross-repository cases may need manual UI handling or a different API path.
- Preserve issue metadata intentionally. When creating sub-issues, set or copy labels, assignees, milestones, issue type, and project fields only when the parent issue, repository policy, or user request calls for them; do not assume all parent metadata should be inherited.

## Templates

### Parent Issue

```markdown
## Outcome

What should be true when the whole initiative is done.

## Context

Why this matters, relevant constraints, and links to ADRs/docs.

## Scope

What is included.

## Out of Scope

What is intentionally excluded.

## Slices

Native sub-issues are the source of truth. Use this section only to explain the planned decomposition.

## Dependencies

Native blocked-by/blocking relationships are the source of truth. Summarize only when helpful.

## Review Size

Estimated files:
Estimated changed lines:
Architectural layers:
Fits normal PR budget:
Split plan:

## Completion Criteria

What must be true before closing the parent.
```

### Sub-Issue or Standalone Work Issue

```markdown
## Outcome

What this slice changes.

## Parent Context

Parent issue and the larger goal in one or two sentences. Use `Not applicable` for standalone issues.

## Related Decisions

Linked ADRs/docs that constrain the work, or `None identified`.

## Scope

What this issue includes.

## Out of Scope

What this issue must not touch.

## Dependencies

Native blocked-by/blocking relationships are the source of truth. Summarize only when helpful.

## Review Size

Estimated files:
Estimated changed lines:
Architectural layers:
Fits normal PR budget:
Split plan:

## Verification

Commands, checks, or review evidence expected before completion.
```

## Handoff Audit

Before reporting completion for issue-backed work, check and state:

- Whether the issue is a parent, sub-issue, or standalone issue.
- Whether all required native sub-issue relationships exist.
- Whether all required native blocked-by/blocking relationships exist.
- Whether linked ADRs/docs were followed or updated.
- Whether any follow-up issue is needed.
- Whether the parent issue can close, must stay open, or needs updated completion criteria. Before closing a parent, list all native sub-issues and verify they are closed or intentionally out of scope.

Do not post issue completion comments while changes are only local and unpushed. Draft the exact comment for the user when useful.
