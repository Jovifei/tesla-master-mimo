# Project agent guidance

## Mandatory authority files (微信小程序提交与发布强制规范)

每次执行任何代码修改、配置调整、生产打包、Git 提交或微信公众平台上传前，**必须依序阅读并严格执行以下规范文件**：
1. `docs/PROJECT-REQUIREMENTS.md`：项目所有者 Jovi 的最高要求与开发军规（禁止虚假汇报、必须自证自检再交付、严禁混淆生产网络边界）。
2. `docs/SUBMISSION-PLAYBOOK.md`：项目提交与发布备忘录（完整的六步提交门禁：静态单测、配置审查、产物体积、模拟器自动全页面巡检截图、后台上传、Git 提交）。
3. `docs/SUBMISSION-INDEX.md`：项目提交流档目录与版本履历台账（每次上传后必须按模板追加流档）。
4. `docs/TROUBLESHOOTING-LESSONS.md`：项目处理经验与踩坑复盘（app.json 丢失根因、环境变量编译注入失效、生产与自托管 502 区分、CLI 自动化实机实景截图方案）。

> **核心纪律**：任何提交必须有完整的参数流档与真实模拟器/真机截图佐证。严禁在未亲眼确认界面正常渲染的情况下声称“已上线或已通过测试”。

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
