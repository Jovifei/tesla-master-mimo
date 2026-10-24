# Project agent guidance

<!-- BEGIN:codex-token-efficiency -->
## Token-efficient navigation

- Read the parent project contract at `E:\project\tesla_master\AGENTS.md`, then only the current task's required status/evidence files; do not preload historical handoffs.
- When `.codegraph/` exists, start with lightweight file map, symbol search, callers/callees, impact, and single-node lookup. Use broad context/explore only if these are insufficient.
- Use `rg` for exact identifiers, errors, resource keys, routes, and headings; read only matching ranges. Read a whole Kotlin/Swift/Go/TypeScript file only when its full behavior is necessary.
- Search `docs/` and `tasks/` by filename or heading first. Exclude APKs, build output, screenshots, recordings, databases, device dumps, and logs from context unless directly required.
- Run focused tests first and summarize only exit status, failing cases, and the relevant error region.
- Preserve original `com.matelink` data/configuration and distinguish mock, source-level, emulator, device, provider, and production evidence.
<!-- END:codex-token-efficiency -->


<!-- BEGIN:codex-project-hub:matelink:v1 -->
## Local project hub reporting (owner-authorized)
- Hub project_id: `matelink`. Registered root: `E:\project\tesla_master\app_mimo`. These are hub identities; do not change the project's own binding, goals, roadmap or gates.
- After a plan changes, a significant stage ends, tests finish, a blocker appears, and before stopping: report a short structured update. Do not report every chat message.
- Use `E:\project\codex-project-hub\integration\bound_report.py` (deterministic wrapper around the existing report.py CLI). Pass `--project matelink --worktree "ACTUAL-WORKTREE-ROOT"`; use the registered root only when actually working there. Branch is read from that worktree; detached HEAD and non-Git UNVERSIONED are explicit. Never reuse another worktree's identity/revision.
- Read the revision first: `python E:\project\codex-project-hub\integration\bound_report.py --project matelink --worktree "ACTUAL-WORKTREE-ROOT" revision`.
- Generate a new event: same prefix plus `template --agent "ACTUAL-AGENT-ID" --run "ACTUAL-RUN-ID" --revision N`; save stdout as UTF-8 to `E:\project\codex-project-hub\reports\matelink-EVENT-ID.json`. Fill current/next/blockers, stage_id and version/test/artifact references using the returned definition; then call the same prefix plus `send "ABSOLUTE-REPORT-FILE"`.
- Only claim tests you actually ran; label inherited/source-document claims and imports with source revision/date and importer role. Never claim another Agent called the CLI. Preserve pending planning (including PENDING_REMOTE_PLANNING); old connection warnings require current confirmation.
- Status reporting cannot change final goals/routes or grant approval. No owner.py apply from ordinary Agent reporting; claimed completion is not verified acceptance. No HTML edits, credentials, private photos, account data, raw sensitive logs or chat transcripts in reports.
- On failure keep the pending JSON, clearly report the error and path, and stop automatic retries. Retry an unchanged event_id/content after connectivity recovers; on 409 inspect latest revision and manually reconcile into a new event. Never silently overwrite or claim success without a successful CLI result.
- Full contract/commands: `E:\project\codex-project-hub\reports\matelink-INTEGRATION.md`. Reporting does not expand the project's execution permissions or start/continue another Agent session.
- This block applies after the Agent actually reads this file; old sessions are not assumed to reload it. Independent worktree copies must be checked separately.
<!-- END:codex-project-hub:matelink:v1 -->
