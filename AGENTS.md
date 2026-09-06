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
