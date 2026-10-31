# Codex with ChatGPT 本地直连核验

日期：2026-10-31

## 结论

Codex with ChatGPT 通过只读连接直接读取当前工作区的文件、搜索结果、图片、Git 信息和已记录的测试结果。仓库内容不会上传到 ChatGPT Project；GitHub 只承担远端仓库、Pull Request 和 Actions CI。

本机连接器名称为 `Codex with ChatGPT · app_mimo`，工作区根目录配置为 `E:\project\tesla_master\app_mimo`。本次 `doctor` 检查中 Node、沙箱、工作区、桥接、MCP、OAuth 和固定连接均通过；工具自身 18 个测试文件、199 项测试全部通过。

## 发现的问题

E 盘原始目录的 `.git` 指向共享模块 Git 目录，而该模块配置中的 `core.worktree` 仍指向 `E:\project\_worktrees\drive-soc-estimate-20261026`。因此：

- 文件读取、文件搜索和图片读取以 E 盘目录为边界，可以直接读取原始目录。
- 普通 Git 子进程可能把原始目录和另一个 worktree 混淆。涉及分支、提交、状态和 diff 时，必须显式同时指定 `--git-dir=E:\project\tesla_master\.git\modules\app_mimo` 与 `--work-tree=E:\project\tesla_master\app_mimo`，并再次核对 `rev-parse --show-toplevel`。
- `workspace_info` 返回的工程类型/脚本只是识别元信息，不能单独证明 Git 身份；要与 `workspace`、顶层文件搜索和显式 Git 核验一起使用。

本次没有修改共享 Git 配置，也没有删除或移动任何 worktree，避免影响其他任务。Project Hub 报告入口因 Git 将 E 盘目录解析为另一个 worktree 而拒绝绑定；没有使用其他 worktree 身份冒报本次状态。

## PR #27 交叉核对

PR #27 在 `883f1722e281fb0e06a98bb5d9b29ee97fba42dd` 时仍为 Draft，实际为 17 项成功、Android 1 项失败；API 35 instrumentation 和 focused Android 成功。此前把 android-summary 成功误记成 Android 全绿，现已更正。完整 XML 确认双变体唯一失败为设置页版本断言仍要求 build77，实际为 build79。后续修复与合并状态以 PR #27 的最新提交及检查为准。该 PR 分支不等于 E 盘原始目录当前分支；原始目录当前分支为 `codex/miniprogram-e-migration-20261029`，并且存在未提交改动。

## 正确使用流程

1. 让 Codex 以 `E:\project\tesla_master\app_mimo` 作为工作区根目录启动连接。
2. 首先运行 `c2c sandbox-allow --json` 和 `c2c doctor -w E:\project\tesla_master\app_mimo --json`。
3. Doctor 通过后，在 ChatGPT Project 中只使用 `Codex with ChatGPT · app_mimo` 连接器。
4. 先用工作区文件读取/搜索确认路径，再用显式 `--git-dir` + `--work-tree` 核对分支、提交和 diff。
5. Codex 本地执行修改、测试和 Git 操作；ChatGPT 只通过连接器做规划和审查。执行结果通过记录接口提供，不把日志、diff 或密钥粘贴到网页端。

后续若要让 C2C 的 Git 工具自动识别 E 盘原始目录，应单独为 C2C 增加共享 worktree 绑定处理并补回归测试；这属于工具代码变更，当前未执行。
