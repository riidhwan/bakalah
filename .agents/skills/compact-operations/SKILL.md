---
name: compact-operations
description: Use when running command-driven workflows where tool output can grow quickly, especially GitHub, CI, release, logs, search, test, build, or status checks. Prefer compact commands, targeted queries, summaries, and bounded output; avoid watch modes, broad logs, full JSON blobs, and repeated noisy polling unless specifically needed.
metadata:
  short-description: Keep command output compact
---

# Compact Operations

Keep operational workflows token-efficient by controlling command output before it enters the transcript.

## Core Rules

- Prefer machine-readable status fields over human-formatted full reports.
- Ask for the smallest useful field set with `--json`, `--jq`, `rg -l`, targeted paths, or command-specific filters.
- Avoid watch modes in long sessions. Poll compact status manually only when needed.
- Fetch full logs, full diffs, full asset lists, or broad search results only after a failure or when exact details are required.
- If a command may be noisy, cap it with tool `max_output_tokens` and summarize the result instead of expanding every line.
- Check local session token usage during long operational work and warn when the session is getting expensive.
- After planning or diagnosis produces a large transcript, recommend starting a fresh implementation session.

## Token Usage Watch

During long command-heavy work, check token usage at natural boundaries:

- after a broad search, large diff, log fetch, CI polling, or release verification step
- before continuing from planning into implementation
- before doing "one more check" in an already long session
- every 10-15 tool calls during prolonged GitHub, test, or debugging workflows

Compact current-project check:

```sh
sqlite3 -header -column ~/.codex/state_5.sqlite "select datetime(updated_at,'unixepoch','localtime') as updated, tokens_used, substr(title,1,80) as title from threads where cwd='$(pwd)' order by updated_at desc limit 1;"
```

Warn the user when:

- the current session exceeds 5M tokens
- one short follow-up appears to add more than 250k tokens
- the same transcript is carrying repeated CI/log/search output
- the session is mixing planning, implementation, and verification after substantial tool output

Suggested warning:

```text
Token check: this session is at about N tokens. The next small checks may be expensive because the transcript is large. I recommend summarizing the current state and starting a fresh session before continuing.
```

Do not run token checks after every command; that creates noise. Use them as checkpoints around operations likely to expand context.

## GitHub And CI

Use compact checks first:

```sh
gh pr view PR --json state,merged,mergeCommit,url,headRefName,baseRefName
gh pr checks PR --json name,state,conclusion,link
gh run view RUN_ID --json status,conclusion,url,createdAt,updatedAt
gh run view RUN_ID --json jobs --jq '.jobs[] | {name,status,conclusion,url}'
gh run list --workflow WORKFLOW --limit 5 --json databaseId,status,conclusion,createdAt,displayTitle,url
gh release view TAG --json isDraft,isPrerelease,name,tagName,url
```

Avoid by default in long sessions:

```sh
gh pr checks PR --watch
gh run watch RUN_ID
gh run view RUN_ID --log
gh run view RUN_ID --log-failed
gh release view TAG --json assets
```

Use noisy forms only when compact status shows a failure or the user explicitly needs exact logs/assets. For failed CI, prefer one failed job at a time:

```sh
gh run view RUN_ID --json jobs --jq '.jobs[] | select(.conclusion=="failure") | {databaseId,name,url}'
gh run view RUN_ID --job JOB_ID --log-failed
```

## Search And File Reads

- In large repos, do not start exploration with `rg -n PATTERN .` or an unqualified search root of `.`.
- Start with filenames or counts: `rg -l PATTERN PATH`, `rg --count PATTERN PATH`, `rg --files PATH`.
- Search targeted directories before repo-wide searches. If the relevant area is unknown, discover candidate paths first with `rg --files | rg 'name|feature|domain'` or inspect module names.
- Avoid broad `rg -n` across large repos unless line-level matches are required.
- When line-level matches are needed, run `rg -n` only against candidate files or specific modules.
- Read bounded ranges with `sed -n` or `nl -ba ... | sed -n`; do not dump whole large files.
- For diffs, start with `git diff --stat` or `git diff --name-only`, then inspect specific files.

Bad first move:

```sh
rg -n "import.*vault|vault.*import|Import.*Vault|Vault.*Import|ImportToVault|to vault" .
```

Better:

```sh
rg --files | rg -i 'vault|import|source|local'
rg -l -i "import.*vault|vault.*import|to vault" app domain data source-local presentation-* 
rg -n -i "import.*vault|vault.*import|to vault" path/to/candidate/File.kt
```

## Status Updates

When output was intentionally kept compact, tell the user the key state and the command shape, not raw command dumps. If a full log is needed, explain why before fetching it.
